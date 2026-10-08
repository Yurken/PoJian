import base64
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as m

QUOTE = "某产品的测试结果提升了百分之三十"
BODY = QUOTE + "。根据报告，测试采用相同的条件。"
SOURCE = {"id": 0, "url": "https://example.org/report", "title": "原始报告", "excerpt": "原始报告显示：测试条件并不相同，结果不能直接比较。"}


def request_data(**updates):
    return {"version": 1, "id": "event:1", "kind": "text", "text": BODY,
            "captured_at": 1_790_000_000_000, "check_facts": True, **updates}


def claim(**updates):
    return {"quote": QUOTE, "topic": "产品评测", "subject": "某产品", "dimension": "测试结果", "stance": "结果提升",
            "evidence": "根据报告，测试采用相同的条件", "conditions": "相同条件", "factual": True, "uncertain": False, **updates}


class FakeProviders:
    def __init__(self, search=True, claims=None, verdict=None, sources=None):
        self.options = m.Options(search_key="test-key" if search else "")
        self.claims = claims if claims is not None else [claim()]
        self.verdict = verdict or {"verdict": "contradicted", "explanation": "测试条件不同，不能直接比较。",
                                   "citations": [{"id": 0, "quote": "测试条件并不相同"}]}
        self.sources = sources if sources is not None else [SOURCE]
        self.model_calls = 0
        self.search_calls = 0
        self.last_image = ""

    def model(self, system, user, deadline, image=""):
        self.model_calls += 1
        self.last_image = image or self.last_image
        if system == m.EXTRACT_PROMPT:
            return {"observed_text": BODY if image else "", "claims": self.claims}
        return self.verdict

    def search(self, query, deadline):
        self.search_calls += 1
        return self.sources


class InputTests(unittest.TestCase):
    def test_valid_text(self): self.assertEqual(m.validate_monitor(request_data())["kind"], "text")
    def test_valid_audio(self): self.assertEqual(m.validate_monitor(request_data(kind="audio"))["kind"], "audio")
    def test_bad_version(self):
        for v in [True, 2, "1", None]:
            with self.subTest(v=v), self.assertRaises(m.ClientError): m.validate_monitor(request_data(version=v))
    def test_no_content(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(text=""))
    def test_long_text(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(text="x" * 6001))
    def test_boolean_timestamp(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(captured_at=True))
    def test_huge_timestamp(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(captured_at=2**63-1))
    def test_check_facts_type(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(check_facts="true"))
    def test_external_image_url_rejected(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(kind="frame", image="https://example.org/a.jpg"))
    def test_missing_image(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(kind="frame"))
    def test_non_image_binary(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(kind="frame", image=base64.b64encode(b"not jpeg").decode()))
    def test_oversized_image(self):
        raw = b"\xff\xd8\xff" + b"x" * (180 * 1024) + b"\xff\xd9"
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(kind="frame", image=base64.b64encode(raw).decode()))
    def test_bounded_jpeg_envelope(self):
        self.assertEqual(m.validate_monitor(request_data(kind="frame", image=base64.b64encode(b"\xff\xd8\xff\xff\xd9").decode()))["kind"], "frame")
    def test_image_not_allowed_on_audio(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(kind="audio", image="abcd"))
    def test_duplicate_json_key(self):
        with self.assertRaises(ValueError): m.decode_json(b'{"id":1,"id":2}')
    def test_nonfinite_json(self):
        with self.assertRaises(ValueError): m.decode_json(b'{"n":NaN}')
    def test_unpaired_surrogate(self):
        with self.assertRaises(m.ClientError): m.validate_monitor(request_data(text="\ud800"))


