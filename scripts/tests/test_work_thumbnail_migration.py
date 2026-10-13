"""Host SQLite checks of the actual 7->8 SQL and exported Room schemas; no device emulation."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/com.emerge.app.data.BrowseDatabase"


def install_schema(connection, version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    for entity in schema["entities"]:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity["indices"]:
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))


class WorkThumbnailMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        install_schema(self.db, 7)
        self.db.execute("INSERT INTO observed_works VALUES ('w','reader','标题','作者','author_title',1000)")
        for event_id in (1, 2):
            self.db.execute("""INSERT INTO browse_events
                (id,packageName,timestamp,eventType,visibleText,workKey,identityMethod)
                VALUES (?,'reader',1000,32,'历史正文','w','author_title')""", (event_id,))
        self.db.execute("""INSERT INTO browse_events
            (id,packageName,timestamp,eventType,visibleText,sourceType,parentEventId,workKey)
            VALUES (3,'reader',1000,0,'OCR原文','screen_ocr',1,'w')""")
        self.db.execute("INSERT INTO browse_samples VALUES (1,1,1000,32,'历史采样')")
        self.db.execute("INSERT INTO browse_semantics VALUES (3,'OCR原文',1100,'[]','frame')")
        source = (ROOT / "app/src/main/java/com/emerge/app/data/BrowseDatabase.kt").read_text(encoding="utf-8")
        migration = source.split("val MIGRATION_7_8", 1)[1].split("val MIGRATION_6_7", 1)[0]
        statements = re.findall(r'db\.execSQL\(("""[\s\S]*?"""|"[^"\n]*")\)', migration)
        self.assertEqual(4, len(statements))
        for statement in statements:
            self.db.execute(statement[3:-3] if statement.startswith('"""') else statement[1:-1])

    def tearDown(self):
        self.db.close()

    def count(self, table):
        return self.db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

    def thumbnail(self):
        self.db.execute("INSERT INTO work_thumbnails VALUES ('w','reader','existing.jpg',2000,'page_screenshot',100,100,2048)")

    def trim_works(self):
        # Read the production query, avoiding a second implementation of deletion dependencies.
        dao = (ROOT / "app/src/main/java/com/emerge/app/data/BrowseEventDao.kt").read_text(encoding="utf-8")
        sql = re.search(r'@Query\("(DELETE FROM observed_works[^"\n]+)"\)', dao).group(1)
        self.db.execute(sql)

    def test_preserves_records_samples_semantics_without_historic_images(self):
        self.assertEqual((3, 1, 1, 0), tuple(self.count(t) for t in
                         ("browse_events", "browse_samples", "browse_semantics", "work_thumbnails")))
        self.assertEqual(("legacy", ""), self.db.execute("SELECT identityEvidence,nativeId FROM observed_works").fetchone())
        self.assertEqual("历史正文", self.db.execute("SELECT visibleText FROM browse_events WHERE id=2").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_migrated_tables_match_fresh_room_schema(self):
        with sqlite3.connect(":memory:") as fresh:
            install_schema(fresh, 8)
            for table, in fresh.execute("SELECT name FROM sqlite_master WHERE type='table' AND name!='sqlite_sequence'"):
                for pragma in ("table_info", "foreign_key_list", "index_list"):
                    self.assertEqual(sorted(fresh.execute(f"PRAGMA {pragma}({table})").fetchall()),
                                     sorted(self.db.execute(f"PRAGMA {pragma}({table})").fetchall()), (table, pragma))

    def test_deleting_one_visit_preserves_shared_thumbnail_and_other_visit(self):
        self.thumbnail()
        self.db.execute("DELETE FROM browse_events WHERE id=1")
        self.trim_works()
        self.assertEqual((1, 0, 0, 1), tuple(self.count(t) for t in
                         ("browse_events", "browse_samples", "browse_semantics", "work_thumbnails")))
        self.db.execute("DELETE FROM browse_events WHERE id=2")
        self.trim_works()
        self.assertEqual(0, self.count("work_thumbnails"))

    def test_clear_removes_thumbnail_dependencies(self):
        self.thumbnail()
        self.db.execute("DELETE FROM browse_events")
        self.trim_works()
        self.assertEqual(0, self.count("work_thumbnails"))
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_orphan_metadata_and_duplicate_representatives_rejected(self):
        self.thumbnail()
        with self.assertRaises(sqlite3.IntegrityError):
            self.thumbnail()
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO work_thumbnails VALUES ('missing','reader','other.jpg',2000,'page_screenshot',100,100,2048)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
