"""Foreign-key/adversarial checks against the actual project database schema, entirely on host."""
from contextlib import closing
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
DATABASE = ROOT / "app/build/project-research-database/pojian-research-v2.sqlite"


class ProjectResearchDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.source = sqlite3.connect(f"file:{DATABASE.as_posix()}?mode=ro", uri=True)
        self.source.row_factory = sqlite3.Row
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript((ROOT / "app/src/main/assets/synthetic/research-schema.sql").read_text(encoding="utf8"))
        self.contact = dict(self.source.execute("SELECT * FROM contacts ORDER BY row_id LIMIT 1").fetchone())
        self.copy("works", "id=?", (self.contact["work_id"],))
        self.copy("versions", "id=?", (self.contact["version_id"],))
        self.copy("profiles", "1=1", ())
        self.copy("user_days", "id=?", (self.contact["day_id"],))
        self.copy("contacts", "row_id=?", (self.contact["row_id"],))

    def tearDown(self):
        self.db.close()
        self.source.close()

    def copy(self, table, where, args):
        for row in self.source.execute(f"SELECT * FROM {table} WHERE {where}", args):
            self.db.execute(f"INSERT INTO {table} VALUES ({','.join('?' for _ in row)})", tuple(row))

    def test_cross_profile_day_cannot_accept_visit(self):
        other = self.source.execute("SELECT id FROM profiles WHERE id<>? LIMIT 1", (self.contact["profile_id"],)).fetchone()[0]
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE contacts SET profile_id=?", (other,))

    def test_another_work_cannot_reuse_original_version(self):
        other = self.source.execute("SELECT id FROM works WHERE id<>? LIMIT 1", (self.contact["work_id"],)).fetchone()[0]
        self.copy("works", "id=?", (other,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE contacts SET work_id=?", (other,))

    def test_corpus_version_and_work_cannot_be_deleted_under_visit(self):
        for table in ("versions", "works"):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(f"DELETE FROM {table}")

    def test_invalid_interval_and_orphan_interval_rejected(self):
        for row in [(self.contact["row_id"], 0, 10, 5), (-999, 0, 10, 20)]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("INSERT INTO visible_intervals VALUES (?,?,?,?)", row)

    def test_version_ordinal_is_unique_per_work(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO versions SELECT 'another-version',work_id,ordinal,observed_at,available_at,text,payload FROM versions")

    def test_delivery_database_can_be_opened_read_only(self):
        with self.assertRaises(sqlite3.OperationalError):
            self.source.execute("DELETE FROM contacts")
        self.assertEqual(33360, self.source.execute("SELECT count(*) FROM contacts").fetchone()[0])

    def test_presentation_cannot_attach_to_another_work_or_claim_real_provenance(self):
        self.copy("claim_presentations", "version_id=?", (self.contact["version_id"],))
        other = self.source.execute("SELECT id FROM works WHERE id<>? LIMIT 1", (self.contact["work_id"],)).fetchone()[0]
        self.copy("works", "id=?", (other,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE claim_presentations SET work_id=?", (other,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE claim_presentations SET provenance='online_model'")


if __name__ == "__main__":
    unittest.main()
