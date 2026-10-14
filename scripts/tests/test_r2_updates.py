import io
import json
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_delta import MAGIC, apply_patch, create_patch, sha256
from publish_r2 import configuration, finalize_release, prune_releases, public_base, publish, verify_public


class MemoryR2:
    class Missing(Exception):
        pass
    exceptions = SimpleNamespace(NoSuchKey=Missing)

    def __init__(self):
        self.objects, self.calls = {}, []
        self.fail_upload = False

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise self.Missing()
        return {"Body": io.BytesIO(self.objects[Key])}

    def upload_file(self, path, bucket, key, ExtraArgs):
        if self.fail_upload:
            raise OSError("upload failed")
        self.calls.append(key)
        self.objects[key] = Path(path).read_bytes()

    def download_file(self, bucket, key, path):
        Path(path).write_bytes(self.objects[key])

    def put_object(self, Bucket, Key, Body, **kwargs):
        self.calls.append(Key)
        self.objects[Key] = Body
        assert kwargs["CacheControl"].startswith("no-store")

    def get_paginator(self, operation):
        assert operation == "list_objects_v2"
        return self

    def paginate(self, Bucket, Prefix):
        keys = [key for key in self.objects if key.startswith(Prefix)]
        for index in range(0, len(keys), 2):
            yield {"Contents": [{"Key": key} for key in keys[index:index + 2]]}

    def delete_objects(self, Bucket, Delete):
        for item in Delete["Objects"]:
            self.calls.append("delete:" + item["Key"])
            self.objects.pop(item["Key"], None)
        return {}


