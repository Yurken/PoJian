import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_delta import sha256
from publish_public_apk import preflight, publish, validate_repo


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
            self.releases[tag] = {"tag_name": tag, "draft": True, "files": {}, "assets": []}

    def upload(self, tag, path):
        if self.fail_upload:
            raise OSError("upload failed")
        self.calls.append(path.name)
        self.releases[tag]["files"][path.name] = path.read_bytes()
        assets = self.releases[tag]["assets"]
        assets[:] = [asset for asset in assets if asset["name"] != path.name]
        assets.append({"name": path.name, "size": path.stat().st_size, "digest": "sha256:" + sha256(path)})

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

    def test_missing_token_fails_before_any_github_request(self):
        with patch("publish_public_apk.gh") as request:
            for token in (None, "", " "):
                with self.subTest(token=token), self.assertRaisesRegex(ValueError, "APK_RELEASE_TOKEN is missing"):
                    preflight("owner/public-apk", {"GH_TOKEN": token})
            request.assert_not_called()

    def test_preflight_rejects_private_or_unwritable_repository(self):
        for private, push in ((True, True), (False, False), (False, None)):
            info = dict(private=private, default_branch="main", permissions=dict(push=push))
            with self.subTest(private=private, push=push), patch("publish_public_apk.gh", return_value=json.dumps(info)), self.assertRaises(ValueError):
                preflight("owner/public-apk", {"GH_TOKEN": "fixture"})

    def test_preflight_only_reads_repository_metadata(self):
        info = dict(private=False, default_branch="main", permissions=dict(push=True))
        with patch("publish_public_apk.gh", return_value=json.dumps(info)) as request:
            self.assertEqual("owner/public-apk", preflight("owner/public-apk", {"GH_TOKEN": "fixture"}).repo)
            request.assert_called_once_with("api", "repos/owner/public-apk")

    def test_retry_rejects_published_release_with_missing_or_changed_apk(self):
        for corrupt in ("missing", "digest", "size"):
            store, apk = FakeStore(), self.apk("old")
            publish(store, apk, 40, "0.5.18", "cert")
            asset = next(a for a in store.releases[store.latest]["assets"] if a["name"] == "PoJian.apk")
            if corrupt == "missing":
                store.releases[store.latest]["assets"].remove(asset)
            elif corrupt == "digest":
                asset["digest"] = "sha256:" + "0" * 64
            else:
                asset["size"] += 1
            with self.subTest(corrupt=corrupt), self.assertRaisesRegex(ValueError, "asset is missing or does not match"):
                publish(store, apk, 40, "0.5.18", "cert")

    def test_incomplete_publication_is_reported_as_failure(self):
        store = FakeStore()
        original = store.publish
        def omit_apk(tag):
            original(tag)
            store.releases[tag]["assets"] = [a for a in store.releases[tag]["assets"] if a["name"] != "PoJian.apk"]
        with patch.object(store, "publish", side_effect=omit_apk), self.assertRaisesRegex(ValueError, "asset is missing or does not match"):
            publish(store, self.apk("new"), 40, "0.5.18", "cert")


if __name__ == "__main__":
    unittest.main()
