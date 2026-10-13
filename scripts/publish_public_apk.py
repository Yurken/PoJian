"""Publish signed APK and optional verified delta to a separate public release repository."""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from apk_delta import MAX_APK, apply_patch, create_patch, sha256


def gh(*arguments, optional=False):
    result = subprocess.run(["gh", *arguments], capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        if optional and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def validate_repo(repo):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid public release repository")
    return repo


class ReleaseStore:
    def __init__(self, repo):
        self.repo = validate_repo(repo)
        info = json.loads(gh("api", f"repos/{repo}"))
        if info["private"]:
            raise ValueError("APK distribution repository must be public")
        if not info.get("permissions", {}).get("push"):
            raise ValueError("APK_RELEASE_TOKEN has no write access to the public release repository")
        self.target = info["default_branch"]

    def release(self, tag=None):
        path = f"repos/{self.repo}/releases/" + (f"tags/{tag}" if tag else "latest")
        result = gh("api", path, optional=True)
        return None if result is None else json.loads(result)

    def download(self, release, asset, path):
        row = next((a for a in release["assets"] if a["name"] == asset), None)
        limit = MAX_APK if asset == "PoJian.apk" else 65536
        if row is None or not 0 < row["size"] <= limit:
            raise ValueError("Missing or oversized release asset")
        gh("release", "download", release["tag_name"], "--repo", self.repo,
           "--pattern", asset, "--output", str(path), "--clobber")
        if path.stat().st_size != row["size"]:
            raise ValueError("Incomplete release asset")

    def prepare(self, tag, name, existing):
        if existing is None:
            gh("release", "create", tag, "--repo", self.repo, "--target", self.target, "--draft",
               "--title", f"破茧 v{name}", "--notes", "破茧 Android 安装包及更新清单。")

    def upload(self, tag, path):
        gh("release", "upload", tag, str(path), "--repo", self.repo, "--clobber")

    def publish(self, tag):
        gh("release", "edit", tag, "--repo", self.repo, "--draft=false", "--latest")


def preflight(repo, environ):
    if not (environ.get("GH_TOKEN") or "").strip():
        raise ValueError("APK_RELEASE_TOKEN is missing; configure it in the source repository Actions secrets")
    return ReleaseStore(repo)


def verify_release(store, tag, manifest, root):
    release = store.release(tag)
    if not release or release["draft"] or release.get("prerelease"):
        raise ValueError("Public release is not published")
    store.download(release, "update.json", root / "published.json")
    if json.loads((root / "published.json").read_text()) != manifest:
        raise ValueError("Published update manifest does not match this APK")
    assets = {asset["name"]: asset for asset in release["assets"]}
    for name, size, digest in [("PoJian.apk", manifest["size"], manifest["sha256"]),
                              *[(p["url"].rsplit("/", 1)[1], p["size"], p["sha256"]) for p in manifest["patches"]]]:
        asset = assets.get(name)
        if not asset or asset["size"] != size or asset.get("digest") not in (None, "sha256:" + digest):
            raise ValueError("Published APK or patch asset is missing or does not match the manifest")


def publish(store, apk, code, name, certificate):
    if not 0 < apk.stat().st_size <= MAX_APK or code <= 0 or not re.fullmatch(r"\d+\.\d+\.\d+", name):
        raise ValueError("Invalid APK release")
    tag, digest = f"v{name}", sha256(apk)
    base = f"https://github.com/{store.repo}/releases/download/{tag}/"
    manifest = {"version": 1, "version_code": code, "version_name": name,
                "versionCode": code, "versionName": name, "apkUrl": base + "PoJian.apk",
                "sha256": digest, "size": apk.stat().st_size,
                "signingCertificateSha256": certificate.strip(), "patches": []}
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        existing, latest = store.release(tag), store.release()
        if existing and not existing["draft"]:
            store.download(existing, "update.json", root / "existing.json")
            saved = json.loads((root / "existing.json").read_text())
            if saved.get("version_code") == code and saved.get("sha256") == digest:
                verify_release(store, tag, saved, root)
                print("Identical public release already published")
                return saved
            raise ValueError("Refusing to overwrite an already published APK version")
        previous = None
        if latest:
            store.download(latest, "update.json", root / "previous.json")
            previous = json.loads((root / "previous.json").read_text())
            if previous["version_code"] >= code:
                raise ValueError("Public latest release is not older than this build")
        artifacts = [apk]
        if previous and previous.get("signingCertificateSha256") == certificate.strip():
            old, restored = root / "base.apk", root / "restored.apk"
            store.download(latest, "PoJian.apk", old)
            if sha256(old) != previous["sha256"]:
                raise ValueError("Base APK digest mismatch")
            patch = root / f"from-{previous['version_code']}.pjdiff"
            create_patch(old, apk, patch)
            if patch.stat().st_size < apk.stat().st_size * 0.8:
                apply_patch(old, patch, restored)
                if sha256(restored) != digest:
                    raise ValueError("Delta reconstruction failed")
                manifest["patches"] = [{"format": "pjdiff1", "fromVersionCode": previous["version_code"],
                    "baseSha256": previous["sha256"], "url": base + patch.name,
                    "sha256": sha256(patch), "size": patch.stat().st_size}]
                artifacts.append(patch)
        info = root / "update.json"
        info.write_text(json.dumps(manifest), encoding="utf-8")
        store.prepare(tag, name, existing)
        for artifact in artifacts + [info]:
            store.upload(tag, artifact)
        store.publish(tag)
        verify_release(store, tag, manifest, root)
        print(f"Public release {tag} published; patches: {[p['size'] for p in manifest['patches']]}")
        return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check credentials and repository access without publishing")
    arguments = parser.parse_args()
    try:
        store = preflight(os.environ["APK_RELEASE_REPOSITORY"], os.environ)
        if arguments.check:
            print(f"Public release credentials and repository access checked: {store.repo}")
        else:
            metadata = json.loads(Path("release-apk/update.json").read_text())
            manifest = publish(store, Path("release-apk/PoJian.apk"), metadata["version_code"],
                               metadata["version_name"], Path("app/signing-certificate.sha256").read_text())
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
                    summary.write(f"Published [{manifest['versionName']}](https://github.com/{store.repo}/releases/tag/v{manifest['versionName']})\n")
    except (KeyError, ValueError, RuntimeError, OSError) as error:
        sys.exit(f"::error::{error}")
