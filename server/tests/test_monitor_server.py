import base64
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as m

QUOTE = "某产品的测试结果提升了百分之三十"
BODY = QUOTE + "。根据报告，测试采用相同的条件。"
SOURCE = {"id": 0, "url": "https://example.org/report", "title": "原始报告", "excerpt": "原始报告显示：测试条件并不相同，结果不能直接比较。"}
IDENTITY_EVIDENCE = "小李与小王的经历记录在《就业调查》和访谈原稿中"
IDENTITY_BODY = QUOTE + "。" + IDENTITY_EVIDENCE + "。发布者小张介绍某产品。"


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

    def search_key(self):
        return self.options.search_key

    def retrieval_available(self):
        return bool(self.options.search_key)


class MonitorTimeoutTests(unittest.TestCase):
    def setUp(self):
        config = legacy.Config("https://example.org/v1/chat/completions", "model", "key", "token", timeout=40)
        runtime = m.RuntimeConfig(config, m.Options(vision_model="vision-model"), ":memory:")
        self.providers = m.Providers(config, runtime)

    def test_text_and_vision_models_use_remaining_two_minute_budget(self):
        reply = {"choices": [{"message": {"content": "{}"}}]}
        for image in ("", "image-fixture"):
            for now, expected in ((1000, 120), (1095, 25)):
                with self.subTest(image=bool(image), elapsed=now - 1000), \
                        patch.object(m.time, "monotonic", return_value=now), \
                        patch.object(m, "post_json", return_value=reply) as post:
                    self.providers.model("system", {}, 1120, image)
                    self.assertEqual(post.call_args.args[3], expected)

    def test_expired_budget_does_not_start_another_model_call(self):
        with patch.object(m.time, "monotonic", return_value=1120), patch.object(m, "post_json") as post:
            with self.assertRaises(m.UpstreamError) as caught:
                self.providers.model("system", {}, 1120)
        self.assertEqual(caught.exception.status, 504)
        post.assert_not_called()

    def test_extraction_search_and_verification_share_one_deadline(self):
        providers = FakeProviders()
        with patch.object(m.time, "monotonic", return_value=1000), \
                patch.object(providers, "model", wraps=providers.model) as model, \
                patch.object(providers, "search", wraps=providers.search) as search:
            m.analyze_monitor(m.validate_monitor(request_data()), providers)
        self.assertEqual(model.call_count, 2)
        self.assertEqual([call.args[2] for call in model.call_args_list], [1120, 1120])
        self.assertEqual(search.call_args.args[1], 1120)


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
        config = legacy.Config("https://example.org", "model", "key", "token")
        provider = m.Providers(config, m.RuntimeConfig(config, m.Options(search_key="test"), ":memory:"))
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
        self.assertEqual(result["claims"][0]["evidence_actors"], [])
        self.assertEqual(result["claims"][0]["evidence_origins"], [])
    def test_evidence_identities_survive_fake_provider_and_verification(self):
        row = claim(evidence=IDENTITY_EVIDENCE, evidence_actors=["小李", "小王"],
                    evidence_origins=["《就业调查》", "访谈原稿"])
        provider = FakeProviders(claims=[row])
        output = self.run_pipeline(provider, text=IDENTITY_BODY)["claims"][0]
        self.assertEqual(output["evidence_actors"], row["evidence_actors"])
        self.assertEqual(output["evidence_origins"], row["evidence_origins"])
        self.assertEqual(output["verdict"], "contradicted")
        self.assertEqual(provider.model_calls, 2)
    def test_one_invalid_identity_rejects_all_claims_before_retrieval(self):
        provider = FakeProviders(claims=[claim(), claim(evidence_actors=["虚构的当事人"])])
        with self.assertRaises(m.UpstreamError): self.run_pipeline(provider)
        self.assertEqual(provider.model_calls, 1)
        self.assertEqual(provider.search_calls, 0)
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
        config = legacy.Config("https://example.org", "model", "key", "token")
        provider = m.Providers(config, m.RuntimeConfig(config, m.Options(), ":memory:"))
        with self.assertRaises(m.UpstreamError): provider.model("system", {}, m.time.monotonic() + 30, "image")

    def test_real_providers_drive_the_pipeline(self):
        # Regression: analyze_monitor must work with real Providers (runtime-backed), not only fakes.
        config = legacy.Config("https://example.org", "model", "key", "token")
        runtime = m.RuntimeConfig(config, m.Options(), ":memory:")
        provider = m.Providers(config, runtime)
        extraction = {"observed_text": "", "claims": [claim()]}
        envelope = {"choices": [{"message": {"content": json.dumps(extraction, ensure_ascii=False)}}]}
        with patch.object(m, "post_json", return_value=envelope):
            result = m.analyze_monitor(m.validate_monitor(request_data()), provider)
        self.assertEqual(len(result["claims"]), 1)
        self.assertEqual(result["claims"][0]["verdict"], "insufficient")
        self.assertIn("未取得可用的网页正文", result["claims"][0]["explanation"])

    def test_real_provider_json_contract_preserves_evidence_identities(self):
        config = legacy.Config("https://example.org", "model", "key", "token")
        runtime = m.RuntimeConfig(config, m.Options(), ":memory:")
        provider = m.Providers(config, runtime)
        row = claim(evidence=IDENTITY_EVIDENCE, evidence_actors=["小李"], evidence_origins=["访谈原稿"])
        extraction = {"observed_text": "", "claims": [row]}
        envelope = {"choices": [{"message": {"content": json.dumps(extraction, ensure_ascii=False)}}]}
        with patch.object(m, "post_json", return_value=envelope) as upstream:
            output = m.analyze_monitor(m.validate_monitor(request_data(text=IDENTITY_BODY, check_facts=False)),
                                       provider)["claims"][0]
        self.assertEqual(output["evidence_actors"], ["小李"])
        self.assertEqual(output["evidence_origins"], ["访谈原稿"])
        self.assertEqual(output["verdict"], "not_checked")
        self.assertEqual(upstream.call_count, 1)


