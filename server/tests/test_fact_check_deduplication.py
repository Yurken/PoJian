"""Synthetic duplicate/revision traces. No live network or model calls."""
from concurrent.futures import ThreadPoolExecutor
import base64
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import monitor_server as m
from fact_check_cache import FactCheckCache
from test_monitor_server import FakeProviders, request_data, claim, BODY


class FactDeduplicationTests(unittest.TestCase):
    def source(self, **changes):
        return m.validate_monitor(request_data(**{"content_scope": "a" * 64, **changes}))

    def test_same_work_text_audio_changed_frames_and_appended_text_reuse_one_check(self):
        trace = [self.source(id="automatic"), self.source(id="manual"), self.source(id="audio", kind="audio"),
                 self.source(id="frame:1", kind="frame", image=base64.b64encode(b"\xff\xd8\xffone\xff\xd9").decode()),
                 self.source(id="frame:2", kind="frame", image=base64.b64encode(b"\xff\xd8\xfftwo\xff\xd9").decode()),
                 self.source(id="append", text=BODY + "补充作者介绍。")]
        before, after, cache = FakeProviders(), FakeProviders(), FactCheckCache()
        for source in trace:
            m.analyze_monitor(source, before)
            result = m.analyze_monitor(source, after, cache)
            self.assertEqual(source["id"], result["id"])
            self.assertEqual("contradicted", result["claims"][0]["verdict"])
        self.assertEqual((6, 1), (before.search_calls, after.search_calls))
        self.assertEqual((12, 7), (before.model_calls, after.model_calls))  # Extraction still sees new samples.
        self.assertTrue(result["coverage"]["check_reused"])
        self.assertFalse(result["coverage"]["search_attempted"])

    def test_different_works_conditions_and_capture_day_are_independent(self):
        provider, cache = FakeProviders(), FactCheckCache()
        m.analyze_monitor(self.source(), provider, cache)
        m.analyze_monitor(self.source(content_scope="b" * 64), provider, cache)
        m.analyze_monitor(self.source(captured_at=request_data()["captured_at"] + 86_400_000), provider, cache)
        provider.claims = [claim(conditions="")]
        m.analyze_monitor(self.source(), provider, cache)
        self.assertEqual(4, provider.search_calls)

    def test_cached_old_fact_leaves_budget_for_a_new_fact(self):
        provider, cache = FakeProviders(), FactCheckCache()
        m.analyze_monitor(self.source(), provider, cache)
        provider.claims = [claim(), claim(quote="测试采用相同的条件", evidence="", conditions="")]
        result = m.analyze_monitor(self.source(id="revision"), provider, cache)
        self.assertEqual(2, provider.search_calls)
        self.assertEqual(["contradicted", "contradicted"], [c["verdict"] for c in result["claims"]])
        self.assertTrue(result["coverage"]["check_reused"])

    def test_new_fact_before_cached_fact_does_not_downgrade_completed_result(self):
        provider, cache = FakeProviders(), FactCheckCache()
        m.analyze_monitor(self.source(), provider, cache)
        provider.claims = [claim(quote="测试采用相同的条件", evidence="", conditions=""), claim()]
        result = m.analyze_monitor(self.source(id="reordered"), provider, cache)
        self.assertEqual(2, provider.search_calls)
        self.assertEqual(["contradicted", "contradicted"], [c["verdict"] for c in result["claims"]])
        self.assertTrue(result["coverage"]["check_reused"])

    def test_failure_is_shared_but_not_saved_as_a_success_and_can_retry(self):
        provider, cache = FakeProviders(), FactCheckCache()
        with patch.object(provider, "search", side_effect=m.UpstreamError("temporary", 503)):
            failed = m.analyze_monitor(self.source(), provider, cache)
        self.assertEqual("not_checked", failed["claims"][0]["verdict"])
        self.assertTrue(failed["claims"][0]["check_error"]["retryable"])
        completed = m.analyze_monitor(self.source(), provider, cache)
        self.assertEqual("contradicted", completed["claims"][0]["verdict"])
        self.assertEqual(1, provider.search_calls)

    def test_concurrent_identical_checks_share_one_search_and_evidence_model(self):
        provider, cache = FakeProviders(), FactCheckCache()
        entered, release, second_extracted = threading.Event(), threading.Event(), threading.Event()
        search, model = provider.search, provider.model
        count = [0]
        def blocked_search(*args):
            entered.set()
            self.assertTrue(release.wait(5))
            return search(*args)
        def observed_model(*args):
            if args[0] == m.EXTRACT_PROMPT:
                count[0] += 1
                if count[0] == 2:
                    second_extracted.set()
            return model(*args)
        with patch.object(provider, "search", side_effect=blocked_search), \
                patch.object(provider, "model", side_effect=observed_model), ThreadPoolExecutor(2) as pool:
            first = pool.submit(m.analyze_monitor, self.source(id="one"), provider, cache)
            self.assertTrue(entered.wait(5))
            second = pool.submit(m.analyze_monitor, self.source(id="two"), provider, cache)
            try:
                self.assertTrue(second_extracted.wait(5))
            finally:
                release.set()
            self.assertEqual("one", first.result(5)["id"])
            self.assertEqual("two", second.result(5)["id"])
        self.assertEqual(1, provider.search_calls)
        self.assertEqual(3, provider.model_calls)

    def test_expiry_eviction_and_result_isolation(self):
        cache = FactCheckCache(capacity=1, ttl=10)
        with patch("fact_check_cache.time.monotonic", return_value=100):
            result, hit = cache.run("a", 1000, lambda: {"sources": []})
            self.assertFalse(hit)
            result["sources"].append("external mutation")
            self.assertEqual(({"sources": []}, True), cache.run("a", 1000, lambda: self.fail()))
            cache.run("b", 1000, lambda: {})
            self.assertFalse(cache.run("a", 1000, lambda: {})[1])
        with patch("fact_check_cache.time.monotonic", return_value=110):
            self.assertFalse(cache.run("a", 1000, lambda: {})[1])

    def test_absent_scope_keeps_old_protocol_and_invalid_scope_is_rejected(self):
        provider, cache = FakeProviders(), FactCheckCache()
        for _ in range(2):
            m.analyze_monitor(m.validate_monitor(request_data()), provider, cache)
        self.assertEqual(2, provider.search_calls)
        for invalid in (None, 1, "raw private title", "g" * 64, "a" * 65):
            with self.subTest(scope=invalid), self.assertRaises(m.ClientError):
                self.source(content_scope=invalid)

    def test_analysis_only_does_not_consume_or_invent_a_fact_result(self):
        provider, cache = FakeProviders(), FactCheckCache()
        result = m.analyze_monitor(self.source(check_facts=False), provider, cache)
        self.assertEqual("not_checked", result["claims"][0]["verdict"])
        self.assertEqual(0, provider.search_calls)
        m.analyze_monitor(self.source(), provider, cache)
        self.assertEqual(1, provider.search_calls)


if __name__ == "__main__":
    unittest.main()
