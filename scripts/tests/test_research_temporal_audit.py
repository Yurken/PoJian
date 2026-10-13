import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("audit", Path(__file__).resolve().parents[1] / "audit_research_dataset.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ResearchTemporalAuditTest(unittest.TestCase):
    def setUp(self):
        self.works = {"past": dict(split="test", identity_group="g")}
        self.versions = {"h": dict(work_id="past", observed_at=10, available_at=15),
                         "c": dict(work_id="now", available_at=20, split="test")}
        self.pair = dict(current_version_id="c", query_at=20, split="test", identity_group="g",
                         history=[dict(work_id="past", version_id="h", observed_at=10, available_at=15)])

    def check(self):
        audit.check_history_pair(self.pair, self.versions, self.works)

    def test_past_history_and_current_available_at_query_accepted(self):
        self.check()

    def test_same_time_history_rejected(self):
        self.versions["h"]["observed_at"] = self.pair["history"][0]["observed_at"] = 20
        with self.assertRaisesRegex(AssertionError, "strictly earlier"):
            self.check()

    def test_old_observation_with_future_result_rejected(self):
        self.versions["h"]["available_at"] = self.pair["history"][0]["available_at"] = 21
        with self.assertRaisesRegex(AssertionError, "future completion"):
            self.check()

    def test_test_history_cannot_use_training_or_other_source_group(self):
        for field, value in [("split", "train"), ("identity_group", "another")]:
            original = copy.deepcopy(self.works)
            self.works["past"][field] = value
            with self.assertRaisesRegex(AssertionError, "cross-group"):
                self.check()
            self.works = original

    def test_falsified_availability_in_pair_rejected(self):
        self.pair["history"][0]["available_at"] = 5
        with self.assertRaisesRegex(AssertionError, "future completion"):
            self.check()

    def test_future_current_version_rejected(self):
        self.versions["c"]["available_at"] = 21
        with self.assertRaisesRegex(AssertionError, "current unavailable"):
            self.check()


if __name__ == "__main__":
    unittest.main()