class EvidenceTests(unittest.TestCase):
    def test_supported_url(self): self.assertTrue(m.evidence_url("https://example.org/report"))
    def test_unsafe_urls(self):
        for url in ["javascript:alert(1)", "http://example.org", "https://user:key@example.org", "https://127.0.0.1/a", "https://192.168.1.1/a", "https://localhost/a", "https://host.local/a", "https://example.org/\n"]:
            with self.subTest(url=url): self.assertFalse(m.evidence_url(url))
    def test_valid_verdict(self): self.assertEqual(m.validate_verdict(FakeProviders().verdict, [SOURCE])["verdict"], "contradicted")
    def test_unknown_reference(self):
        with self.assertRaises(m.UpstreamError): m.validate_verdict({**FakeProviders().verdict, "citations": [{"id": 9, "quote": "测试条件并不相同"}]}, [SOURCE])
    def test_boolean_reference(self):
        with self.assertRaises(m.UpstreamError): m.validate_verdict({**FakeProviders().verdict, "citations": [{"id": False, "quote": "测试条件并不相同"}]}, [SOURCE])
    def test_invented_quote(self):
        with self.assertRaises(m.UpstreamError): m.validate_verdict({**FakeProviders().verdict, "citations": [{"id": 0, "quote": "从来没有这句话"}]}, [SOURCE])
    def test_no_evidence_not_true_or_false(self):
        for verdict in ["supported", "contradicted", "conflicting"]:
            with self.subTest(verdict=verdict), self.assertRaises(m.UpstreamError): m.validate_verdict({"verdict": verdict, "explanation": "结论", "citations": []}, [SOURCE])
    def test_insufficient_can_have_no_sources(self): self.assertEqual(m.validate_verdict({"verdict": "insufficient", "explanation": "证据不足", "citations": []}, [])["sources"], [])
    def test_unknown_verdict(self):
        with self.assertRaises(m.UpstreamError): m.validate_verdict({"verdict": "true", "explanation": "结论", "citations": []}, [])
    def test_fake_observed_text_cannot_anchor_text_quote(self):
        with self.assertRaises(m.UpstreamError): m.validate_extraction({"observed_text": "虚构句子", "claims": [claim(quote="虚构句子")]}, m.validate_monitor(request_data()))
    def test_non_verbatim_input_evidence(self):
        with self.assertRaises(m.UpstreamError): m.validate_extraction({"claims": [claim(evidence="模型创造的证据")]}, m.validate_monitor(request_data()))
    def test_claim_limit(self):
        with self.assertRaises(m.UpstreamError): m.validate_extraction({"claims": [claim()] * 4}, m.validate_monitor(request_data()))
    def test_attribution_flag_type(self):
        with self.assertRaises(m.UpstreamError): m.validate_extraction({"claims": [claim(uncertain="false")]}, m.validate_monitor(request_data()))
    def test_retrieval_discards_snippet_only_and_duplicate_urls(self):
        provider = m.Providers(legacy.Config("https://example.org", "model", "key", "token"), m.Options(search_key="test"))
        data = {"results": [{"url": "https://example.org/a", "content": "仅摘要"},
                            {"url": "https://example.org/b", "raw_content": "取得的正文", "title": "文档"},
                            {"url": "https://example.org/b", "raw_content": "重复正文"}]}
        with patch.object(m, "post_json", return_value=data):
            rows = provider.search("问题", m.time.monotonic() + 60)
            self.assertEqual(len(rows), 1); self.assertEqual(rows[0]["excerpt"], "取得的正文")


class PipelineTests(unittest.TestCase):
    def run_pipeline(self, provider=None, **updates): return m.analyze_monitor(m.validate_monitor(request_data(**updates)), provider or FakeProviders())
    def test_complete_pipeline(self):
        provider = FakeProviders(); result = self.run_pipeline(provider)
        self.assertEqual(result["claims"][0]["verdict"], "contradicted")
        self.assertEqual(provider.model_calls, 2); self.assertEqual(provider.search_calls, 1)
        self.assertEqual(result["claims"][0]["sources"][0]["url"], SOURCE["url"])
    def test_missing_search_never_fabricates_verdict(self):
        provider = FakeProviders(search=False); result = self.run_pipeline(provider)
        self.assertEqual(result["claims"][0]["verdict"], "not_checked"); self.assertEqual(provider.search_calls, 0)
    def test_opinion_not_fact_checked(self):
        provider = FakeProviders(claims=[claim(factual=False)]); result = self.run_pipeline(provider)
        self.assertEqual(result["claims"][0]["verdict"], "opinion"); self.assertEqual(provider.search_calls, 0)
    def test_disabled_fact_mode(self):
        provider = FakeProviders(); self.assertEqual(self.run_pipeline(provider, check_facts=False)["claims"][0]["verdict"], "not_checked")
        self.assertEqual(provider.search_calls, 0)
    def test_uncertain_attribution_abstains(self): self.assertEqual(self.run_pipeline(FakeProviders(claims=[claim(uncertain=True)]))["claims"][0]["verdict"], "insufficient")
    def test_empty_retrieval_abstains(self): self.assertEqual(self.run_pipeline(FakeProviders(sources=[]))["claims"][0]["verdict"], "insufficient")
    def test_fabricated_citation_becomes_unchecked(self):
        provider = FakeProviders(verdict={"verdict": "supported", "explanation": "捏造结论", "citations": [{"id": 99, "quote": "不存在的引用"}]})
        self.assertEqual(self.run_pipeline(provider)["claims"][0]["verdict"], "not_checked")
    def test_one_fact_per_request_and_explicit_coverage(self):
        provider = FakeProviders(claims=[claim(), claim()]); result = self.run_pipeline(provider)
        self.assertEqual([c["verdict"] for c in result["claims"]], ["contradicted", "not_checked"])
        self.assertEqual(provider.search_calls, 1); self.assertEqual(result["coverage"]["fact_check_limit"], 1)
    def test_visual_independent_of_audio(self):
        provider = FakeProviders(); image = base64.b64encode(b"\xff\xd8\xff\xff\xd9").decode()
        result = self.run_pipeline(provider, kind="frame", text="", image=image)
        self.assertEqual(provider.last_image, image); self.assertEqual(result["observed_text"], BODY)
        self.assertTrue(result["coverage"]["visual_transcription_unverified"])
    def test_source_failure_keeps_unchecked(self):
        provider = FakeProviders()
        with patch.object(provider, "search", side_effect=m.UpstreamError("failure")):
            self.assertEqual(self.run_pipeline(provider)["claims"][0]["verdict"], "not_checked")
    def test_no_claims_does_not_mean_true(self): self.assertEqual(self.run_pipeline(FakeProviders(claims=[]))["claims"], [])
    def test_model_without_vision_configuration(self):
        provider = m.Providers(legacy.Config("https://example.org", "model", "key", "token"), m.Options())
        with self.assertRaises(m.UpstreamError): provider.model("system", {}, m.time.monotonic() + 30, "image")


