"""Publish immutable signed APK first, optional delta second, latest manifest last."""
import argparse
import hashlib
import json
import os
import re
import tempfile
import time
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from apk_delta import MAX_APK, apply_patch, create_patch, sha256


def public_base(value):
    value = value.rstrip("/")
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("R2_PUBLIC_BASE_URL must be a public HTTPS bucket URL without query or credentials")
    return value


def configuration(environ):
    required = ("R2_ACCOUNT_ID", "R2_BUCKET", "R2_PUBLIC_BASE_URL", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
    for key in required:
        if not environ.get(key, "").strip():
            raise ValueError(f"{key} is missing")
    if not re.fullmatch(r"[0-9a-fA-F]{32}", environ["R2_ACCOUNT_ID"]):
        raise ValueError("Invalid R2_ACCOUNT_ID")
    return environ["R2_BUCKET"], public_base(environ["R2_PUBLIC_BASE_URL"])


def verify_public(base, manifest):
    """Check anonymous downloads, including every byte of the published APK."""
    headers = {"User-Agent": "PoJian-Updater/1.0"}
    with urlopen(Request(base + "/updates/latest.json", headers=headers), timeout=60) as response:
        if json.loads(response.read(65537)) != manifest:
            raise ValueError("Public R2 manifest does not match the release")
    digest, size = hashlib.sha256(), 0
    with urlopen(Request(manifest["apkUrl"], headers=headers), timeout=60) as response:
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > MAX_APK:
                raise ValueError("Oversized public APK")
            digest.update(block)
    if size != manifest["size"] or digest.hexdigest() != manifest["sha256"]:
        raise ValueError("Public R2 APK checksum or size mismatch")


def prune_releases(client, bucket, manifest, keep=3):
    """Remove old release objects only after the new public release is verified."""
    if keep < 1:
        raise ValueError("At least one release must be retained")
    pattern = re.compile(r"updates/releases/(\d+)-([0-9a-f]{64})/(PoJian\.apk|from-\d+\.pjdiff)")
    releases = {}
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix="updates/releases/"):
        for item in page.get("Contents", []):
            key = item["Key"]
            match = pattern.fullmatch(key)
            # A newer upload may be in progress; never let it displace or delete this release.
            if not match or int(match[1]) > manifest["versionCode"]:
                continue
            prefix = key.rsplit("/", 1)[0]
            releases.setdefault(prefix, {"code": int(match[1]), "keys": [], "apk": False})
            releases[prefix]["keys"].append(key)
            releases[prefix]["apk"] |= match[3] == "PoJian.apk"
    current = manifest["apkKey"].rsplit("/", 1)[0]
    if not releases.get(current, {}).get("apk"):
        raise ValueError("Current APK missing from R2 listing; refusing cleanup")
    candidates = sorted((p for p, info in releases.items() if info["apk"] and p != current),
                        key=lambda p: (releases[p]["code"], p), reverse=True)
    retained, versions = {current}, {manifest["versionCode"]}
    for prefix in candidates:
        if len(retained) >= keep:
            break
        if releases[prefix]["code"] not in versions:
            retained.add(prefix)
            versions.add(releases[prefix]["code"])
    obsolete = [{"Key": key} for prefix, info in releases.items() if prefix not in retained
                for key in info["keys"]]
    for offset in range(0, len(obsolete), 1000):
        result = client.delete_objects(Bucket=bucket, Delete={"Objects": obsolete[offset:offset + 1000], "Quiet": True})
        if result.get("Errors"):
            raise RuntimeError("R2 retention cleanup failed for one or more objects")
    print(f"R2 retention: kept {len(retained)} releases; deleted {len(obsolete)} old objects")


def finalize_release(client, bucket, base, manifest):
    for attempt in range(3):
        try:
            verify_public(base, manifest)
            break
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(5)
    print("Anonymous R2 manifest and APK download verified")
    prune_releases(client, bucket, manifest)


