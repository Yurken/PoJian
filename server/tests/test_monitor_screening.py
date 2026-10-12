"""Fact-screening contract and downstream payload tests; model judgments are fixtures."""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as m
from test_monitor_server import FakeProviders, claim, request_data, SOURCE

FACT = "2022年冬季奥林匹克运动会在北京举行"
OPINION = "我觉得这次活动非常精彩，个人很喜欢这样的氛围"
MIXED = OPINION + "，" + FACT + "，希望以后还能有更多活动。"


def candidate(**updates):
    return claim(quote=MIXED, check_quote=FACT, evidence="", conditions="", **updates)


class ScreeningTests(unittest.TestCase):
    def run_pipeline(self, row, **updates):
        provider = FakeProviders(claims=[row])
        with patch.object(provider, "model", wraps=provider.model) as model, \
                patch.object(provider, "search", wraps=provider.search) as search:
            result = m.analyze_monitor(m.validate_monitor(request_data(**{"text": MIXED, **updates})), provider)
        return result, provider, model, search

    def test_mixed_paragraph_sends_only_selected_fact_to_search(self):
        result, provider, model, search = self.run_pipeline(candidate())
        self.assertEqual(search.call_args.args[0], FACT)
        self.assertNotIn(OPINION, search.call_args.args[0])
        self.assertEqual(provider.model_calls, 2)  # Initial fast pass + evidence alignment, no extra pass.
        self.assertEqual(model.call_args_list[0].args[1]["text"], MIXED)
        self.assertTrue(model.call_args_list[0].args[1]["check_facts"])
        alignment = model.call_args_list[1].args[1]
        self.assertEqual(alignment["claim"], FACT)
        self.assertEqual(alignment["original_quote"], MIXED)
        self.assertEqual(result["claims"][0]["quote"], MIXED)
        self.assertEqual(result["claims"][0]["check_quote"], FACT)

    def test_pure_opinion_never_calls_search_or_evidence_model(self):
        row = claim(quote=OPINION, check_quote="", factual=False, evidence="", conditions="")
        result, provider, _, search = self.run_pipeline(row)
        self.assertEqual(provider.model_calls, 1)
        search.assert_not_called()
        self.assertEqual(result["claims"][0]["verdict"], "opinion")
        self.assertFalse(result["coverage"]["search_attempted"])

    def test_fact_disguised_as_personal_view_still_uses_selected_fact(self):
        paragraph = "我觉得该产品2025年销量增长了30%，真让人意外。"
        fact = "该产品2025年销量增长了30%"
        row = claim(quote=paragraph, check_quote=fact, evidence="", conditions="")
        _, _, _, search = self.run_pipeline(row, text=paragraph)
        self.assertEqual(search.call_args.args[0], fact)

    def test_missing_candidate_never_falls_back_to_whole_paragraph(self):
        row = candidate()
        del row["check_quote"]
        result, provider, _, search = self.run_pipeline(row)
        search.assert_not_called()
        self.assertEqual(provider.model_calls, 1)
        self.assertEqual(result["claims"][0]["verdict"], "not_checked")
        self.assertEqual(result["claims"][0]["quote"], MIXED)

    def test_empty_candidate_does_not_consume_fact_budget_before_valid_fact(self):
        row = candidate()
        row["check_quote"] = ""
        provider = FakeProviders(claims=[row, candidate()])
        result = m.analyze_monitor(m.validate_monitor(request_data(text=MIXED)), provider)
        self.assertEqual(provider.search_calls, 1)
        self.assertEqual([row["verdict"] for row in result["claims"]], ["not_checked", "contradicted"])

    def test_uncertain_attribution_is_not_searched(self):
        result, provider, _, search = self.run_pipeline(candidate(uncertain=True))
        search.assert_not_called()
        self.assertEqual(provider.model_calls, 1)
        self.assertEqual(result["claims"][0]["verdict"], "insufficient")
        self.assertEqual(result["claims"][0]["sources"], [])

    def test_no_fact_mode_still_keeps_viewpoint_structure_and_skips_search(self):
        result, provider, model, search = self.run_pipeline(candidate(), check_facts=False)
        self.assertFalse(model.call_args.args[1]["check_facts"])
        self.assertEqual(provider.model_calls, 1)
        search.assert_not_called()
        self.assertEqual(result["claims"][0]["stance"], "结果提升")

    def test_nonfactual_flag_prevents_search_even_with_candidate_text(self):
        result, _, _, search = self.run_pipeline(candidate(factual=False))
        search.assert_not_called()
        self.assertEqual(result["claims"][0]["check_quote"], "")

    def test_negation_numbers_units_and_conditions_reach_both_downstream_stages(self):
        fact = "2025年在相同测试条件下，该产品续航没有提升30%"
        paragraph = "我有些失望，" + fact + "，个人认为不值得买。"
        row = claim(quote=paragraph, check_quote=fact, conditions="相同测试条件", evidence="")
        _, _, model, search = self.run_pipeline(row, text=paragraph)
        self.assertEqual(search.call_args.args[0], fact)
        self.assertEqual(model.call_args.args[1]["claim"], fact)
        self.assertEqual(model.call_args.args[1]["conditions"], "相同测试条件")
        self.assertEqual(model.call_args.args[1]["original_quote"], paragraph)

    def test_separate_context_conditions_are_not_truncated_off_search_query(self):
        fact, conditions = "事" * 240, "条" * 200
        row = claim(quote=fact, check_quote=fact, conditions=conditions, evidence="")
        _, _, _, search = self.run_pipeline(row, text=conditions + fact)
        self.assertEqual(search.call_args.args[0], fact + " " + conditions)
        self.assertLessEqual(len(search.call_args.args[0]), 500)

    def test_rewritten_invented_or_cross_claim_candidates_are_rejected_before_search(self):
        for candidate_text in ("2024年冬奥会在北京举行", "2022年北京冬奥会", OPINION, " ", 42, [FACT]):
            with self.subTest(candidate_text=candidate_text):
                row = claim(quote=FACT, check_quote=candidate_text, evidence="", conditions="")
                provider = FakeProviders(claims=[row])
                with self.assertRaises(m.UpstreamError):
                    m.analyze_monitor(m.validate_monitor(request_data(text=MIXED)), provider)
                self.assertEqual(provider.search_calls, 0)

    def test_candidate_length_limit_does_not_remove_necessary_conditions(self):
        row = claim(quote="字" * 241, check_quote="字" * 241, evidence="")
        provider = FakeProviders(claims=[row])
        with self.assertRaises(m.UpstreamError):
            m.analyze_monitor(m.validate_monitor(request_data(text="字" * 241)), provider)
        self.assertEqual(provider.search_calls, 0)

    def test_evidence_model_can_reject_scope_changed_by_shortening(self):
        paragraph = "目前没有证据表明该产品能节省30%的电量"
        row = claim(quote=paragraph, check_quote="该产品能节省30%的电量", evidence="", conditions="")
        provider = FakeProviders(claims=[row], verdict={"verdict": "insufficient", "explanation": "短句遗漏原文否定。", "citations": []})
        result = m.analyze_monitor(m.validate_monitor(request_data(text=paragraph)), provider)
        self.assertEqual(result["claims"][0]["verdict"], "insufficient")

    def test_real_provider_uses_existing_flash_fast_pass_without_extra_model_call(self):
        config = legacy.Config("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                               "qwen3.8-flash", "fixture-key", "fixture-token")
        provider = m.Providers(config, m.RuntimeConfig(config, m.Options(search_key="fixture-search-key"), ":memory:"))
        model_reply = {"choices": [{"message": {"content": json.dumps({"claims": [candidate()]})}}]}
        alignment_reply = {"choices": [{"message": {"content": json.dumps(FakeProviders().verdict)}}]}
        with patch.object(m, "post_json", side_effect=[model_reply, alignment_reply]) as post, \
                patch.object(provider, "search", return_value=[SOURCE]) as search:
            result = m.analyze_monitor(m.validate_monitor(request_data(text=MIXED)), provider)
        self.assertEqual(post.call_count, 2)
        first = post.call_args_list[0].args[2]
        self.assertEqual(first["model"], "qwen3.8-flash")
        self.assertFalse(first["enable_thinking"])
        self.assertEqual(json.loads(first["messages"][1]["content"])["text"], MIXED)
        self.assertEqual(search.call_args.args[0], FACT)
        self.assertEqual(result["claims"][0]["verdict"], "contradicted")
