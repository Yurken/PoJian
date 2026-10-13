"""Ensure context rows cannot inflate published target metrics."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("scoring", Path(__file__).resolve().parents[1] / "summarize_research_evaluation.py")
scoring = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scoring)


class ResearchScoringTest(unittest.TestCase):
    def test_context_rows_excluded_even_when_correct(self):
        rows = [dict(sample_id="x.history0", expected="FIRST", predicted="FIRST"),
                dict(sample_id="x.target", expected="REPEATED", predicted="NEW")]
        selected = scoring.target_rows(rows, {"x": dict(expected_legacy_increment="REPEATED")})
        self.assertEqual(1, len(selected))
        self.assertEqual(0, scoring.score([(r["expected"], r["predicted"]) for r in selected])["accuracy"])

    def test_missing_duplicate_or_stale_targets_rejected(self):
        gold = {"x": dict(expected_legacy_increment="REPEATED")}
        good = dict(sample_id="x.target", expected="REPEATED", predicted="NEW")
        for rows in ([], [good, good], [dict(good, expected="NEW")], [dict(good, sample_id="y.target")]):
            with self.assertRaises(ValueError):
                scoring.target_rows(rows, gold)

    def test_unknown_and_missed_claims_stay_in_denominator(self):
        result = scoring.score([("REPEATED", "NO_CLAIM"), ("UNKNOWN", "UNKNOWN")])
        self.assertEqual(2, result["total"])
        self.assertEqual(.5, result["accuracy"])
        self.assertEqual(.5, result["macro_f1"])

    def test_empty_scores_are_unavailable_not_perfect(self):
        result = scoring.score([])
        self.assertIsNone(result["accuracy"])
        self.assertIsNone(result["macro_f1"])


if __name__ == "__main__":
    unittest.main()
