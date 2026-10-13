"""Fixture integrity, exported SQLite and dataset partitions; no device or model evaluation."""
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("synthetic", ROOT / "scripts/generate_synthetic_data.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class SyntheticDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.demo = generator.build_demo()
        test_root = ROOT / "app/build/synthetic-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(dir=test_root)
        cls.output = Path(cls.temp.name)
        generator.write_database(cls.output / "pojian-synthetic-demo.db", cls.demo)
        cls.research = generator.research_corpus(cls.output)
        cls.works = [json.loads(line) for line in (cls.output / "works.jsonl").read_text(encoding="utf8").splitlines()]
        cls.versions = [json.loads(line) for line in (cls.output / "observations.jsonl").read_text(encoding="utf8").splitlines()]

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_bundled_asset_is_reproducible_and_synthetic(self):
        self.assertEqual(self.demo, json.loads((ROOT / "app/src/main/assets/synthetic/demo-v1.json").read_text(encoding="utf8")))
        self.assertEqual((generator.dumps(self.demo) + "\n").encode("utf8"),
                         (ROOT / "app/src/main/assets/synthetic/demo-v1.json").read_bytes())
        self.assertTrue(self.demo["synthetic"])
        self.assertEqual(0, self.demo["participantCount"])
        self.assertEqual([], self.demo["media"])
        self.assertTrue(all(w["nativeId"] == "" for w in self.demo["works"]))

    def test_sqlite_integrity_relations_and_rebuild(self):
        path = self.output / "pojian-synthetic-demo.db"
        generator.write_database(path, self.demo)
        db = sqlite3.connect(path)
        try:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            self.assertEqual(8, db.execute("PRAGMA user_version").fetchone()[0])
            for table, count in [("observed_works", 588), ("browse_events", 770), ("browse_samples", 2016),
                                 ("browse_semantics", 770), ("work_thumbnails", 0), ("monitor_tasks", 0)]:
                self.assertEqual(count, db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            self.assertEqual(588, db.execute("SELECT COUNT(DISTINCT workKey) FROM browse_events").fetchone()[0])
        finally:
            db.close()

    def test_cannot_target_production_database(self):
        sentinel = self.output / "zhiheng.db"
        sentinel.write_bytes(b"existing-user-data")
        with self.assertRaises(ValueError):
            generator.write_database(sentinel, self.demo)
        self.assertEqual(b"existing-user-data", sentinel.read_bytes())

    def test_reserved_name_does_not_authorize_overwriting_an_unknown_file(self):
        folder = self.output / "unknown"
        folder.mkdir()
        sentinel = folder / "pojian-synthetic-demo.db"
        sentinel.write_bytes(b"existing-user-data")
        with self.assertRaises(ValueError):
            generator.write_database(sentinel, self.demo)
        self.assertEqual(b"existing-user-data", sentinel.read_bytes())

    def test_counts_and_splits(self):
        self.assertEqual(6000, len(self.works))
        self.assertEqual(18000, len(self.versions))
        self.assertEqual(dict(train=4200, validation=900, test=900), Counter(w["split"] for w in self.works))
        self.assertEqual({domain: 1000 for domain in generator.DOMAINS}, Counter(w["domain"] for w in self.works))
        self.assertEqual({name: 2000 for name, _ in generator.FORMATS[:3]}, Counter(w["mediaType"] for w in self.works))
        test = [w for w in self.works if w["split"] == "test"]
        self.assertEqual({name: 300 for name, _ in generator.FORMATS[:3]}, Counter(w["mediaType"] for w in test))
        self.assertEqual({domain: 150 for domain in generator.DOMAINS}, Counter(w["domain"] for w in test))

    def test_author_identity_and_near_duplicate_groups_are_partitioned_first(self):
        for key in ["authorId", "workKey", "nearDuplicateGroup", "templateFamily", "text"]:
            groups = defaultdict(set)
            for row in self.works:
                groups[row[key]].add(row["split"])
            self.assertTrue(all(len(splits) == 1 for splits in groups.values()))
        partitions = {w["workKey"]: w["split"] for w in self.works}
        self.assertTrue(all(v["split"] == partitions[v["workKey"]] for v in self.versions))

    def test_partial_versions_never_contain_future_emotions(self):
        groups = defaultdict(list)
        for row in self.versions:
            groups[row["workKey"]].append(row)
        for work in self.works:
            versions = groups[work["workKey"]]
            self.assertEqual([1, 2, 3], [v["version"] for v in versions])
            self.assertLess(versions[0]["availableAt"], versions[1]["availableAt"])
            self.assertLess(versions[1]["availableAt"], versions[2]["availableAt"])
            self.assertNotIn(work["emotion"]["context"], versions[1]["text"])
            self.assertEqual(work["text"], versions[2]["text"])

    def test_evidence_and_emotion_spans_point_back_to_text(self):
        for work in self.works:
            text, emotion = work["text"], work["emotion"]
            for name in ["expression", "cause"]:
                start, end = emotion[name + "Span"]
                self.assertEqual(emotion[name], text[start:end])
            self.assertEqual("not_inferred", emotion["viewerState"])
            for claim in work["claims"]:
                self.assertIn(claim["quote"], text)
                self.assertIn(claim["evidence"], text)
                self.assertNotIn(claim["quote"], generator.DOMAINS)


if __name__ == "__main__":
    unittest.main()
