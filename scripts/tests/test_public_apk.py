import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_public_apk import publish, validate_repo


class FakeStore:
    repo = "owner/public-apk"

    def __init__(self):
        self.releases, self.calls, self.latest = {}, [], None
        self.fail_upload = False

    def release(self, tag=None):
        return self.releases.get(tag if tag else self.latest)

    def download(self, release, asset, path):
        path.write_bytes(release["files"][asset])

    def prepare(self, tag, name, existing):
        self.calls.append("prepare")
        if existing is None:
            self.releases[tag] = {"tag_name": tag, "draft": True, "files": {}}

    def upload(self, tag, path):
        if self.fail_upload:
            raise OSError("upload failed")
        self.calls.append(path.name)
        self.releases[tag]["files"][path.name] = path.read_bytes()

    def publish(self, tag):
        self.calls.append("publish")
        self.releases[tag]["draft"] = False
        self.latest = tag


class PublicApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def apk(self, text):
        path = self.root / "PoJian.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", text)
            archive.writestr("lib/unchanged.so", bytes(range(256)) * 1000)
        return path

    def test_uploads_complete_manifest_before_publishing_and_preserves_delta(self):
        store = FakeStore()
        first = publish(store, self.apk("old"), 40, "0.5.18", "cert")
        self.assertEqual([], first["patches"])
        second = publish(store, self.apk("changed"), 41, "0.5.19", "cert")
        self.assertEqual(40, second["patches"][0]["fromVersionCode"])
        self.assertEqual(["update.json", "publish"], store.calls[-2:])
        self.assertEqual("https://github.com/owner/public-apk/releases/download/v0.5.19/PoJian.apk", second["apkUrl"])
        self.assertEqual(second["versionCode"], second["version_code"])

    def test_failed_upload_does_not_change_latest(self):
        store = FakeStore()
        publish(store, self.apk("old"), 40, "0.5.18", "cert")
        store.fail_upload = True
        with self.assertRaises(OSError):
            publish(store, self.apk("changed"), 41, "0.5.19", "cert")
        self.assertEqual("v0.5.18", store.latest)
        self.assertTrue(store.releases["v0.5.19"]["draft"])

    def test_retry_is_idempotent_and_published_bytes_cannot_change(self):
        store = FakeStore()
        apk = self.apk("old")
        publish(store, apk, 40, "0.5.18", "cert")
        calls = len(store.calls)
        publish(store, apk, 40, "0.5.18", "cert")
        self.assertEqual(calls, len(store.calls))
        with self.assertRaises(ValueError):
            publish(store, self.apk("other"), 40, "0.5.18", "cert")

    def test_older_version_cannot_replace_latest(self):
        store = FakeStore()
        publish(store, self.apk("new"), 41, "0.5.19", "cert")
        with self.assertRaises(ValueError):
            publish(store, self.apk("old"), 40, "0.5.18", "cert")

    def test_repository_validation(self):
        self.assertEqual("baijiamin/PoJian-Releases", validate_repo("baijiamin/PoJian-Releases"))
        for value in ("https://github.com/repo", "repo", "owner/repo/more", "owner/repo\n"):
            with self.assertRaises(ValueError):
                validate_repo(value)


if __name__ == "__main__":
    unittest.main()
