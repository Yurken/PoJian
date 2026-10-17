import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import emerge_server as legacy
import monitor_server as monitor


class FastTextConfigTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = str(Path(self.directory.name) / "runtime.json")
        self.config = legacy.Config("https://example.org/v1/chat/completions", "prior-text", "text-secret-1234", "token")
        self.options = monitor.Options()
        self.env = patch.dict(monitor.os.environ, {"EMERGE_CLEANUP_MODEL": ""})
        self.env.start()
        self.addCleanup(self.env.stop)

    def load(self):
        return monitor.RuntimeConfig(self.config, self.options, self.path)

    def test_legacy_configuration_inherits_saved_text_provider(self):
        runtime = self.load()
        runtime.update({"text_model": "previous-no-thinking-model"})
        saved = json.loads(Path(self.path).read_text())
        saved.pop("fast_text", None)
        Path(self.path).write_text(json.dumps(saved))
        restored = self.load()
        self.assertEqual(restored.provider_settings(False), restored.fast_text_settings())
        self.assertEqual("previous-no-thinking-model", restored.masked_settings()["fast_text"]["model"])
        restored.update({"text_model": "updated-main"})
        self.assertEqual("updated-main", restored.fast_text_settings()[2])

    def test_independent_settings_persist_and_do_not_change_main_model(self):
        runtime = self.load()
        runtime.update({"fast_text_url": "https://fast.example.org/v1", "fast_text_model": "fast-v2", "fast_text_key": "fast-secret-5678"})
        restored = self.load()
        self.assertEqual(("https://fast.example.org/v1/chat/completions", "fast-secret-5678", "fast-v2"), restored.fast_text_settings())
        self.assertEqual("prior-text", restored.provider_settings(False)[2])
        self.assertNotIn("fast-secret-5678", json.dumps(restored.masked_settings()))
        restored.update({"fast_text_model": "", "fast_text_key": ""})
        self.assertEqual("fast-v2", restored.fast_text_settings()[2])
        restored.update({"fast_text_url": "-", "fast_text_model": "-", "fast_text_key": "-"})
        self.assertEqual(restored.provider_settings(False), self.load().fast_text_settings())

    def test_profiles_include_overrides_and_legacy_profiles_restore_inheritance(self):
        runtime = self.load()
        runtime.save_profile("legacy")
        runtime.update({"fast_text_model": "fast-v3"})
        runtime.save_profile("custom")
        saved = json.loads(Path(self.path).read_text())
        saved["profiles"][0].pop("fast_text")
        Path(self.path).write_text(json.dumps(saved))
        runtime = self.load()
        runtime.apply_profile("legacy")
        self.assertEqual("prior-text", runtime.fast_text_settings()[2])
        runtime.apply_profile("custom")
        self.assertEqual("fast-v3", runtime.fast_text_settings()[2])
        self.assertEqual("fast-v3", runtime.list_profiles()[1]["fast_text_model"])

    def test_invalid_fields_and_cross_host_inherited_secret_are_rejected(self):
        runtime = self.load()
        original = runtime.fast_text_settings()
        for fields in ({"fast_text_model": 1}, {"fast_text_model": "x" * 401},
                       {"fast_text_url": "http://bad.example.org"},
                       {"fast_text_url": "https://other.example.org/v1"}):
            with self.subTest(fields=fields), self.assertRaises(legacy.ClientError):
                runtime.update(fields)
            self.assertEqual(original, runtime.fast_text_settings())

    def test_probe_uses_fast_provider_and_non_thinking_options(self):
        runtime = self.load()
        runtime.update({"fast_text_url": "https://dashscope.aliyuncs.com/compatible-mode/v1", "fast_text_model": "qwen3.8-flash", "fast_text_key": "fast-key"})
        with patch.object(monitor, "post_json", return_value={}) as post:
            self.assertTrue(monitor.probe_provider("fast_text", runtime)["ok"])
            self.assertEqual("qwen3.8-flash", post.call_args.args[2]["model"])
            self.assertEqual("fast-key", post.call_args.args[1])
            self.assertFalse(post.call_args.args[2]["enable_thinking"])

    def test_invalid_ports_are_rejected_without_changing_configuration(self):
        runtime = self.load()
        original = runtime.masked_settings()
        for field in ("text_url", "fast_text_url"):
            for port in ("notaport", "0", "65536"):
                with self.subTest(field=field, port=port), self.assertRaises(legacy.ClientError):
                    runtime.update({field: f"https://example.org:{port}/v1"})
                self.assertEqual(original, runtime.masked_settings())

    def test_invalid_legacy_endpoint_keeps_admin_status_available(self):
        for section in ("text", "fast_text"):
            with self.subTest(section=section):
                Path(self.path).write_text(json.dumps({section: {
                    "url": "https://example.org:notaport/v1", "model": "old-model", "key": "old-key"
                }}))
                runtime = self.load()
                self.assertFalse(runtime.masked_settings()["fast_text"]["configured"])
                self.assertEqual("", runtime.fast_text_settings()[1])

    def test_retained_fast_key_requires_explicit_replacement_on_origin_change(self):
        runtime = self.load()
        runtime.update({"fast_text_url": "https://fast-a.example.org/v1", "fast_text_key": "key-a"})
        original = runtime.fast_text_settings()
        with self.assertRaises(legacy.ClientError):
            runtime.update({"fast_text_url": "https://fast-b.example.org/v1", "fast_text_key": ""})
        self.assertEqual(original, runtime.fast_text_settings())
        runtime.update({"fast_text_url": "https://fast-b.example.org/v1", "fast_text_key": "key-b"})
        self.assertEqual("key-b", runtime.fast_text_settings()[1])
        runtime.update({"fast_text_url": "https://fast-b.example.org:443/other"})
        self.assertEqual("key-b", runtime.fast_text_settings()[1])

    def test_retained_fast_key_is_not_transferred_by_changing_inherited_text_url(self):
        runtime = self.load()
        runtime.update({"fast_text_key": "fast-key"})
        with self.assertRaises(legacy.ClientError):
            runtime.update({"text_url": "https://other.example.org/v1"})
        runtime.update({"text_url": "https://other.example.org/v1", "fast_text_key": "-"})
        self.assertEqual(runtime.provider_settings(False), runtime.fast_text_settings())
