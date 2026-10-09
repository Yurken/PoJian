import copy
import http.client
import json
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from server import emerge_server as gateway


def sources():
    return {"version": 1, "segments": [{"id": 2, "text": "岗位减少百分之二十五，需要继续核查。", "timestamp": 2000,
        "startMillis": 0, "endMillis": 2000}], "history": [{"id": 1, "text": "过去岗位减少百分之二十。",
        "timestamp": 1000, "startMillis": 0, "endMillis": 1000}]}


def insight():
    return {"summary": "数字有所变化，需要核查来源。", "claims": [{"claim": "岗位减少",
        "evidence": "岗位减少百分之二十五", "segmentId": 2, "relation": "相同议题，数字变化", "novelty": "条件变化",
        "historySegmentId": 1, "historyQuote": "岗位减少百分之二十"}]}


class ValidatorTests(unittest.TestCase):
    def test_valid_output_preserves_quote_and_history(self):
        result = gateway.validate_output(insight(), sources(), "test-model", {"prompt_tokens": 10, "completion_tokens": 20, "private": "hidden"})
        self.assertEqual(insight()["claims"], result["claims"])
        self.assertEqual({"prompt_tokens": 10, "completion_tokens": 20}, result["usage"])

    def test_no_claim_and_no_history_are_valid(self):
        self.assertEqual([], gateway.validate_output({"summary": "没有明确观点", "claims": []}, sources(), "test")["claims"])
        value = insight()
        value["claims"][0].update(historySegmentId=None, historyQuote=None)
        self.assertIsNone(gateway.validate_output(value, sources(), "test")["claims"][0]["historySegmentId"])

    def test_invented_ids_and_quotes_are_rejected(self):
        variants = [("segmentId", 99), ("segmentId", True), ("evidence", "无中生有的证据"),
                    ("historySegmentId", 99), ("historyQuote", "杜撰的历史原句"),
                    ("historySegmentId", None), ("claim", ""), ("relation", "x" * 121)]
        for key, bad in variants:
            with self.subTest(key=key, bad=bad):
                value = insight()
                value["claims"][0][key] = bad
                with self.assertRaises(gateway.UpstreamError):
                    gateway.validate_output(value, sources(), "test")

    def test_input_ids_limits_and_ranges_are_validated(self):
        variants = []
        duplicate = sources(); duplicate["history"][0]["id"] = 2; variants.append(duplicate)
        backwards = sources(); backwards["segments"][0]["endMillis"] = -1; variants.append(backwards)
        boolean = sources(); boolean["segments"][0]["id"] = True; variants.append(boolean)
        excessive = sources(); excessive["segments"][0]["text"] = "字" * 20_001; variants.append(excessive)
        surrogate = sources(); surrogate["segments"][0]["text"] = "\ud800"; variants.append(surrogate)
        wrong_version = sources(); wrong_version["version"] = True; variants.append(wrong_version)
        for value in variants:
            with self.subTest(value_type=type(value)):
                with self.assertRaises(gateway.ClientError):
                    gateway.validate_input(value)
        self.assertEqual(sources(), gateway.validate_input(sources()))

    def test_json_rejects_duplicate_keys_and_nan(self):
        for raw in (b'{"id":1,"id":2}', b'{"id":NaN}'):
            with self.assertRaises(ValueError):
                gateway.decode_json(raw)

    def test_configuration_requires_keys_and_https(self):
        env = {"EMERGE_UPSTREAM_API_KEY": "upstream-secret", "EMERGE_SERVICE_TOKEN": "client-secret"}
        with patch.dict("os.environ", env, clear=True):
            self.assertEqual(gateway.DEFAULT_UPSTREAM, gateway.Config.from_env().upstream_url)
        for extra in ({"EMERGE_SERVICE_TOKEN": ""}, {"EMERGE_UPSTREAM_URL": "http://public.example/v1/chat/completions"},
                      {"EMERGE_UPSTREAM_URL": "https://user:secret@example.com/v1"}, {"EMERGE_UPSTREAM_TIMEOUT": "60"}):
            with patch.dict("os.environ", env | extra, clear=True):
                with self.assertRaises(ValueError):
                    gateway.Config.from_env()

    def test_unconfigured_mode_must_be_explicit(self):
        with patch.dict("os.environ", {"EMERGE_SERVICE_TOKEN": "client-token", "EMERGE_ALLOW_UNCONFIGURED": "1"}, clear=True):
            with self.assertRaises(ValueError):
                gateway.Config.from_env()
            config = gateway.Config.from_env(require_upstream=False)
            self.assertFalse(config.model_configured)
            self.assertEqual("", config.upstream_key)

    def test_unconfigured_mode_still_requires_token_and_valid_configuration(self):
        env = {"EMERGE_SERVICE_TOKEN": "client-token"}
        for extra in ({"EMERGE_SERVICE_TOKEN": ""}, {"EMERGE_UPSTREAM_MODEL": ""},
                      {"EMERGE_UPSTREAM_URL": "http://public.example/v1/chat/completions"}):
            with self.subTest(extra=extra), patch.dict("os.environ", env | extra, clear=True):
                with self.assertRaises(ValueError):
                    gateway.Config.from_env(require_upstream=False)


