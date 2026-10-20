"""Exercise the Room retention queries against SQLite with boundary fixtures."""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def query_for(file_name, method):
    source = (ROOT / "app/src/main/java/com/emerge/app/data" / file_name).read_text(encoding="utf-8")
    return re.search(r'@Query\("""([^\"]+)"""\)\s+suspend fun ' + method, source).group(1)


class StorageRetentionQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE browse_events(id INTEGER PRIMARY KEY, timestamp INTEGER, endTimestamp INTEGER,
                parentEventId INTEGER, audioSessionId TEXT, sourceType TEXT DEFAULT 'accessibility');
            CREATE TABLE browse_samples(visitId INTEGER, capturedAt INTEGER);
            CREATE TABLE audio_sessions(id TEXT PRIMARY KEY, startedAt INTEGER, status TEXT);
            CREATE TABLE audio_transcripts(sessionId TEXT, createdAt INTEGER);
            INSERT INTO browse_events(id,timestamp,endTimestamp) VALUES(99,9000,9100);
        """)

    def visit(self, id, start, end=None, parent=None, audio=None):
        self.db.execute("INSERT INTO browse_events VALUES(?,?,?,?,?,'accessibility')",
                        (id, start, start if end is None else end, parent, audio))

    def oldest_visit(self, cutoff):
        return self.db.execute(query_for("BrowseEventDao.kt", "oldestStorageVisit"),
                               {"beforeTimestamp": cutoff}).fetchone()

    def oldest_audio(self, cutoff):
        return self.db.execute(query_for("AudioDao.kt", "oldestStorageSession"),
                               {"beforeTimestamp": cutoff}).fetchone()

    def test_period_cleanup_excludes_boundary_and_recent_records(self):
        self.visit(1, 1000)
        self.visit(2, 2000)
        self.assertIsNone(self.oldest_visit(1000))
        self.assertEqual((1, 1000), self.oldest_visit(1001))

    def test_long_visit_does_not_block_expired_short_visit(self):
        self.visit(1, 100, 2500)
        self.visit(2, 200, 999)
        self.assertEqual((2, 200), self.oldest_visit(1000))

    def test_recent_child_and_sample_keep_whole_visit(self):
        self.visit(1, 100)
        self.visit(2, 200)
        self.visit(3, 300)
        self.visit(4, 1000, parent=1)
        self.db.execute("INSERT INTO browse_samples VALUES(2,1000)")
        self.assertEqual((3, 300), self.oldest_visit(1000))

    def test_capacity_policy_still_selects_oldest_without_age_filter(self):
        self.visit(1, 100, 9000)
        self.assertEqual((1, 100), self.oldest_visit(None))
        self.db.execute("DELETE FROM browse_events WHERE id=1")
        self.assertIsNone(self.oldest_visit(None))

    def test_period_cleanup_includes_the_last_expired_visit(self):
        self.assertIsNone(self.oldest_visit(9100))
        self.assertEqual((99, 9000), self.oldest_visit(9101))
        self.assertIsNone(self.oldest_visit(None))

    def test_recent_sample_still_protects_the_last_visit(self):
        self.db.execute("INSERT INTO browse_samples VALUES(99,10000)")
        self.assertIsNone(self.oldest_visit(10000))
        self.assertEqual((99, 9000), self.oldest_visit(10001))

    def test_completed_audio_at_boundary_is_not_expired(self):
        self.db.execute("INSERT INTO audio_sessions VALUES('boundary',1000,'completed')")
        self.assertIsNone(self.oldest_audio(1000))
        self.assertEqual(("boundary", 1000), self.oldest_audio(1001))

    def test_active_audio_and_recent_material_are_protected(self):
        self.db.executemany("INSERT INTO audio_sessions VALUES(?,?,?)", [
            ("active", 100, "recording"), ("recent-transcript", 200, "completed"),
            ("recent-event", 300, "completed"), ("expired", 400, "completed")])
        self.db.execute("INSERT INTO audio_transcripts VALUES('recent-transcript',1000)")
        self.visit(1, 1000, audio="recent-event")
        self.assertEqual(("expired", 400), self.oldest_audio(1000))

    def test_capacity_keeps_latest_audio_but_expired_audio_can_be_removed(self):
        self.db.execute("INSERT INTO audio_sessions VALUES('latest',100,'completed')")
        self.visit(1, 100, parent=99, audio="latest")
        self.assertIsNone(self.oldest_audio(None))
        self.assertEqual(("latest", 100), self.oldest_audio(10000))

    def test_latest_audio_with_recent_transcript_is_not_expired(self):
        self.db.execute("INSERT INTO audio_sessions VALUES('latest',100,'completed')")
        self.visit(1, 100, parent=99, audio="latest")
        self.db.execute("INSERT INTO audio_transcripts VALUES('latest',10000)")
        self.assertIsNone(self.oldest_audio(10000))
        self.assertEqual(("latest", 100), self.oldest_audio(10001))


if __name__ == "__main__":
    unittest.main()
