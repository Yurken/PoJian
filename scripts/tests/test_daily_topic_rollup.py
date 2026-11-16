"""Run the production v9->v10 SQL/triggers and DAO queries on SQLite, including a bounded query benchmark."""
import pathlib
import hashlib
import re
import sqlite3
import statistics
import time
import unittest
from test_work_thumbnail_migration import ROOT, install_schema

DATA = ROOT / "app/src/main/java/com/emerge/app/data"


def statements():
    source = (DATA / "DailyTopicRollupSchema.kt").read_text()
    return [s[3:-3] if s.startswith('"""') else s[1:-1]
            for s in re.findall(r'db\.execSQL\(("""[\s\S]*?"""|"[^"\n]*")\)', source)]


def query(method):
    source = (DATA / "DailyTopicRollup.kt").read_text()
    pattern = r'@Query\(("""[\s\S]*?"""|"[^"\n]*")\)\s+suspend fun (\w+)\('
    value = next(value for value, name in re.findall(pattern, source) if name == method)
    return value[3:-3] if value.startswith('"""') else value[1:-1]


class DailyTopicRollupTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        install_schema(self.db, 9)
        # Migration retains old source and queues it for one-time Kotlin semantic projection.
        self.event(10001)
        for sql in statements():
            self.db.execute(sql)

    def event(self, id, parent=None, session=None):
        self.db.execute("INSERT INTO browse_events (id,packageName,timestamp,eventType,visibleText,parentEventId,audioSessionId) VALUES (?,'reader',?,0,'source',?,?)", (id, id, parent, session))

    def project(self, id, date="2026-11-16", topic="就业", work="same-work", fact="same-fact", new=True, guided=1, duration=1250):
        self.db.execute("INSERT INTO rollup_contributions VALUES(?,?,?,?,1,?,?,1,?,'fingerprint')",
                        (id, date, topic, work, guided, duration, id))
        self.db.execute("INSERT INTO rollup_facts VALUES(?,?,?,?,?,'reader',?,?)",
                        (id, date, topic, fact, f"fact {fact}", id, new))
        self.db.execute("DELETE FROM rollup_dirty WHERE eventId=?", (id,))

    def counts(self):
        return self.db.execute("SELECT viewEvents,uniqueWorks,dedupFacts,newFacts,guidanceEvents,guidanceDurationSec,analyzedEvents FROM daily_topic_rollup WHERE dateKey='2026-11-16' AND topicName='就业'").fetchone()

    def summary(self):
        params = {"first": "2026-11-01", "last": "2026-11-30", "through": 2**63-1}
        future = self.db.execute(query("hasFuture"), params).fetchone()[0]
        keys = self.db.execute(query("semanticKeys"), params).fetchall()
        hashlib.sha256("\n".join(f"{id}:{fingerprint}" for id, fingerprint in keys).encode()).hexdigest()
        return (self.db.execute(query("filteredDays" if future else "days"), params).fetchall(),
                self.db.execute(query("eventTotals"), params).fetchone(),
                self.db.execute(query("factTotals"), params).fetchone(),
                self.db.execute(query("guidanceMillis"), params).fetchone())

    def test_migration_preserves_source_and_marks_old_rows_dirty(self):
        self.assertEqual([(10001,)], self.db.execute("SELECT eventId FROM rollup_dirty").fetchall())
        self.assertEqual("source", self.db.execute("SELECT visibleText FROM browse_events WHERE id=10001").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())
        schema = ROOT / "app/schemas/com.emerge.app.data.BrowseDatabase/10.json"
        if schema.exists():
            with sqlite3.connect(":memory:") as fresh:
                install_schema(fresh, 10)
                for table, in fresh.execute("SELECT name FROM sqlite_master WHERE type='table' AND name!='sqlite_sequence'"):
                    for pragma in ("table_info", "foreign_key_list", "index_list"):
                        self.assertEqual(sorted(fresh.execute(f"PRAGMA {pragma}({table})").fetchall()),
                                         sorted(self.db.execute(f"PRAGMA {pragma}({table})").fetchall()), (table, pragma))

    def test_distinct_refs_and_subsecond_duration_survive_deletion(self):
        for id in (1, 2):
            self.event(id)
            self.project(id, new=id == 1)
        self.assertEqual((2, 1, 1, 1, 2, 2, 2), self.counts())
        self.db.execute("DELETE FROM browse_events WHERE id=1")
        self.assertEqual((1, 1, 1, 0, 1, 1, 1), self.counts())
        self.db.execute("DELETE FROM browse_events WHERE id=2")
        self.assertIsNone(self.counts())

    def test_reanalysis_replaces_old_fact_and_counts_atomically(self):
        self.event(1)
        self.project(1)
        self.db.commit()
        self.db.execute("BEGIN")
        self.db.execute("DELETE FROM rollup_contributions WHERE eventId=1")
        self.db.execute("DELETE FROM rollup_facts WHERE eventId=1")
        self.project(1, work="other-work", fact="other-fact", new=False, guided=0, duration=0)
        self.assertEqual((1, 1, 1, 0, 0, 0, 1), self.counts())
        self.db.rollback()
        self.assertEqual((1, 1, 1, 1, 1, 1, 1), self.counts())

    def test_multi_day_distinct_totals_do_not_sum_daily_distinct_counts(self):
        self.event(1)
        self.project(1, date="2026-11-15")
        self.project(1, date="2026-11-16")
        self.event(2)
        self.project(2, date="2026-11-16", new=False)
        days, events, facts, duration = self.summary()
        self.assertEqual(2, len(days))
        self.assertEqual((2, 1, 2, 2), events)
        self.assertEqual((1, 1), facts)
        self.assertEqual((3750,), duration)

    def test_audio_cascade_pruning_and_clear_remove_references(self):
        self.db.execute("INSERT INTO audio_sessions(id,packageName,source,title,startedAt) VALUES('s','reader','playback','audio',1)")
        self.event(1, session="s")
        self.event(2, parent=1)
        self.project(1)
        self.project(2)
        self.db.execute("DELETE FROM audio_sessions WHERE id='s'")
        self.assertIsNone(self.counts())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_text_and_semantic_changes_queue_refresh_in_same_transaction(self):
        self.event(1)
        self.project(1)
        self.db.execute("UPDATE browse_events SET visibleText='edited' WHERE id=1")
        self.assertIn((1,), self.db.execute("SELECT eventId FROM rollup_dirty").fetchall())
        self.db.execute("DELETE FROM rollup_dirty")
        self.db.execute("INSERT INTO browse_semantics VALUES(1,'edited',1,'[]','text')")
        self.assertEqual([(1,)], self.db.execute("SELECT eventId FROM rollup_dirty").fetchall())
        self.db.execute("DELETE FROM rollup_dirty")
        self.db.execute("DELETE FROM browse_semantics")
        self.assertEqual([(1,)], self.db.execute("SELECT eventId FROM rollup_dirty").fetchall())

    def test_history_is_bounded_deduplicated_and_excludes_current_or_future(self):
        for id in range(1, 31):
            self.event(id)
            self.project(id, fact=f"fact-{id // 2}", topic="就业" if id < 25 else "健康")
        rows = self.db.execute(query("history"), {"topic": "就业", "limit": 15, "before": 20}).fetchall()
        self.assertLessEqual(len(rows), 15)
        self.assertEqual(len(rows), len({row[0] for row in rows}))
        self.assertTrue(all(row[3] < 20 for row in rows))

    def test_future_same_day_records_do_not_enter_current_summary(self):
        for id in (1, 2):
            self.event(id)
            self.project(id, work=f"work-{id}", fact=f"fact-{id}")
        params = {"first": "2026-11-01", "last": "2026-11-30", "through": 1}
        self.assertEqual((1,), self.db.execute(query("hasFuture"), params).fetchone())
        self.assertEqual((1, 1, 1, 1), self.db.execute(query("eventTotals"), params).fetchone())
        self.assertEqual((1, 1), self.db.execute(query("factTotals"), params).fetchone())
        day = self.db.execute(query("filteredDays"), params).fetchone()
        self.assertEqual((1, 1, 1, 1), day[2:6])

    def test_thousand_record_summary_query_under_twenty_ms(self):
        for id in range(1, 1001):
            self.event(id)
            self.project(id, date=f"2026-11-{1 + id % 28:02}", topic=f"topic-{id % 7}",
                         work=f"work-{id // 3}", fact=f"fact-{id // 4}", new=id % 4 == 0)
        self.db.commit()
        self.summary()
        timings = []
        for _ in range(15):
            start = time.perf_counter()
            result = self.summary()
            timings.append((time.perf_counter() - start) * 1000)
        self.assertEqual(1000, result[1][0])
        median = statistics.median(timings)
        print(f"\nSQLite 1000-row materialized summary query median={median:.3f} ms; excludes ingestion, backfill, full report/PDF")
        self.assertLess(median, 20)


if __name__ == "__main__":
    unittest.main(verbosity=2)
