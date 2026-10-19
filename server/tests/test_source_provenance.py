import copy
import unittest

from test_monitor_server import FakeProviders, claim, m, request_data


class SourceProvenanceTests(unittest.TestCase):
    evidence = "青禾研究所独立调查300家企业，形成《就业样本甲》，岗位增加10%"

    def provenance(self, **updates):
        source = dict(producer="青禾研究所", material="《就业样本甲》", kind="primary",
                      basis=self.evidence, upstream=[])
        return dict(complete=True, sources=[{**source, **updates}])

    def extract(self, provenance, evidence=None):
        evidence = evidence or self.evidence
        row = claim(evidence=evidence, source_provenance=provenance)
        source = m.validate_monitor(request_data(text=row["quote"] + "。" + evidence))
        return m.validate_extraction({"claims": [row]}, source)[1][0]

    def test_original_collection_survives_extraction_and_pipeline(self):
        row = claim(evidence=self.evidence, source_provenance=self.provenance())
        result = m.analyze_monitor(m.validate_monitor(request_data(
            text=row["quote"] + "。" + self.evidence, check_facts=False)), FakeProviders(claims=[row]))
        self.assertEqual(self.provenance(), result["claims"][0].get("source_provenance"))

    def test_missing_optional_field_remains_legacy_compatible(self):
        result = m.validate_extraction({"claims": [claim()]}, m.validate_monitor(request_data()))[1][0]
        self.assertNotIn("source_provenance", result)

    def test_invented_or_cross_claim_anchors_are_rejected(self):
        for update in [dict(producer="另一机构"), dict(material="《不存在的材料》"),
                       dict(basis="青禾研究所实测了400人"), dict(upstream=["另一调查"])]:
            with self.subTest(update=update), self.assertRaises(m.UpstreamError):
                self.extract(self.provenance(**update))

    def test_distinct_material_names_without_collection_basis_cannot_be_primary(self):
        for evidence in ["青禾研究所发布《就业样本甲》，岗位增加10%",
                         "青禾研究所未独立调查300家企业，形成《就业样本甲》",
                         "据说青禾研究所独立调查300家企业，形成《就业样本甲》",
                         "青禾研究所不是独立调查300家企业，形成《就业样本甲》",
                         "如果青禾研究所独立调查300家企业，就会形成《就业样本甲》",
                         "青禾研究所计划独立调查300家企业，形成《就业样本甲》"]:
            with self.subTest(evidence=evidence), self.assertRaises(m.UpstreamError):
                self.extract(self.provenance(basis=evidence), evidence)

    def test_secondary_material_retains_explicit_common_upstream(self):
        evidence = "青禾研究所的《就业样本甲》引用《共同调查》中的数据"
        value = self.provenance(kind="secondary", basis=evidence, upstream=["《共同调查》"])
        self.assertEqual(value, self.extract(value, evidence).get("source_provenance"))

    def test_primary_must_not_hide_an_upstream_dependency(self):
        evidence = self.evidence + "，引用《共同调查》"
        with self.assertRaises(m.UpstreamError):
            self.extract(self.provenance(basis=evidence, upstream=["《共同调查》"]), evidence)

    def test_invalid_shapes_and_unbounded_values_are_rejected(self):
        valid = self.provenance()
        for value in [None, [], {**valid, "complete": "true"}, {**valid, "sources": []},
                      {**valid, "sources": valid["sources"] * 5},
                      self.provenance(kind="verified"), self.provenance(producer=""),
                      self.provenance(upstream="青禾研究所")]:
            with self.subTest(value=value), self.assertRaises(m.UpstreamError):
                self.extract(value)

    def test_incomplete_or_unknown_provenance_is_preserved_without_inventing_sources(self):
        value = dict(complete=False, sources=[])
        self.assertEqual(value, self.extract(value).get("source_provenance"))
        value = self.provenance(kind="unknown")
        value["complete"] = False
        self.assertEqual(value, self.extract(value).get("source_provenance"))

    def test_duplicate_sources_and_upstream_anchors_are_rejected(self):
        value = copy.deepcopy(self.provenance())
        value["sources"] *= 2
        with self.assertRaises(m.UpstreamError):
            self.extract(value)
        evidence = "青禾研究所的《就业样本甲》引用《共同调查》"
        with self.assertRaises(m.UpstreamError):
            self.extract(self.provenance(kind="secondary", basis=evidence,
                                        upstream=["《共同调查》", "《共同调查》"]), evidence)

    def test_reporting_another_producer_does_not_make_the_reporter_primary(self):
        evidence = "青禾研究所报道远山研究院独立调查300家企业，形成《就业样本甲》"
        with self.assertRaises(m.UpstreamError):
            self.extract(self.provenance(basis=evidence), evidence)

    def test_extraction_checks_qualifiers_outside_the_cropped_evidence(self):
        for prefix in ("据说", "并非", "如果"):
            source = m.validate_monitor(request_data(text=prefix + self.evidence))
            row = claim(quote=self.evidence, evidence=self.evidence, source_provenance=self.provenance())
            with self.subTest(prefix=prefix), self.assertRaises(m.UpstreamError):
                m.validate_extraction({"claims": [row]}, source)
