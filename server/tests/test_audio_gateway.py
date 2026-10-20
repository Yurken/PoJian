"""Legacy APK audio requests must use the monitor gateway's live configuration and budget."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as monitor


class AudioGatewayTests(unittest.TestCase):
    payload = {"version": 1, "segments": [
        {"id": 1, "text": "招聘岗位正在减少", "timestamp": 1, "startMillis": 0, "endMillis": 1000}
    ], "history": []}
    result = {"summary": "fixture", "claims": [], "model": "fixture"}

    def setUp(self):
        self.config = legacy.Config("https://example.org/v1/chat/completions", "initial-model",
                                    "initial-key", "unchanged-client-token", concurrency=1)
        self.options = monitor.Options(admin_password="independent-admin-password", daily_limit=2)
        self.runtime = monitor.RuntimeConfig(self.config, self.options, ":memory:")
        self.budget = monitor.DailyBudget(":memory:", 2)
        self.server = monitor.ThreadingHTTPServer(("127.0.0.1", 0),
            monitor.make_handler(self.config, self.options, self.budget, runtime=self.runtime))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.budget.close()

    def call(self, path="/analyze", payload=None, token="unchanged-client-token"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request("POST", path, json.dumps(self.payload if payload is None else payload),
                               {"Content-Type": "application/json", "Authorization": "Bearer " + token})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_admin_update_is_used_by_existing_audio_protocol_and_token(self):
        status, login = self.call("/admin/login", {"user": "admin", "password": self.options.admin_password})
        self.assertEqual(200, status)
        self.assertEqual(200, self.call("/config/update", {
            "text_url": "https://replacement.example/v1", "text_model": "replacement-model",
            "text_key": "replacement-key"
        }, token=login["token"])[0])
        with patch.object(legacy, "request_upstream", return_value=self.result) as upstream:
            self.assertEqual((200, self.result), self.call())
        actual = upstream.call_args.args[0]
        self.assertEqual("https://replacement.example/v1/chat/completions", actual.upstream_url)
        self.assertEqual("replacement-model", actual.upstream_model)
        self.assertEqual("replacement-key", actual.upstream_key)
        self.assertEqual(self.config.service_token, actual.service_token)

    def test_disabling_live_model_prevents_audio_calls_and_charges(self):
        self.runtime.update({"text_key": "-"})
        with patch.object(legacy, "request_upstream", return_value=self.result) as upstream:
            self.assertEqual(503, self.call()[0])
            upstream.assert_not_called()
        self.assertEqual(0, self.budget.used())

    def test_audio_requests_consume_the_shared_daily_budget(self):
        with patch.object(legacy, "request_upstream", return_value=self.result) as upstream:
            self.assertEqual(200, self.call()[0])
            self.assertEqual(200, self.call()[0])
            self.assertEqual(429, self.call()[0])
            self.assertEqual(2, upstream.call_count)
        self.assertEqual(2, self.budget.used())

    def test_invalid_requests_do_not_charge_but_upstream_failure_does(self):
        with patch.object(legacy, "request_upstream", side_effect=legacy.UpstreamError("fixture", 502)) as upstream:
            self.assertEqual(401, self.call(token="wrong")[0])
            self.assertEqual(400, self.call(payload={"version": 1, "segments": [], "history": []})[0])
            upstream.assert_not_called()
            self.assertEqual(0, self.budget.used())
            self.assertEqual(502, self.call()[0])
        self.assertEqual(1, self.budget.used())

    def test_monitor_and_audio_share_the_same_concurrency_slot(self):
        entered, release = threading.Event(), threading.Event()
        def analyzing(*args):
            entered.set()
            if not release.wait(4):
                raise AssertionError("fixture was not released")
            return {"claims": []}
        with patch.object(monitor, "analyze_monitor", side_effect=analyzing), \
                patch.object(legacy, "request_upstream", return_value=self.result) as upstream, \
                ThreadPoolExecutor(max_workers=1) as pool:
            request = pool.submit(self.call, "/monitor", {"version": 1, "id": "fixture", "kind": "text",
                "text": "招聘岗位正在减少", "captured_at": 1, "check_facts": False})
            try:
                self.assertTrue(entered.wait(2))
                self.assertEqual(429, self.call()[0])
                upstream.assert_not_called()
            finally:
                release.set()
            self.assertEqual(200, request.result()[0])
        self.assertEqual(1, self.budget.used())
