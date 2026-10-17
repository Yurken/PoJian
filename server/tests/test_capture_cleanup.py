import http.client
import json
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as monitor
import capture_cleanup as cleanup


class CleanupContractTests(unittest.TestCase):
    def test_only_exact_ui_suffixes_and_valid_distinct_lines_can_be_deleted(self):
        content = "😀正文2026年 广告 展开\n16.2万"
        valid = {"drop_lines": [1], "trim_suffixes": [{"line": 0, "suffix": " 广告 展开"}]}
        self.assertEqual(valid, cleanup.validate_selection(content, valid))
        for invalid in [None, {}, {"drop_lines": [True], "trim_suffixes": []},
                {"drop_lines": [0, 0], "trim_suffixes": []},
                {"drop_lines": [-1], "trim_suffixes": []},
                {"drop_lines": [1, 0], "trim_suffixes": []},
                {"drop_lines": [], "trim_suffixes": [{"line": 0, "suffix": "正文2026年 广告 展开"}]},
                {"drop_lines": [0], "trim_suffixes": valid["trim_suffixes"]},
                {**valid, "text": "改写正文"}]:
            with self.subTest(invalid=invalid), self.assertRaises(legacy.UpstreamError):
                cleanup.validate_selection(content, invalid)

    def test_request_bounds_and_utf8(self):
        for text in ["", " ", "x" * 16001, "\n".join(["x"] * 257), "\ud800"]:
            with self.subTest(text=text[:20]), self.assertRaises(legacy.ClientError):
                cleanup.validate_request({"version": 1, "text": text})

    def test_fast_provider_reuses_endpoint_key_and_disables_thinking(self):
        config = legacy.Config("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", "key", "token")
        runtime = monitor.RuntimeConfig(config, monitor.Options(), ":memory:")
        provider = monitor.Providers(config, runtime)
        reply = {"choices": [{"message": {"content": '{"drop_lines":[],"trim_suffixes":[]}'}, "finish_reason": "stop"}]}
        with patch.dict(monitor.os.environ, {"EMERGE_CLEANUP_MODEL": ""}), patch.object(provider.diagnostics, "call", return_value=reply) as call:
            cleanup.clean("2026年正文。", provider)
            stage, url, key, payload, timeout = call.call_args.args
        self.assertEqual("cleanup", stage)
        self.assertEqual(config.upstream_url, url)
        self.assertEqual(config.upstream_key, key)
        self.assertEqual("qwen-plus", payload["model"])
        self.assertFalse(payload["enable_thinking"])
        self.assertEqual(1024, payload["max_tokens"])
        self.assertLessEqual(timeout, 2.5)
        runtime.update({"fast_text_model": "custom-fast"})
        with patch.object(provider.diagnostics, "call", return_value=reply) as call:
            cleanup.clean("2026年正文。", provider)
            self.assertEqual("custom-fast", call.call_args.args[3]["model"])


class CleanupHttpTests(unittest.TestCase):
    def setUp(self):
        config = legacy.Config("https://example.org/v1/chat/completions", "fixture", "key", "token")
        self.provider = Mock()
        self.provider.cleanup_model.return_value = {"drop_lines": [1, 2], "trim_suffixes": []}
        self.budget = monitor.DailyBudget(":memory:", 2)
        self.server = monitor.ThreadingHTTPServer(("127.0.0.1", 0),
            monitor.make_handler(config, monitor.Options(), self.budget, self.provider))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.budget.close()

    def call(self, data=None, token="token"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request("POST", "/capture-cleanup", json.dumps(data or {
            "version": 1, "text": "2026年比赛共有20名选手。\n相关搜索\n学习机T6国际版"}),
            {"Content-Type": "application/json", "Authorization": "Bearer " + token})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_cleanup_before_storage_contract_and_shared_budget(self):
        status, result = self.call()
        self.assertEqual(200, status)
        self.assertEqual([1, 2], result["drop_lines"])
        self.assertEqual(64, len(result["input_hash"]))
        self.assertEqual(1, self.budget.used())
        self.assertEqual(200, self.call()[0])
        self.assertEqual(429, self.call()[0])

    def test_auth_and_bad_request_do_not_invoke_model(self):
        self.assertEqual(401, self.call(token="wrong")[0])
        self.assertEqual(400, self.call({"version": 1, "text": "x" * 16001})[0])
        self.provider.cleanup_model.assert_not_called()
        self.assertEqual(0, self.budget.used())

    def test_invalid_model_result_is_not_returned_as_clean_content(self):
        self.provider.cleanup_model.return_value = {"drop_lines": [999], "trim_suffixes": []}
        status, result = self.call()
        self.assertEqual(502, status)
        self.assertNotIn("2026", json.dumps(result))
