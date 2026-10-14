"""Execute the actual Room migration against preserved version-8 history, without an emulator."""
import re
import sqlite3
import unittest
from test_work_thumbnail_migration import ROOT, install_schema


class WorkOriginalMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        install_schema(self.db, 8)
        self.db.execute("INSERT INTO observed_works VALUES ('w','reader','标题','作者','author_title',1000,'legacy','')")
        for event in (1, 2):
            self.db.execute("INSERT INTO browse_events (id,packageName,timestamp,eventType,visibleText,workKey) VALUES (?,'reader',1000,0,'原文','w')", (event,))
        source = (ROOT / "app/src/main/java/com/emerge/app/data/BrowseDatabase.kt").read_text(encoding="utf-8")
        migration = source.split("val MIGRATION_8_9", 1)[1].split("val MIGRATION_7_8", 1)[0]
        statements = re.findall(r'db\.execSQL\(("""[\s\S]*?"""|"[^"\n]*")\)', migration)
        self.assertEqual(1, len(statements))
        for statement in statements:
            self.db.execute(statement[3:-3] if statement.startswith('"""') else statement[1:-1])

    def tearDown(self):
        self.db.close()

    def test_migration_preserves_history_and_matches_fresh_schema(self):
        self.assertEqual(2, self.db.execute("SELECT COUNT(*) FROM browse_events").fetchone()[0])
        with sqlite3.connect(":memory:") as fresh:
            install_schema(fresh, 9)
            for table, in fresh.execute("SELECT name FROM sqlite_master WHERE type='table' AND name!='sqlite_sequence'"):
                for pragma in ("table_info", "foreign_key_list", "index_list"):
                    self.assertEqual(sorted(fresh.execute(f"PRAGMA {pragma}({table})").fetchall()),
                                     sorted(self.db.execute(f"PRAGMA {pragma}({table})").fetchall()), (table, pragma))

    def test_last_visit_deletion_cascades_original_link_only(self):
        dao = (ROOT / "app/src/main/java/com/emerge/app/data/BrowseEventDao.kt").read_text(encoding="utf-8")
        trim = re.search(r'@Query\("(DELETE FROM observed_works[^"\n]+)"\)', dao).group(1)
        self.db.execute("INSERT INTO work_originals VALUES ('w','https://v.douyin.com/AbCd/','user_link',2000)")
        self.db.execute("DELETE FROM browse_events WHERE id=1")
        self.db.execute(trim)
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM work_originals").fetchone()[0])
        self.db.execute("DELETE FROM browse_events WHERE id=2")
        self.db.execute(trim)
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM work_originals").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_originals_cannot_outlive_or_duplicate_their_work(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO work_originals VALUES ('missing','https://v.douyin.com/AbCd/','user_link',2000)")
        self.db.execute("INSERT INTO work_originals VALUES ('w','https://v.douyin.com/AbCd/','user_link',2000)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO work_originals VALUES ('w','https://v.douyin.com/Other/','user_link',3000)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