class ExtractionIdentityTests(unittest.TestCase):
    fields = ("evidence_actors", "evidence_origins")

    def extract(self, row=None, body=IDENTITY_BODY):
        return m.validate_extraction({"claims": [row if row is not None else claim(evidence=IDENTITY_EVIDENCE)]},
                                     m.validate_monitor(request_data(text=body)))[1][0]

    def test_legacy_missing_fields_and_unknown_identity_are_empty(self):
        row = self.extract()
        self.assertEqual(row["evidence_actors"], [])
        self.assertEqual(row["evidence_origins"], [])
        explicit = self.extract(claim(evidence="", evidence_actors=[], evidence_origins=[]))
        self.assertEqual(explicit["evidence_actors"], [])
        self.assertEqual(explicit["evidence_origins"], [])

    def test_exact_identity_values_are_preserved_without_trimming_or_deduction(self):
        evidence = " 当事人小李 记录在《就业调查》中"
        row = self.extract(claim(evidence=evidence, evidence_actors=[" 当事人小李 "],
                                 evidence_origins=["《就业调查》"]), QUOTE + "。" + evidence)
        self.assertEqual(row["evidence_actors"], [" 当事人小李 "])
        self.assertEqual(row["evidence_origins"], ["《就业调查》"])
        self.assertNotIn("independent", row)

    def test_each_column_allows_four_items_of_eighty_characters(self):
        values = [letter * 80 for letter in "甲乙丙丁"]
        evidence = "、".join(values)
        row = self.extract(claim(evidence=evidence, evidence_actors=values, evidence_origins=values),
                           QUOTE + "。" + evidence)
        self.assertEqual(row["evidence_actors"], values)
        self.assertEqual(row["evidence_origins"], values)

    def test_each_present_column_requires_a_list(self):
        for field in self.fields:
            for value in [None, "小李", {"name": "小李"}, ("小李",), 1, True]:
                with self.subTest(field=field, kind=type(value).__name__), self.assertRaises(m.UpstreamError):
                    self.extract(claim(evidence=IDENTITY_EVIDENCE, **{field: value}))

    def test_identity_item_type_length_and_nonblank_constraints(self):
        for field in self.fields:
            for value in [None, 1, True, {}, [], "", " \t\n", "甲" * 81]:
                with self.subTest(field=field, kind=type(value).__name__), self.assertRaises(m.UpstreamError):
                    self.extract(claim(evidence=IDENTITY_EVIDENCE, **{field: [value]}))

    def test_duplicate_and_fifth_item_reject_the_response(self):
        for field in self.fields:
            for values in [["小李", "小李"], ["小", "李", "小王", "就业", "调查"]]:
                with self.subTest(field=field, count=len(values)), self.assertRaises(m.UpstreamError):
                    self.extract(claim(evidence=IDENTITY_EVIDENCE, **{field: values}))

    def test_invented_cross_spliced_and_outside_evidence_values_are_rejected(self):
        for field in self.fields:
            for value in ["不存在的人物", "小李小王", "就业访谈", "小张", "某产品"]:
                with self.subTest(field=field, value=value), self.assertRaises(m.UpstreamError):
                    self.extract(claim(evidence=IDENTITY_EVIDENCE, **{field: [value]}))

    def test_nonempty_identities_require_nonempty_evidence(self):
        for field in self.fields:
            with self.subTest(field=field), self.assertRaises(m.UpstreamError):
                self.extract(claim(evidence="", **{field: ["小李"]}))


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