def publish(client, bucket, base, apk, code, name, certificate):
    base = public_base(base)
    if not 0 < apk.stat().st_size <= MAX_APK or code <= 0 or not re.fullmatch(r"\d+\.\d+\.\d+", name):
        raise ValueError("Invalid APK release")
    latest_key = "updates/latest.json"
    try:
        response = client.get_object(Bucket=bucket, Key=latest_key)
        try:
            raw = response["Body"].read(65537)
        finally:
            response["Body"].close()
        if len(raw) > 65536:
            raise ValueError("Oversized existing manifest")
        previous = json.loads(raw)
    except client.exceptions.NoSuchKey:
        previous = None
    digest = sha256(apk)
    key = f"updates/releases/{code}-{digest}/PoJian.apk"
    if previous:
        if previous["versionCode"] > code:
            raise ValueError("Refusing to replace a newer release")
        if previous["versionCode"] == code and previous["sha256"] != digest:
            raise ValueError("Version already published with different APK bytes")
        if previous["versionCode"] == code:
            print("Identical version already published")
            return previous
    manifest = {"versionCode": code, "versionName": name, "apkUrl": base + "/" + key,
                "sha256": digest, "size": apk.stat().st_size, "apkKey": key,
                "signingCertificateSha256": certificate.strip(), "patches": []}
    # Immutable keys prevent cached older APKs from being confused with a new version.
    client.upload_file(str(apk), bucket, key, ExtraArgs={"ContentType": "application/vnd.android.package-archive",
                       "CacheControl": "public, max-age=31536000, immutable"})
    if previous and previous.get("signingCertificateSha256") == certificate.strip():
        old_key = previous.get("apkKey", "")
        if re.fullmatch(r"updates/releases/\d+-[0-9a-f]{64}/PoJian\.apk", old_key):
            with tempfile.TemporaryDirectory() as directory:
                old, patch, rebuilt = (Path(directory) / item for item in ("base.apk", "update.pjdiff", "rebuilt.apk"))
                client.download_file(bucket, old_key, str(old))
                if sha256(old) != previous["sha256"]:
                    raise ValueError("Previous APK checksum mismatch")
                create_patch(old, apk, patch)
                # Only advertise patches that save at least 20% of download bytes.
                if patch.stat().st_size < apk.stat().st_size * 0.8:
                    apply_patch(old, patch, rebuilt)
                    if sha256(rebuilt) != digest:
                        raise ValueError("Patch reconstruction mismatch")
                    patch_key = f"updates/releases/{code}-{digest}/from-{previous['versionCode']}.pjdiff"
                    client.upload_file(str(patch), bucket, patch_key, ExtraArgs={"ContentType": "application/octet-stream",
                                       "CacheControl": "public, max-age=31536000, immutable"})
                    manifest["patches"].append({"format": "pjdiff1", "fromVersionCode": previous["versionCode"],
                        "baseSha256": previous["sha256"], "url": base + "/" + patch_key,
                        "sha256": sha256(patch), "size": patch.stat().st_size})
    client.put_object(Bucket=bucket, Key=latest_key, Body=json.dumps(manifest).encode(),
                      ContentType="application/json", CacheControl="no-store, max-age=0")
    print(f"Published {name}: APK {apk.stat().st_size} bytes; patches {[p['size'] for p in manifest['patches']]}")
    return manifest


if __name__ == "__main__":
    import boto3
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    bucket, base = configuration(os.environ)
    account = os.environ["R2_ACCOUNT_ID"]
    client = boto3.client("s3", endpoint_url=f"https://{account}.r2.cloudflarestorage.com", region_name="auto",
                          aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                          aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"])
    if arguments.check:
        client.list_objects_v2(Bucket=bucket, Prefix="updates/", MaxKeys=1)
        print("R2 configuration and bucket read access verified")
    else:
        metadata = json.loads(Path("release-apk/update.json").read_text())
        manifest = publish(client, bucket, base, Path("release-apk/PoJian.apk"),
                           metadata["version_code"], metadata["version_name"],
                           Path("app/signing-certificate.sha256").read_text())
        finalize_release(client, bucket, base, manifest)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
                summary.write(f"R2: [{manifest['versionName']}]({manifest['apkUrl']})\n")
