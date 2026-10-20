import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location(
    "demo_snapshot", Path(__file__).parents[1] / "prepare-demo-snapshot.py")
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class DemoSnapshotTest(unittest.TestCase):
    def test_committed_wal_records_survive_without_credentials_or_device_queue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            (source / "databases").mkdir(parents=True)
            (source / "shared_prefs").mkdir()
            (source / "shared_prefs/audio_analysis.xml").write_text(
                '<map><string name="token">device-secret</string>'
                '<string name="endpoint">https://private.example</string>'
                '<boolean name="enabled" value="true"/></map>')
            (source / "shared_prefs/capture.xml").write_text(
                '<map><boolean name="enabled" value="true"/>'
                '<set name="allowed_packages"><string>example.app</string></set></map>')
            # Keep a committed WAL open so that ignoring WAL would fail this test.
            connection = sqlite3.connect(source / "databases/zhiheng.db")
            try:
                connection.executescript("""
                    PRAGMA journal_mode=WAL;
                    PRAGMA user_version=9;
                    CREATE TABLE browse_events (id INTEGER PRIMARY KEY, visibleText TEXT);
                    CREATE TABLE monitor_tasks (payload TEXT, leaseOwner TEXT, leaseUntil INTEGER, status TEXT);
                    CREATE TABLE work_thumbnails (fileName TEXT, byteCount INTEGER);
                    INSERT INTO browse_events VALUES (1, 'original record');
                    INSERT INTO monitor_tasks VALUES ('device envelope','worker',123,'queued');
                """)
                connection.commit()
                summary = demo.prepare(source, root / "assets")
                self.assertEqual(1, summary["tables"]["browse_events"])
                with zipfile.ZipFile(root / "assets/demo-snapshot.zip") as archive:
                    extracted = root / "restored.db"
                    extracted.write_bytes(archive.read("databases/zhiheng.db"))
                    prefs = archive.read("shared_prefs/audio_analysis.xml")
                    self.assertNotIn(b"device-secret", prefs)
                    self.assertNotIn(b"private.example", prefs)
                    self.assertEqual("true", ET.fromstring(prefs).find("boolean[@name='enabled']").get("value"))
                    self.assertEqual("true", ET.fromstring(prefs).find("boolean[@name='demo_online_migrated']").get("value"))
                    capture = ET.fromstring(archive.read("shared_prefs/capture.xml"))
                    self.assertEqual("example.app", capture.find("set/string").text)
                    self.assertEqual("false", capture.find("boolean").get("value"))
                    self.assertFalse(any(name.endswith("-wal") for name in archive.namelist()))
                restored = sqlite3.connect(extracted)
                try:
                    self.assertEqual([(1, "original record")], restored.execute("SELECT * FROM browse_events").fetchall())
                    self.assertEqual([("", "", 0, "cancelled")], restored.execute("SELECT * FROM monitor_tasks").fetchall())
                finally:
                    restored.close()
                self.assertEqual("device envelope", connection.execute("SELECT payload FROM monitor_tasks").fetchone()[0])
                self.assertIn("device-secret", (source / "shared_prefs/audio_analysis.xml").read_text())
                with self.assertRaises(FileExistsError):
                    demo.prepare(source, root / "assets")
            finally:
                connection.close()


if __name__ == "__main__":
    unittest.main()
