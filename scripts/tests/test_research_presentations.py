from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_presentations import make_presentation


class SyntheticPresentationTest(unittest.TestCase):
    def test_partial_version_does_not_borrow_later_emotion(self):
        quote = "岗位要求相近时，应届生需要准备项目"
        version = dict(id="v1", work_id="w", text=quote)
        claim = dict(version_id="v1", work_id="w", quote=quote, conditions="岗位要求相近时",
                     dimension="准备项目", subject="应届生", topic="求职", uncertain=False)
        annotation = make_presentation(version, claim, [])
        self.assertEqual("unknown", annotation["anxiety"]["level"])
        with self.assertRaises(AssertionError):
            make_presentation(version, claim, [dict(version_id="v3", work_id="w")])

    def test_rebuttal_and_conditions_are_not_shortened_into_support(self):
        quote = "有人主张“所有学生都要直接就业”，但我反对在这些条件下直接采用这一建议"
        version = dict(id="v", work_id="w", text=quote)
        claim = dict(version_id="v", work_id="w", quote=quote, conditions="当前", dimension="直接就业",
                     subject="学生", topic="教育", uncertain=False)
        annotation = make_presentation(version, claim, [])
        self.assertIn("反对", annotation["viewpoint"])
        self.assertIn("条件", annotation["viewpoint"])
        self.assertLessEqual(len(annotation["viewpoint"]), 48)

    def test_negated_anxiety_is_low_content_signal_not_viewer_diagnosis(self):
        quote = "在时间允许时，可以比较不同的升学路径"
        mood = "作者说自己并不焦虑。"
        version = dict(id="v", work_id="w", text=quote + mood)
        claim = dict(version_id="v", work_id="w", quote=quote, conditions="在时间允许时", dimension="升学路径",
                     subject="学生", topic="教育", uncertain=False)
        emotion = dict(version_id="v", work_id="w", context_span=[len(quote), len(quote + mood)], expression="并不焦虑", attribution="negated")
        annotation = make_presentation(version, claim, [emotion])
        self.assertEqual("low", annotation["anxiety"]["level"])
        self.assertEqual(mood, annotation["anxiety"]["evidence"])
        self.assertIn("不据此推断", annotation["anxiety"]["reason"])


if __name__ == "__main__":
    unittest.main()