class UnconfiguredStartupTests(unittest.TestCase):
    def test_monitor_starts_without_model_key_only_when_selected(self):
        env = {"EMERGE_SERVICE_TOKEN": "test-token", "EMERGE_ALLOW_UNCONFIGURED": "1"}
        with patch.dict("os.environ", env, clear=True), patch.object(m, "DailyBudget") as budget_factory, \
                patch.object(m, "make_handler") as handler_factory, patch.object(m, "ThreadingHTTPServer") as server_factory, \
                patch("builtins.print"):
            server_factory.return_value.serve_forever.side_effect = KeyboardInterrupt
            m.main()
            config = handler_factory.call_args.args[0]
            self.assertFalse(config.model_configured)
            self.assertEqual("", config.upstream_key)
            server_factory.return_value.server_close.assert_called_once()
            budget_factory.return_value.close.assert_called_once()

    def test_missing_key_without_explicit_switch_still_refuses_startup(self):
        for value in (None, "0", "true"):
            env = {"EMERGE_SERVICE_TOKEN": "test-token"}
            if value is not None:
                env["EMERGE_ALLOW_UNCONFIGURED"] = value
            with self.subTest(value=value), patch.dict("os.environ", env, clear=True), \
                    patch.object(m, "ThreadingHTTPServer") as server_factory:
                with self.assertRaises(SystemExit):
                    m.main()
                server_factory.assert_not_called()


class UnconfiguredHttpTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config(legacy.DEFAULT_UPSTREAM, "qwen-plus", "", "test-token")
        self.provider = FakeProviders()
        self.budget = m.DailyBudget(":memory:", 5)
        self.server = m.ThreadingHTTPServer(("127.0.0.1", 0), m.make_handler(self.config, self.provider.options, self.budget, self.provider))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.budget.close()

    def call(self, path, token=None, method="POST"):
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, json.dumps(request_data()) if method == "POST" else None, headers)
            reply = connection.getresponse()
            return reply.status, json.loads(reply.read())
        finally:
            connection.close()

    def assert_no_processing(self):
        self.assertEqual(self.provider.model_calls, 0)
        self.assertEqual(self.provider.search_calls, 0)
        self.assertEqual(self.budget.db.execute("SELECT COALESCE(SUM(used), 0) FROM daily").fetchone()[0], 0)

    def test_health_and_capabilities_report_unconfigured_model(self):
        self.assertEqual(self.call("/health", method="GET"), (200, {"status": "ok", "version": 1, "model_configured": False}))
        status, body = self.call("/capabilities", method="GET")
        self.assertEqual(status, 200)
        self.assertFalse(body["model_configured"])
        self.assertTrue(body["monitor"])
        self.assert_no_processing()

    def test_unauthenticated_analysis_still_returns_unauthorized(self):
        with patch.object(legacy, "request_upstream") as upstream:
            for path in ("/monitor", "/analyze"):
                for token in (None, "wrong"):
                    with self.subTest(path=path, token=token):
                        self.assertEqual(self.call(path, token=token)[0], 401)
            upstream.assert_not_called()
        self.assert_no_processing()

    def test_authenticated_analysis_returns_unconfigured_without_processing(self):
        with patch.object(legacy, "request_upstream") as upstream:
            for path in ("/monitor", "/analyze"):
                with self.subTest(path=path):
                    self.assertEqual(self.call(path, token="test-token"), (503, {"error": "Model API is not configured"}))
            upstream.assert_not_called()
        self.assert_no_processing()


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
    def test_live_http_pipeline(self):
        status, body = self.call()
        self.assertEqual(status, 200)
        self.assertEqual(body["claims"][0]["verdict"], "contradicted")
        self.assertEqual(body["claims"][0]["evidence_actors"], [])
        self.assertEqual(body["claims"][0]["evidence_origins"], [])
    def test_http_pipeline_preserves_evidence_identity_fields(self):
        self.provider.claims = [claim(evidence=IDENTITY_EVIDENCE, evidence_actors=["小李", "小王"],
                                     evidence_origins=["《就业调查》", "访谈原稿"])]
        status, body = self.call(body=request_data(text=IDENTITY_BODY))
        self.assertEqual(status, 200)
        self.assertEqual(body["claims"][0]["evidence_actors"], ["小李", "小王"])
        self.assertEqual(body["claims"][0]["evidence_origins"], ["《就业调查》", "访谈原稿"])
        self.assertEqual(body["claims"][0]["verdict"], "contradicted")
    def test_http_invalid_identity_rejects_whole_response_without_revealing_text(self):
        for field in ("evidence_actors", "evidence_origins"):
            with self.subTest(field=field):
                self.provider.claims = [claim(), claim(**{field: ["private synthetic identity"]})]
                status, body = self.call()
                self.assertEqual(status, 502)
                self.assertEqual(body, {"error": "Invalid argument evidence identities"})
                self.assertNotIn("claims", body)
        self.assertEqual(self.provider.search_calls, 0)
    def test_unauthorized_does_not_call_provider(self): self.assertEqual(self.call(token="wrong")[0], 401); self.assertEqual(self.provider.model_calls, 0)
    def test_invalid_body(self): self.assertEqual(self.call(body={"version": 2})[0], 400)
    def test_wrong_media_type(self): self.assertEqual(self.call(extra={"Content-Type": "text/plain"})[0], 415)
    def test_oversized_body(self): self.assertEqual(self.call(body={"data": "x" * m.MAX_BODY})[0], 413)
    def test_budget_is_enforced(self):
        for _ in range(5): self.assertEqual(self.call()[0], 200)
        self.assertEqual(self.call()[0], 429)
    def test_health_preserved(self): self.assertEqual(self.call(path="/health", method="GET"), (200, {"status": "ok", "version": 1, "model_configured": True}))
    def test_capabilities_are_configuration_not_accuracy(self):
        body = self.call(path="/capabilities", method="GET")[1]
        self.assertIn("retrieval_configured", body)
        self.assertTrue(body["model_configured"])
    def test_unknown_endpoint(self): self.assertEqual(self.call(path="/nothing")[0], 404)
    def test_legacy_analyze_preserved(self):
        body = {"version": 1, "segments": [{"id": 1, "text": BODY, "timestamp": 100, "startMillis": 0, "endMillis": 100}], "history": []}
        with patch.object(legacy, "request_upstream", return_value={"summary": "legacy contract", "claims": [], "model": "fixture"}):
            status, result = self.call(path="/analyze", body=body)
            self.assertEqual(status, 200); self.assertEqual(result["summary"], "legacy contract")


class StatusPageTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config("https://example.org/v1/chat/completions", "fixture-model", "sk-test-secret-key", "test-token")
        self.provider = FakeProviders(); self.budget = m.DailyBudget(":memory:", 5)
        self.stats = m.admin.Stats()
        self.server = m.ThreadingHTTPServer(("127.0.0.1", 0), m.make_handler(self.config, self.provider.options, self.budget, self.provider, stats=self.stats))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.budget.close()

    def call(self, path, token="test-token", method="POST", body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection.request(method, path, json.dumps(body if body is not None else request_data()) if method == "POST" else None, headers)
        reply = connection.getresponse(); status, data = reply.status, reply.read(); connection.close()
        return status, data

    def status_body(self):
        status, data = self.call("/status", method="GET")
        self.assertEqual(status, 200)
        return json.loads(data)

    def test_admin_page_served_as_html(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request("GET", "/admin")
        reply = connection.getresponse()
        status, data = reply.status, reply.read()
        content_type = reply.getheader("Content-Type")
        connection.close()
        self.assertEqual(status, 200)
        self.assertIn("text/html", content_type)
        text = data.decode("utf-8")
        self.assertIn("服务状态", text)
        self.assertIn('fetch("status"', text)

    def test_status_reports_service_and_settings(self):
        body = self.status_body()
        self.assertTrue(body["service"]["monitor"])
        self.assertTrue(body["service"]["model_configured"])
        self.assertTrue(body["service"]["retrieval_configured"])
        settings = body["settings"]
        self.assertEqual(settings["text"]["model"], "fixture-model")
        self.assertEqual(settings["text"]["timeout"], 120)
        self.assertEqual(settings["service_token"], "已配置")
        self.assertEqual(settings["daily_limit"], self.provider.options.daily_limit)
        self.assertEqual(settings["budget_used_today"], 0)

    def test_status_masks_all_credentials(self):
        raw = self.status_body()
        encoded = m.admin.status_json(raw).decode("utf-8")
        self.assertNotIn("sk-test-secret-key", encoded)
        self.assertNotIn("test-key", encoded)  # retrieval key from FakeProviders options
        self.assertNotIn("test-token", encoded)
        self.assertTrue(raw["settings"]["text"]["api_key"].startswith("****"))
        self.assertTrue(raw["settings"]["retrieval"]["configured"])

    def test_stats_record_requests_without_content(self):
        self.assertEqual(self.call("/monitor", token="wrong")[0], 401)
        self.assertEqual(self.call("/health", method="GET", token=None)[0], 200)
        snapshot = self.stats.snapshot()
        self.assertEqual(snapshot["by_status"]["401"], 1)
        self.assertIn("/monitor", snapshot["endpoints"])
        self.assertEqual(snapshot["endpoints"]["/monitor"]["errors"], 1)
        recent_paths = [row["path"] for row in snapshot["recent"]]
        self.assertIn("/health", recent_paths)

    def test_stats_exclude_request_content(self):
        secret_text = "绝不该出现的正文内容"
        self.call("/monitor", token="wrong", body=request_data(text=secret_text))
        snapshot = self.stats.snapshot()
        self.assertNotIn(secret_text, m.admin.status_json(snapshot).decode("utf-8"))

    def test_budget_usage_reflected(self):
        self.assertEqual(self.call("/monitor")[0], 200)
        self.assertEqual(self.status_body()["settings"]["budget_used_today"], 1)

    def test_status_requires_service_token(self):
        self.assertEqual(self.call("/status", token=None, method="GET")[0], 401)
        self.assertEqual(self.call("/status", token="wrong", method="GET")[0], 401)

    def test_snapshot_returns_full_recent_buffer(self):
        for _ in range(60):
            token = self.stats.begin(); self.stats.end(token, "/health", 200)
        recent = self.stats.snapshot()["recent"]
        self.assertEqual(len(recent), 60)
        self.assertEqual(recent[0]["path"], "/health")


class ConfigApiTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.config = legacy.Config("https://example.org/v1/chat/completions", "fixture-model",
                                    "sk-test-secret-key", "test-token")
        self.options = m.Options(vision_url="https://example.org/vision", vision_model="vision-model",
                                 vision_key="vision-secret", search_key="tavily-secret", daily_limit=7)
        self.runtime_path = os.path.join(self.directory.name, "runtime-config.json")
        self.runtime = m.RuntimeConfig(self.config, self.options, self.runtime_path)
        self.budget = m.DailyBudget(":memory:", self.runtime.daily_limit)
        self.server = m.ThreadingHTTPServer(("127.0.0.1", 0),
                                            m.make_handler(self.config, self.options, self.budget, runtime=self.runtime))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.budget.close(); self.directory.cleanup()

    def call(self, path, body=None, token="test-token", method="POST"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection.request(method, path, json.dumps(body if body is not None else {}) if method == "POST" else None, headers)
        reply = connection.getresponse(); status, data = reply.status, reply.read(); connection.close()
        return status, json.loads(data) if data else {}

    def status(self):
        status, body = self.call("/status", method="GET")
        self.assertEqual(status, 200)
        return body

    def test_status_and_config_endpoints_require_token(self):
        self.assertEqual(self.call("/status", token=None, method="GET")[0], 401)
        self.assertEqual(self.call("/config/update", {"text_model": "x"}, token=None)[0], 401)
        self.assertEqual(self.call("/config/update", {"text_model": "x"}, token="wrong")[0], 401)

    def test_update_applies_immediately_and_persists(self):
        self.assertEqual(self.call("/config/update", {"text_key": "sk-new-secret-9abc", "text_model": "new-model"})[0], 200)
        settings = self.status()["settings"]
        self.assertEqual(settings["text"]["model"], "new-model")
        self.assertEqual(settings["text"]["api_key"], "****9abc")
        self.assertTrue(settings["text"]["configured"])
        self.assertEqual(settings["config_source"], "runtime")
        reloaded = m.RuntimeConfig(self.config, self.options, self.runtime_path)
        self.assertEqual(reloaded.provider_settings(False), ("https://example.org/v1/chat/completions", "sk-new-secret-9abc", "new-model"))

    def test_update_rejects_invalid_url_and_keeps_config(self):
        self.assertEqual(self.call("/config/update", {"text_url": "http://evil.example/x"})[0], 400)
        self.assertEqual(self.call("/config/update", {"daily_limit": 0})[0], 400)
        settings = self.status()["settings"]
        self.assertEqual(settings["text"]["url"], "https://example.org/v1/chat/completions")
        self.assertEqual(settings["daily_limit"], 7)

    def test_empty_fields_keep_existing_values(self):
        self.assertEqual(self.call("/config/update", {"text_key": "", "text_model": ""})[0], 200)
        settings = self.status()["settings"]
        self.assertEqual(settings["text"]["model"], "fixture-model")
        self.assertEqual(settings["text"]["api_key"], "****-key")

    def test_dash_clears_optional_fields(self):
        self.assertEqual(self.call("/config/update",
            {"vision_url": "-", "vision_model": "-", "vision_key": "-", "search_key": "-"})[0], 200)
        settings = self.status()["settings"]
        self.assertFalse(settings["vision"]["configured"])
        self.assertEqual(settings["vision"]["model"], "")
        # Retrieval stays available through the text model's built-in web search.
        self.assertTrue(settings["retrieval"]["configured"])
        self.assertIn("模型联网搜索", settings["retrieval"]["provider"])
        self.assertEqual(self.call("/config/update", {"text_url": "-"})[0], 400)
        self.assertEqual(self.call("/config/update", {"text_model": "-"})[0], 400)

    def test_daily_limit_update_syncs_budget(self):
        self.assertEqual(self.call("/config/update", {"daily_limit": 3})[0], 200)
        for _ in range(3):
            self.assertTrue(self.budget.reserve())
        self.assertFalse(self.budget.reserve())

    def test_vision_and_retrieval_updates(self):
        self.assertEqual(self.call("/config/update", {"vision_model": "vision-2", "search_key": "tavily-2"})[0], 200)
        settings = self.status()["settings"]
        self.assertEqual(settings["vision"]["model"], "vision-2")
        self.assertEqual(settings["vision"]["api_key"], "****cret")
        self.assertEqual(settings["retrieval"]["api_key"], "****ly-2")
        self.assertEqual(settings["retrieval"]["key_count"], 1)
        self.assertTrue(settings["retrieval"]["configured"])

    def test_search_key_pool_parsing_rotation_and_failover(self):
        self.assertEqual(self.call("/config/update", {"search_key": "tvly-a-aaaa, tvly-b-bbbb\ntvly-c-cccc"})[0], 200)
        settings = self.status()["settings"]
        self.assertEqual(settings["retrieval"]["key_count"], 3)
        self.assertNotIn("tvly-a-aaaa", json.dumps(settings))
        reloaded = m.RuntimeConfig(self.config, self.options, self.runtime_path)
        self.assertEqual(reloaded.search_keys(), ["tvly-a-aaaa", "tvly-b-bbbb", "tvly-c-cccc"])

        providers = m.Providers(self.config, reloaded)
        reply = {"results": [{"url": "https://example.org/r", "title": "报告",
                              "raw_content": "原始正文内容"}]}
        with patch.object(m, "post_json", side_effect=[m.UpstreamError("Provider rejected the request"), reply]) as mocked:
            sources = providers.search("问题", m.time.monotonic() + 60)
        self.assertEqual(len(sources), 1)
        self.assertEqual(mocked.call_count, 2)
        used_keys = [call.args[1] for call in mocked.call_args_list]
        self.assertNotEqual(used_keys[0], used_keys[1])
        self.assertTrue(set(used_keys) <= set(reloaded.search_keys()))

    def test_dash_clears_search_key_pool(self):
        self.assertEqual(self.call("/config/update", {"search_key": "tvly-a-aaaa tvly-b-bbbb"})[0], 200)
        self.assertEqual(self.status()["settings"]["retrieval"]["key_count"], 2)
        self.assertEqual(self.call("/config/update", {"search_key": "-"})[0], 200)
        self.assertEqual(self.status()["settings"]["retrieval"]["key_count"], 0)

    def test_profiles_save_apply_delete(self):
        self.assertEqual(self.call("/config/profile/save", {"name": "工作自用"})[0], 200)
        profiles = self.status()["settings"]["profiles"]
        self.assertEqual([row["name"] for row in profiles], ["工作自用"])
        self.assertEqual(profiles[0]["text_model"], "fixture-model")
        self.assertEqual(self.call("/config/update", {"text_model": "other-model"})[0], 200)
        self.assertEqual(self.status()["settings"]["text"]["model"], "other-model")
        self.assertEqual(self.call("/config/profile/apply", {"name": "工作自用"})[0], 200)
        self.assertEqual(self.status()["settings"]["text"]["model"], "fixture-model")
        self.assertEqual(self.call("/config/profile/apply", {"name": "不存在"})[0], 404)
        self.assertEqual(self.call("/config/profile/delete", {"name": "工作自用"})[0], 200)
        self.assertEqual(self.status()["settings"]["profiles"], [])
        self.assertEqual(self.call("/config/profile/delete", {"name": "工作自用"})[0], 404)

    def test_profile_names_are_validated(self):
        self.assertEqual(self.call("/config/profile/save", {"name": ""})[0], 400)
        self.assertEqual(self.call("/config/profile/save", {"name": "很" * 41})[0], 400)
        for index in range(10):
            self.assertEqual(self.call("/config/profile/save", {"name": f"配置{index}"})[0], 200)
        self.assertEqual(self.call("/config/profile/save", {"name": "超出上限"})[0], 400)

    def test_full_keys_never_appear_in_responses(self):
        self.assertEqual(self.call("/config/update", {"text_key": "sk-live-full-secret", "vision_key": "vision-full-secret",
                                                     "search_key": "tavily-full-secret"})[0], 200)
        self.assertEqual(self.call("/config/profile/save", {"name": "敏感"})[0], 200)
        encoded = json.dumps(self.status(), ensure_ascii=False)
        for secret in ("sk-live-full-secret", "vision-full-secret", "tavily-full-secret", "sk-test-secret-key"):
            self.assertNotIn(secret, encoded)
        self.assertIn("****cret", encoded)

    def test_provider_probes(self):
        with patch.object(m, "post_json", return_value={}) as mocked:
            for target in ("text", "vision", "retrieval"):
                status, body = self.call("/config/test", {"target": target})
                self.assertEqual(status, 200)
                self.assertTrue(body["ok"])
                self.assertIsInstance(body["ms"], int)
            self.assertEqual(mocked.call_count, 3)
        with patch.object(m, "post_json", side_effect=m.UpstreamError("Provider rejected the request")):
            status, body = self.call("/config/test", {"target": "text"})
            self.assertEqual(status, 200)
            self.assertEqual(body, {"ok": False, "error": "Provider rejected the request"})
        self.assertEqual(self.call("/config/test", {"target": "other"})[0], 400)


class AdminConsoleTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config("https://example.org/v1/chat/completions", "fixture-model",
                                    "sk-test-secret-key", "test-token")
        self.options = m.Options(admin_user="ops", admin_password="console-password-1")
        self.runtime = m.RuntimeConfig(self.config, self.options, ":memory:")
        self.budget = m.DailyBudget(":memory:", 5)
        self.server = m.ThreadingHTTPServer(("127.0.0.1", 0),
                                            m.make_handler(self.config, self.options, self.budget, runtime=self.runtime))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.budget.close()

    def request(self, path, payload=None, token=None, method="POST"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection.request(method, path, json.dumps(payload if payload is not None else {}) if method == "POST" else None, headers)
        reply = connection.getresponse(); status, data = reply.status, reply.read(); connection.close()
        return status, json.loads(data) if data else {}

    def login(self, user="ops", password="console-password-1"):
        return self.request("/admin/login", {"user": user, "password": password})

    def test_login_issues_session_for_console_endpoints(self):
        status, body = self.login()
        self.assertEqual(status, 200)
        session = body["token"]
        self.assertEqual(self.request("/status", token=session, method="GET")[0], 200)
        self.assertEqual(self.request("/config/profile/save", {"name": "方案A"}, token=session)[0], 200)
        # No Tavily key here: retrieval probe falls back to the text model's web search path.
        with patch.object(m, "post_json", return_value={"search_info": {"search_results": [{"url": "https://example.org/a"}]}}):
            self.assertEqual(self.request("/config/test", {"target": "retrieval"}, token=session)[1]["ok"], True)
        with patch.object(m, "post_json", return_value={}):
            body = self.request("/config/test", {"target": "retrieval"}, token=session)[1]
            self.assertFalse(body["ok"])
            self.assertIn("enable_search", body["error"])

    def test_bad_login_rejected_and_throttled(self):
        self.assertEqual(self.login(password="wrong")[0], 401)
        self.assertEqual(self.login(user="nobody")[0], 401)
        for _ in range(3):
            self.assertEqual(self.login(password="wrong")[0], 401)
        self.assertEqual(self.login(password="wrong")[0], 429)

    def test_logout_revokes_session(self):
        session = self.login()[1]["token"]
        self.assertEqual(self.request("/status", token=session, method="GET")[0], 200)
        self.assertEqual(self.request("/admin/logout", {}, token=session)[0], 200)
        self.assertEqual(self.request("/status", token=session, method="GET")[0], 401)
        self.assertEqual(self.request("/status", token="test-token", method="GET")[0], 200)

    def test_default_password_falls_back_to_service_token(self):
        options = m.Options()
        runtime = m.RuntimeConfig(self.config, options, ":memory:")
        budget = m.DailyBudget(":memory:", 5)
        server = m.ThreadingHTTPServer(("127.0.0.1", 0), m.make_handler(self.config, options, budget, runtime=runtime))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection_port = server.server_port
            def login(user, password):
                connection = http.client.HTTPConnection("127.0.0.1", connection_port, timeout=3)
                connection.request("POST", "/admin/login", json.dumps({"user": user, "password": password}),
                                   {"Content-Type": "application/json"})
                reply = connection.getresponse(); status, data = reply.status, reply.read(); connection.close()
                return status, json.loads(data)
            self.assertEqual(login("admin", "test-token")[0], 200)
            self.assertEqual(login("admin", "wrong")[0], 401)
        finally:
            server.shutdown(); server.server_close(); thread.join(); budget.close()


class ProbeErrorDetailTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        class RejectingHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.dumps({"error": {"message": "Invalid API-key provided", "type": "authentication_error"}}).encode()
                self.send_response(401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), RejectingHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/v1/chat/completions"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()

    def test_detail_flag_includes_status_and_provider_message(self):
        with self.assertRaises(m.UpstreamError) as caught:
            m.post_json(self.url, "key", {}, 5, detail=True)
        message = str(caught.exception)
        self.assertIn("HTTP 401", message)
        self.assertIn("Invalid API-key provided", message)

    def test_default_error_stays_generic(self):
        with self.assertRaises(m.UpstreamError) as caught:
            m.post_json(self.url, "key", {}, 5)
        self.assertEqual(str(caught.exception), "Provider rejected the request")

    def test_base_urls_are_normalized(self):
        self.assertEqual(m.normalize_endpoint("https://x.example/compatible-mode/v1"),
                         "https://x.example/compatible-mode/v1/chat/completions")
        self.assertEqual(m.normalize_endpoint("https://x.example/v1/"),
                         "https://x.example/v1/chat/completions")
        self.assertEqual(m.normalize_endpoint("https://x.example/v1/chat/completions"),
                         "https://x.example/v1/chat/completions")
        self.assertEqual(m.normalize_endpoint("https://x.example/custom/deployments/gpt/chat/completions"),
                         "https://x.example/custom/deployments/gpt/chat/completions")
        self.assertEqual(m.normalize_endpoint(""), "")

    def test_provider_settings_normalize_stored_base_url(self):
        config = legacy.Config("https://x.example/compatible-mode/v1", "model", "key", "token")
        runtime = m.RuntimeConfig(config, m.Options(), ":memory:")
        url, key, model = runtime.provider_settings(False)
        self.assertEqual(url, "https://x.example/compatible-mode/v1/chat/completions")

    def test_probe_endpoint_surfaces_detail(self):
        config = legacy.Config(self.url, "fixture-model", "sk-key", "test-token")
        runtime = m.RuntimeConfig(config, m.Options(), ":memory:")
        result = m.probe_provider("text", runtime)
        self.assertFalse(result["ok"])
        self.assertIn("HTTP 401", result["error"])
        self.assertIn("Invalid API-key provided", result["error"])


class BuiltinSearchTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config("https://example.org/v1/chat/completions", "fixture-model",
                                    "sk-test-secret-key", "test-token")
        self.runtime = m.RuntimeConfig(self.config, m.Options(), ":memory:")
        self.providers = m.Providers(self.config, self.runtime)

    def test_retrieval_available_via_text_model(self):
        self.assertTrue(self.providers.retrieval_available())
        empty = m.RuntimeConfig(legacy.Config("https://example.org", "model", "", "token"), m.Options(), ":memory:")
        self.assertFalse(m.Providers(self.config, empty).retrieval_available())
        settings = self.runtime.masked_settings()
        self.assertTrue(settings["retrieval"]["configured"])
        self.assertIn("模型联网搜索", settings["retrieval"]["provider"])

    def test_builtin_search_fetches_page_bodies(self):
        search_reply = {"search_info": {"search_results": [
            {"url": "https://example.org/report", "title": "原始报告"},
            {"url": "https://example.org/report", "title": "重复链接"},
            {"url": "http://127.0.0.1/internal", "title": "非法地址"},
            {"url": "https://example.org/second", "title": "抓取失败"}]}}
        pages = {"https://example.org/report": SOURCE["excerpt"]}
        with patch.object(m, "post_json", return_value=search_reply) as mocked, \
                patch.object(m, "fetch_page_text", side_effect=lambda url, deadline: pages.get(url, "")):
            sources = self.providers.search("某产品的测试", m.time.monotonic() + 60)
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["excerpt"], SOURCE["excerpt"])
        self.assertEqual(mocked.call_count, 1)
        payload = mocked.call_args[0][2]
        self.assertTrue(payload["enable_search"])

    def test_builtin_search_empty_results_yield_no_sources(self):
        with patch.object(m, "post_json", return_value={}):
            self.assertEqual(self.providers.search("问题", m.time.monotonic() + 60), [])

    def test_builtin_search_full_pipeline(self):
        extraction = {"observed_text": "", "claims": [claim()]}
        replies = [
            {"choices": [{"message": {"content": json.dumps(extraction, ensure_ascii=False)}}]},
            {"search_info": {"search_results": [{"url": "https://example.org/report", "title": "原始报告"}]}},
            {"choices": [{"message": {"content": json.dumps(FakeProviders().verdict, ensure_ascii=False)}}]},
        ]
        with patch.object(m, "post_json", side_effect=replies), \
                patch.object(m, "fetch_page_text", return_value=SOURCE["excerpt"]):
            result = m.analyze_monitor(m.validate_monitor(request_data()), self.providers)
        self.assertEqual(result["claims"][0]["verdict"], "contradicted")
        self.assertEqual(result["claims"][0]["sources"][0]["url"], "https://example.org/report")
        self.assertTrue(result["coverage"]["search_attempted"])

    def test_fetch_page_text_extracts_and_rejects(self):
        class PageHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/html":
                    body = "<html><head><script>var secret='x';</script></head><body><p>正文第一句。</p><p>正文第二句。</p></body></html>".encode()
                    self.send_response(200); self.send_header("Content-Type", "text/html")
                else:
                    body = b"\x89PNG\r\n\x1a\n"
                    self.send_response(200); self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers(); self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), PageHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            with patch.object(m, "evidence_url", return_value=True):
                body = m.fetch_page_text(base + "/html", m.time.monotonic() + 10)
                self.assertIn("正文第一句", body)
                self.assertIn("正文第二句", body)
                self.assertNotIn("secret", body)
                self.assertEqual(m.fetch_page_text(base + "/image", m.time.monotonic() + 10), "")
            self.assertEqual(m.fetch_page_text("http://127.0.0.1/anything", m.time.monotonic() + 10), "")
            self.assertEqual(m.fetch_page_text("not-a-url", m.time.monotonic() + 10), "")
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == "__main__": unittest.main()