class R2UpdatesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def apk(self, name, text):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", text)
            archive.writestr("lib/arm64-v8a/large.so", bytes(range(256)) * 1000)
        return path

    def test_byte_exact_rebuild_with_shifted_headers_and_changed_payload(self):
        old, new = self.apk("old.apk", b"old"), self.apk("new.apk", b"new code changes length")
        patch, restored = self.root / "patch", self.root / "restored.apk"
        create_patch(old, new, patch)
        apply_patch(old, patch, restored)
        self.assertEqual(new.read_bytes(), restored.read_bytes())
        self.assertLess(patch.stat().st_size, new.stat().st_size * 0.1)

    def test_malformed_patch_never_accepted(self):
        old = self.apk("old.apk", b"old")
        for data in [b"bad", MAGIC + struct.pack(">Q", 1) + b"\x00",
                     MAGIC + struct.pack(">Q", 1) + b"\x01" + struct.pack(">QQ", 10**9, 1),
                     MAGIC + struct.pack(">Q", 1) + b"\x02" + struct.pack(">Q", 2) + b"xx\x00"]:
            patch = self.root / "bad"
            patch.write_bytes(data)
            with self.assertRaises((ValueError, struct.error)):
                apply_patch(old, patch, self.root / "out")

    def test_publication_order_delta_and_idempotent_retry(self):
        client = MemoryR2()
        old, new = self.apk("old.apk", b"old"), self.apk("new.apk", b"new")
        first = publish(client, "bucket", "https://updates.example.com", old, 40, "0.5.20", "cert")
        self.assertEqual([], first["patches"])
        second = publish(client, "bucket", "https://updates.example.com", new, 41, "0.5.21", "cert")
        self.assertEqual(40, second["patches"][0]["fromVersionCode"])
        self.assertEqual("updates/latest.json", client.calls[-1])
        before = len(client.calls)
        publish(client, "bucket", "https://updates.example.com", new, 41, "0.5.21", "cert")
        self.assertEqual(before, len(client.calls))
        with self.assertRaises(ValueError):
            publish(client, "bucket", "https://updates.example.com", old, 40, "0.5.20", "cert")
        with self.assertRaises(ValueError):
            publish(client, "bucket", "https://updates.example.com", old, 41, "0.5.21", "cert")

    def test_upload_failure_preserves_previous_manifest(self):
        client = MemoryR2()
        publish(client, "bucket", "https://updates.example.com", self.apk("old.apk", b"old"), 40, "0.5.20", "cert")
        saved = client.objects["updates/latest.json"]
        client.fail_upload = True
        with self.assertRaises(OSError):
            publish(client, "bucket", "https://updates.example.com", self.apk("new.apk", b"new"), 41, "0.5.21", "cert")
        self.assertEqual(saved, client.objects["updates/latest.json"])

    def test_signing_change_does_not_advertise_patch(self):
        client = MemoryR2()
        publish(client, "bucket", "https://updates.example.com", self.apk("old.apk", b"old"), 40, "0.5.20", "cert")
        result = publish(client, "bucket", "https://updates.example.com", self.apk("new.apk", b"new"), 41, "0.5.21", "other")
        self.assertEqual([], result["patches"])

    def test_rejects_credentials_http_and_queries(self):
        for url in ("http://cdn.example.com", "https://user:password@cdn.example.com", "https://cdn.example.com?q=1"):
            with self.assertRaises(ValueError):
                public_base(url)

    def test_missing_configuration_fails_before_network(self):
        config = dict(R2_ACCOUNT_ID="a" * 32, R2_BUCKET="pojian",
                      R2_PUBLIC_BASE_URL="https://pub-example.r2.dev/",
                      R2_ACCESS_KEY_ID="access", R2_SECRET_ACCESS_KEY="secret")
        self.assertEqual(("pojian", "https://pub-example.r2.dev"), configuration(config))
        for key in config:
            with self.subTest(key=key), self.assertRaises(ValueError):
                configuration({**config, key: ""})

    def test_anonymous_download_verification_detects_corruption(self):
        client = MemoryR2()
        apk = self.apk("release.apk", b"release")
        base = "https://pub-example.r2.dev"
        manifest = publish(client, "bucket", base, apk, 56, "0.5.34", "cert")
        for content in (apk.read_bytes(), b"corrupt"):
            with patch("publish_r2.urlopen", side_effect=[
                io.BytesIO(json.dumps(manifest).encode()), io.BytesIO(content)
            ]) as request:
                if content == b"corrupt":
                    with self.assertRaises(ValueError):
                        verify_public(base, manifest)
                else:
                    verify_public(base, manifest)
                self.assertEqual(base + "/updates/latest.json", request.call_args_list[0].args[0].full_url)
                self.assertEqual(manifest["apkUrl"], request.call_args_list[1].args[0].full_url)
                for call in request.call_args_list:
                    self.assertEqual("PoJian-Updater/1.0", call.args[0].get_header("User-agent"))

    def test_retention_keeps_latest_three_versions_and_their_patches(self):
        client = MemoryR2()
        keys = {}
        for code in (8, 9, 10, 11, 12, 13):
            prefix = f"updates/releases/{code}-{'a' * 64}"
            keys[code] = prefix + "/PoJian.apk"
            client.objects[keys[code]] = b"apk"
            client.objects[prefix + f"/from-{code - 1}.pjdiff"] = b"patch"
        client.objects["updates/latest.json"] = b"latest"
        client.objects["updates/releases/unrelated/PoJian.apk"] = b"unrelated"
        manifest = {"versionCode": 12, "apkKey": keys[12]}
        prune_releases(client, "bucket", manifest)
        for code in (8, 9):
            self.assertNotIn(keys[code], client.objects)
            self.assertFalse(any(key.startswith(keys[code].rsplit('/', 1)[0] + '/') for key in client.objects))
        for code in (10, 11, 12, 13):
            self.assertIn(keys[code], client.objects)
            self.assertIn(keys[code].rsplit('/', 1)[0] + f"/from-{code - 1}.pjdiff", client.objects)
        self.assertIn("updates/latest.json", client.objects)
        self.assertIn("updates/releases/unrelated/PoJian.apk", client.objects)
        before = dict(client.objects)
        prune_releases(client, "bucket", manifest)
        self.assertEqual(before, client.objects)

    def test_failed_public_verification_never_prunes(self):
        with patch("publish_r2.verify_public", side_effect=ValueError("bad download")), \
             patch("publish_r2.time.sleep"), patch("publish_r2.prune_releases") as prune:
            with self.assertRaises(ValueError):
                finalize_release(MemoryR2(), "bucket", "https://example.com", {})
            prune.assert_not_called()

    def test_cleanup_requires_current_apk_and_reports_delete_errors(self):
        client = MemoryR2()
        current = f"updates/releases/4-{'a' * 64}/PoJian.apk"
        manifest = {"versionCode": 4, "apkKey": current}
        with self.assertRaises(ValueError):
            prune_releases(client, "bucket", manifest)
        for code in (1, 2, 3, 4):
            client.objects[f"updates/releases/{code}-{'a' * 64}/PoJian.apk"] = b"apk"
        with patch.object(client, "delete_objects", return_value={"Errors": [{"Code": "AccessDenied"}]}):
            with self.assertRaises(RuntimeError):
                prune_releases(client, "bucket", manifest)


if __name__ == "__main__":
    unittest.main()
