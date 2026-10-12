"""Publish immutable signed APK first, optional delta second, latest manifest last."""
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

from apk_delta import MAX_APK, apply_patch, create_patch, sha256


def public_base(value):
    value = value.rstrip("/")
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("R2_PUBLIC_BASE_URL must be a public HTTPS bucket URL without query or credentials")
    return value


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
    account = os.environ["R2_ACCOUNT_ID"]
    if not re.fullmatch(r"[0-9a-fA-F]{32}", account):
        raise ValueError("Invalid R2_ACCOUNT_ID")
    client = boto3.client("s3", endpoint_url=f"https://{account}.r2.cloudflarestorage.com", region_name="auto",
                          aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                          aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"])
    metadata = json.loads(Path("release-apk/update.json").read_text())
    publish(client, os.environ["R2_BUCKET"], os.environ["R2_PUBLIC_BASE_URL"], Path("release-apk/PoJian.apk"),
            metadata["version_code"], metadata["version_name"], Path("app/signing-certificate.sha256").read_text())
