"""Deterministic retrieval/deadline regressions; no external provider calls."""
import copy
import io
import json
from pathlib import Path
import socket
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as m

URL = "https://example.org/report"
OTHER = "https://example.org/other"
SEARCH = {"results": [{"url": URL, "title": "Original report", "content": "Search snippet"},
                      {"url": OTHER, "title": "Other source"}]}
EXTRACT = {"results": [{"url": URL, "raw_content": "Retrieved original evidence body."}],
           "failed_results": [{"url": OTHER, "error": "Fetch failed"}]}


class RetrievalTests(unittest.TestCase):
    def setUp(self):
        self.config = legacy.Config("https://example.org/model", "model", "model-secret", "service-secret")
        self.runtime = m.RuntimeConfig(self.config, m.Options(search_key="key-a-secret"), ":memory:")
        self.runtime.update({"search_key": "key-a-secret key-b-secret key-c-secret"})
        self.provider = m.Providers(self.config, self.runtime)

    def search(self):
        return self.provider.search("private test query", m.time.monotonic() + 110)

    def test_search_then_bounded_extract_preserves_partial_success(self):
        with patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            sources = self.search()
        self.assertEqual([call.args[0] for call in post.call_args_list],
                         ["https://api.tavily.com/search", "https://api.tavily.com/extract"])
        search_payload, extract_payload = [call.args[2] for call in post.call_args_list]
        self.assertFalse(search_payload["include_raw_content"])
        self.assertFalse(search_payload["include_answer"])
        self.assertEqual(extract_payload["urls"], [URL, OTHER])
        self.assertEqual(extract_payload["timeout"], 12)
        self.assertLess(extract_payload["timeout"], post.call_args.args[3])
        self.assertEqual(sources, [{"id": 0, "url": URL, "title": "Original report",
                                   "excerpt": "Retrieved original evidence body."}])
        diagnostics = self.provider.diagnostics.snapshot()
        self.assertEqual(diagnostics[1]["failed_results_count"], 1)

    def test_only_discovered_public_urls_are_extracted_and_accepted(self):
        rows = copy.deepcopy(SEARCH)
        rows["results"] += [{"url": URL}, {"url": "https://127.0.0.1/private"},
                            {"url": "https://user:secret@example.org/private"}]
        extraction = copy.deepcopy(EXTRACT)
        extraction["results"] += [{"url": "https://invented.example.org/", "raw_content": "Unrequested body"},
                                  {"url": URL, "raw_content": "Duplicate body"},
                                  {"url": OTHER, "content": "Snippet is not a body"}]
        with patch.object(m, "post_json", side_effect=[rows, extraction]) as post:
            sources = self.search()
        self.assertEqual(post.call_args.args[2]["urls"], [URL, OTHER])
        self.assertEqual(len(sources), 1)
        with self.assertRaises(m.UpstreamError):
            m.validate_verdict({"verdict": "supported", "explanation": "Claim", "citations":
                               [{"id": 0, "quote": "Snippet is not a body"}]}, sources)

    def test_empty_search_does_not_start_extraction(self):
        with patch.object(m, "post_json", return_value={"results": []}) as post:
            self.assertEqual(self.search(), [])
        self.assertEqual(post.call_count, 1)

    def test_extract_snippets_never_become_evidence(self):
        with patch.object(m, "post_json", side_effect=[SEARCH, {"results": SEARCH["results"]}]):
            self.assertEqual(self.search(), [])

    def test_transport_and_provider_failures_do_not_repeat_across_keys(self):
        for code in ("timeout", "connection", "provider_failure", "invalid_json", "request_rejected"):
            with self.subTest(code=code), patch.object(m, "post_json",
                    side_effect=m.MonitorProviderError("Failure", code)) as post:
                with self.assertRaises(m.MonitorProviderError):
                    self.search()
                self.assertEqual(post.call_count, 1)

    def test_extract_timeout_does_not_repeat_search_or_change_keys(self):
        with patch.object(m, "post_json", side_effect=[SEARCH,
                m.MonitorProviderError("Timed out", "timeout", 504)]) as post:
            with self.assertRaises(m.MonitorProviderError):
                self.search()
        self.assertEqual(post.call_count, 2)
        self.assertEqual(self.provider.diagnostics.snapshot()[-1]["stage"], "tavily_extract")

    def test_auth_failure_fails_over_and_skips_key_until_cooldown_expires(self):
        self.runtime.update({"search_key": "key-a-secret key-b-secret"})
        with patch.object(m.time, "monotonic", return_value=100), patch.object(m, "post_json", side_effect=[
                m.MonitorProviderError("Rejected", "authentication", provider_status=401), SEARCH, EXTRACT]) as post:
            self.search()
        self.assertEqual([call.args[1] for call in post.call_args_list],
                         ["key-a-secret", "key-b-secret", "key-b-secret"])
        with patch.object(m.time, "monotonic", return_value=101), \
                patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            self.search()
        self.assertTrue(all(call.args[1] == "key-b-secret" for call in post.call_args_list))
        with patch.object(m.time, "monotonic", return_value=401), \
                patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            self.search()
        self.assertEqual(post.call_args_list[0].args[1], "key-a-secret")

    def test_quota_failure_during_extract_reuses_discovered_urls(self):
        with patch.object(m, "post_json", side_effect=[SEARCH,
                m.MonitorProviderError("Quota exceeded", "rate_limited", provider_status=432), EXTRACT]) as post:
            self.search()
        self.assertEqual([call.args[0].rsplit('/', 1)[1] for call in post.call_args_list],
                         ["search", "extract", "extract"])
        self.assertEqual([call.args[1] for call in post.call_args_list],
                         ["key-a-secret", "key-a-secret", "key-b-secret"])

    def test_duplicate_keys_do_not_add_retries_and_all_cooling_keys_fail_fast(self):
        self.runtime.update({"search_key": "key-a-secret key-a-secret key-b-secret"})
        with patch.object(m, "post_json", side_effect=m.MonitorProviderError("Quota", "rate_limited")) as post:
            for _ in range(2):
                with self.assertRaises(m.MonitorProviderError):
                    self.search()
        self.assertEqual(post.call_count, 2)

    def test_removed_key_cooldown_does_not_poison_new_config(self):
        self.provider._key_cooldowns["key-a-secret"] = (m.time.monotonic() + 300, "authentication")
        self.runtime.update({"search_key": "replacement-secret"})
        with patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            self.search()
        self.assertEqual(post.call_args.args[1], "replacement-secret")
        self.assertNotIn("key-a-secret", self.provider._key_cooldowns)

    def test_exhausted_retrieval_budget_does_not_start_extract(self):
        clock = [100.0]
        def slow_search(*args):
            clock[0] = 139.0
            return SEARCH
        with patch.object(m.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(m, "post_json", side_effect=slow_search) as post:
            with self.assertRaises(m.MonitorProviderError) as caught:
                self.search()
        self.assertEqual(caught.exception.code, "timeout")
        self.assertEqual(post.call_count, 1)

    def test_failure_diagnostics_preserve_upstream_status_but_no_payloads_or_keys(self):
        with patch.object(m, "post_json", side_effect=m.MonitorProviderError(
                "Unsafe upstream private query key-a-secret", "timeout", provider_status=504)):
            with self.assertRaises(m.MonitorProviderError):
                self.search()
        event = self.provider.diagnostics.snapshot()[0]
        self.assertEqual(event["http_status"], 504)
        self.assertEqual(event["code"], "timeout")
        self.assertIsInstance(event["ms"], int)
        serialized = json.dumps(event)
        for secret in ("private", "key-a-secret", "Unsafe", "api.tavily.com"):
            self.assertNotIn(secret, serialized)

    def test_diagnostics_are_bounded_and_snapshot_is_not_mutable_state(self):
        with patch.object(m, "post_json", return_value={}):
            for _ in range(103):
                self.provider.diagnostics.call("tavily_search", "https://example.org", "secret", {}, 15)
        result = self.provider.diagnostics.snapshot()
        self.assertEqual(len(result), 100)
        result[0]["code"] = "modified"
        self.assertEqual(self.provider.diagnostics.snapshot()[0]["code"], "ok")

    def test_evidence_probe_selects_requested_key_and_reports_both_stages(self):
        with patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            result = m.probe_tavily(self.config, self.runtime, {"mode": "evidence", "key_index": 2})
        self.assertTrue(result["ok"])
        self.assertEqual(result["source_count"], 1)
        self.assertEqual([row["stage"] for row in result["attempts"]], ["tavily_search", "tavily_extract"])
        self.assertTrue(all(call.args[1] == "key-c-secret" for call in post.call_args_list))
        self.assertNotIn("key-c-secret", json.dumps(result))
        self.assertNotIn("Retrieved original", json.dumps(result))

    def test_failed_or_empty_probe_is_not_reported_successful(self):
        for reply, code in (({"results": []}, "no_evidence"),
                            (m.MonitorProviderError("Failure", "timeout", 504), "timeout")):
            with self.subTest(code=code), patch.object(m, "post_json", side_effect=[reply]):
                result = m.probe_tavily(self.config, self.runtime, {"mode": "evidence"})
                self.assertFalse(result["ok"])
                self.assertEqual(result["code"], code)
                self.assertIn("ms", result)

    def test_probe_uses_one_config_snapshot_and_never_falls_back_to_model(self):
        with patch.object(self.runtime, "search_keys", side_effect=[["key-a-secret"], []]) as keys, \
                patch.object(m, "post_json", side_effect=[SEARCH, EXTRACT]) as post:
            result = m.probe_tavily(self.config, self.runtime, {"mode": "evidence"})
        self.assertTrue(result["ok"])
        self.assertEqual(keys.call_count, 1)
        self.assertTrue(all(call.args[0].startswith("https://api.tavily.com/") for call in post.call_args_list))

    def test_probe_rejects_invalid_options_before_network_call(self):
        for payload in ({"mode": []}, {"mode": "other"}, {"key_index": True}, {"key_index": -1},
                        {"key_index": 3}, {"query": ""}, {"query": "x" * 501}):
            with self.subTest(payload=payload), patch.object(m, "post_json") as post:
                with self.assertRaises(m.ClientError):
                    m.probe_tavily(self.config, self.runtime, payload)
                post.assert_not_called()


class TransportDeadlineTests(unittest.TestCase):
    def test_body_socket_timeout_uses_remaining_time_after_headers_and_chunks(self):
        sock = Mock()
        response = SimpleNamespace(fp=SimpleNamespace(raw=SimpleNamespace(_sock=sock)),
                                   read1=Mock(return_value=b"data"))
        with patch.object(m.time, "monotonic", side_effect=[103.0, 109.0, 111.0]):
            m.read_provider_chunk(response, 1024, 110)
            m.read_provider_chunk(response, 1024, 110)
            with self.assertRaises(m.MonitorProviderError):
                m.read_provider_chunk(response, 1024, 110)
        self.assertEqual([call.args[0] for call in sock.settimeout.call_args_list], [7.0, 1.0])
        self.assertEqual(response.read1.call_count, 2)

    def test_connection_timeout_wrapped_in_urlerror_is_timeout_not_connection(self):
        opener = Mock()
        opener.open.side_effect = URLError(socket.timeout())
        with patch.object(m, "build_opener", return_value=opener), self.assertRaises(m.MonitorProviderError) as caught:
            m.post_json("https://example.org", "secret", {}, 15)
        self.assertEqual(caught.exception.code, "timeout")

    def test_upstream_timeout_status_is_distinct_from_local_socket_timeout(self):
        for status in (408, 504):
            opener = Mock()
            opener.open.side_effect = HTTPError("https://example.org", status, "upstream", {}, io.BytesIO(b"{}"))
            with patch.object(m, "build_opener", return_value=opener), self.assertRaises(m.MonitorProviderError) as caught:
                m.post_json("https://example.org", "secret", {}, 15)
            self.assertEqual(caught.exception.code, "timeout")
            self.assertEqual(caught.exception.provider_status, status)

    def test_expired_request_does_not_open_connection(self):
        with patch.object(m, "build_opener") as opener, self.assertRaises(m.MonitorProviderError):
            m.post_json("https://example.org", "secret", {}, 0)
        opener.assert_not_called()
