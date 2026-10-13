from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import monitor_server as monitor
from claim_presentation import validate_presentation

TEXT = "在岗位要求相近时，需要比较准备项目的不同路径。"


def presentation():
    signal = dict(level="low", reason="限定了适用条件，需对照具体岗位。", evidence=TEXT)
    return dict(category="求职", viewpoint="岗位要求相近时比较项目准备路径", anxiety=dict(signal),
                cocoon=dict(signal), cognition=dict(signal), suggestions=["比较不同岗位要求，再选择准备路径。"])


class PresentationContractTest(unittest.TestCase):
    def test_new_annotation_survives_monitor_protocol_and_legacy_remains_optional(self):
        claim = dict(quote=TEXT, factual=False, uncertain=False)
        source = dict(text=TEXT, kind="text")
        _, old = monitor.validate_extraction(dict(claims=[claim]), source)
        self.assertNotIn("presentation", old[0])
        _, new = monitor.validate_extraction(dict(claims=[dict(claim, presentation=presentation())]), source)
        self.assertEqual(presentation(), new[0]["presentation"])

    def test_unknown_is_allowed_but_missing_evidence_cannot_mean_low(self):
        value = presentation()
        value["anxiety"].update(level="unknown", evidence="")
        self.assertEqual(value, validate_presentation(value, TEXT))
        value["anxiety"]["level"] = "low"
        with self.assertRaises(ValueError):
            validate_presentation(value, TEXT)

    def test_invalid_category_label_foreign_evidence_and_oversized_suggestion_are_rejected(self):
        values = []
        for key, replacement in (("category", "某演员"), ("viewpoint", "就业"), ("suggestions", ["x" * 121])):
            value = presentation(); value[key] = replacement; values.append(value)
        for key, replacement in (("evidence", "另一个未授权作品的原文"), ("level", "certain"), ("reason", "")):
            value = presentation(); value["cognition"][key] = replacement; values.append(value)
        for value in values:
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_presentation(value, TEXT)

    def test_protocol_rejects_bad_extension_instead_of_silently_showing_it(self):
        value = presentation(); value["anxiety"]["evidence"] = "不属于本次输入"
        with self.assertRaises(monitor.UpstreamError):
            monitor.validate_extraction(dict(claims=[dict(quote=TEXT, factual=False, uncertain=False, presentation=value)]), dict(text=TEXT, kind="text"))


if __name__ == "__main__":
    unittest.main()