class BudgetTests(unittest.TestCase):
    def test_persists_across_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "budget.db")
            first = m.DailyBudget(path, 1); self.assertTrue(first.reserve("2026-10-08")); first.close()
            second = m.DailyBudget(path, 1); self.assertFalse(second.reserve("2026-10-08")); self.assertTrue(second.reserve("2026-10-09")); second.close()
    def test_atomic_concurrent_reservations(self):
        budget = m.DailyBudget(":memory:", 3); answers = []
        threads = [threading.Thread(target=lambda: answers.append(budget.reserve("2026-10-08"))) for _ in range(20)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual(sum(answers), 3); budget.close()


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config("https://example.org/v1/chat/completions", "fixture-model", "not-a-key", "test-token")
        self.provider = FakeProviders(); self.budget = m.DailyBudget(":memory:", 5)
        self.server = m.ThreadingHTTPServer(("127.0.0.1", 0), m.make_handler(self.config, self.provider.options, self.budget, self.provider))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.budget.close()
    def call(self, path="/monitor", body=None, token="test-token", method="POST", extra=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Content-Type": "application/json", "Authorization": "Bearer " + token, **(extra or {})}
        connection.request(method, path, json.dumps(body if body is not None else request_data()), headers)
        reply = connection.getresponse(); status = reply.status; data = json.loads(reply.read()); connection.close(); return status, data
    def test_live_http_pipeline(self): self.assertEqual(self.call()[1]["claims"][0]["verdict"], "contradicted")
    def test_unauthorized_does_not_call_provider(self): self.assertEqual(self.call(token="wrong")[0], 401); self.assertEqual(self.provider.model_calls, 0)
    def test_invalid_body(self): self.assertEqual(self.call(body={"version": 2})[0], 400)
    def test_wrong_media_type(self): self.assertEqual(self.call(extra={"Content-Type": "text/plain"})[0], 415)
    def test_oversized_body(self): self.assertEqual(self.call(body={"data": "x" * m.MAX_BODY})[0], 413)
    def test_budget_is_enforced(self):
        for _ in range(5): self.assertEqual(self.call()[0], 200)
        self.assertEqual(self.call()[0], 429)
    def test_health_preserved(self): self.assertEqual(self.call(path="/health", method="GET")[0], 200)
    def test_capabilities_are_configuration_not_accuracy(self): self.assertIn("retrieval_configured", self.call(path="/capabilities", method="GET")[1])
    def test_unknown_endpoint(self): self.assertEqual(self.call(path="/nothing")[0], 404)
    def test_legacy_analyze_preserved(self):
        body = {"version": 1, "segments": [{"id": 1, "text": BODY, "timestamp": 100, "startMillis": 0, "endMillis": 100}], "history": []}
        with patch.object(legacy, "request_upstream", return_value={"summary": "legacy contract", "claims": [], "model": "fixture"}):
            status, result = self.call(path="/analyze", body=body)
            self.assertEqual(status, 200); self.assertEqual(result["summary"], "legacy contract")


if __name__ == "__main__": unittest.main()