class FakeUpstream(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.requests.append((dict(self.headers), json.loads(raw)))
        if self.server.delay:
            time.sleep(self.server.delay)
        self.send_response(self.server.response_status)
        if self.server.response_status == 302:
            self.send_header("Location", "http://127.0.0.1:%d/redirect-target" % self.server.server_port)
        raw_response = self.server.response_body
        self.send_header("Content-Length", str(len(raw_response)))
        self.end_headers()
        try:
            self.wfile.write(raw_response)
        except (BrokenPipeError, ConnectionResetError):
            pass


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), FakeUpstream)
        cls.upstream.daemon_threads = True
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()
        cls.config = gateway.Config("http://127.0.0.1:%d/chat/completions" % cls.upstream.server_port,
                                    "fake-qwen", "upstream-key-never-on-phone", "phone-service-token", timeout=0.15)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), gateway.make_handler(cls.config))
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.server, cls.upstream):
            server.shutdown(); server.server_close()
        cls.thread.join(); cls.upstream_thread.join()

    def setUp(self):
        self.upstream.requests = []
        self.upstream.delay = 0
        self.upstream.response_status = 200
        self.set_upstream(insight())

    def set_upstream(self, value):
        self.upstream.response_body = json.dumps({"choices": [{"message": {"content": json.dumps(value, ensure_ascii=False)}}],
            "usage": {"prompt_tokens": 23, "completion_tokens": 45}}).encode("utf-8")

    def call(self, path="/analyze", token="phone-service-token", raw=None, method="POST", headers=None):
        body = json.dumps(sources()).encode("utf-8") if raw is None else raw
        values = {"Content-Type": "application/json"}
        if token is not None:
            values["Authorization"] = "Bearer " + token
        values.update(headers or {})
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body=body if method == "POST" else None, headers=values)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_authenticated_roundtrip_keeps_keys_on_service(self):
        status, body = self.call()
        self.assertEqual(200, status)
        self.assertEqual("fake-qwen", body["model"])
        self.assertEqual(insight()["claims"], body["claims"])
        headers, request = self.upstream.requests[0]
        self.assertEqual("Bearer upstream-key-never-on-phone", headers["Authorization"])
        self.assertEqual({"type": "json_object"}, request["response_format"])
        self.assertNotIn("upstream-key", json.dumps(body))
        self.assertEqual(sources(), json.loads(request["messages"][1]["content"]))

    def test_health_does_not_call_upstream(self):
        self.assertEqual((200, {"status": "ok", "version": 1, "model_configured": True}), self.call("/health", method="GET", token=""))
        self.assertEqual([], self.upstream.requests)

    def test_status_requires_service_token(self):
        self.assertEqual(401, self.call("/status", token=None, method="GET")[0])
        self.assertEqual(401, self.call("/status", token="wrong", method="GET")[0])
        status, body = self.call("/status", method="GET")
        self.assertEqual(200, status)
        self.assertFalse(body["service"]["monitor"])

    def test_bad_auth_rejects_before_upstream(self):
        self.assertEqual(401, self.call(token="wrong")[0])
        self.assertEqual([], self.upstream.requests)

    def test_input_errors_do_not_call_upstream(self):
        self.assertEqual(400, self.call(raw=b'{bad-json}')[0])
        self.assertEqual(400, self.call(raw=b'{"version":1,"segments":[]}')[0])
        self.assertEqual(415, self.call(headers={"Content-Type": "text/plain"})[0])
        self.assertEqual(413, self.call(raw=b"", headers={"Content-Length": str(gateway.MAX_REQUEST_BYTES + 1)})[0])
        self.assertEqual([], self.upstream.requests)

    def test_fabricated_upstream_quote_is_rejected(self):
        value = insight(); value["claims"][0]["evidence"] = "服务凭空添加的证据"
        self.set_upstream(value)
        self.assertEqual(502, self.call()[0])

    def test_upstream_error_body_is_not_reflected(self):
        self.upstream.response_status = 401
        self.upstream.response_body = b"private transcript and upstream-key-never-on-phone"
        status, body = self.call()
        self.assertEqual(502, status)
        self.assertNotIn("private", json.dumps(body))
        self.assertNotIn("upstream-key", json.dumps(body))

    def test_redirects_are_not_followed(self):
        self.upstream.response_status = 302
        self.assertEqual(502, self.call()[0])
        self.assertEqual(1, len(self.upstream.requests))

    def test_timeout_is_reported(self):
        self.upstream.delay = 0.3
        self.assertEqual(504, self.call()[0])
        time.sleep(0.2)  # Let the isolated fake request finish before the next test.

    def test_large_upstream_and_invalid_output_are_rejected(self):
        self.upstream.response_body = b"x" * (gateway.MAX_UPSTREAM_BYTES + 1)
        self.assertEqual(502, self.call()[0])
        self.set_upstream({"summary": "x" * 1501, "claims": []})
        self.assertEqual(502, self.call()[0])


if __name__ == "__main__":
    unittest.main()
