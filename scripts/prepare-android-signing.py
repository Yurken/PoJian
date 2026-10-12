#!/usr/bin/env python3
"""Restore CI signing secrets into a new private directory, without logging secrets."""
import base64
import binascii
import os
from pathlib import Path
import sys


def property_value(value):
    # Properties.load(InputStream) uses ISO-8859-1 with Java Unicode escapes.
    result = []
    for char in value:
        if char in "\\ =:#!":
            result.append("\\" + char)
        elif 33 <= ord(char) <= 126:
            result.append(char)
        else:
            for offset in range(0, len(encoded := char.encode("utf-16-be")), 2):
                result.append("\\u" + encoded[offset:offset + 2].hex())
    return "".join(result)


def prepare(directory, environ):
    fields = {
        "storePassword": "POJIAN_SIGNING_STORE_PASSWORD",
        "keyAlias": "POJIAN_SIGNING_KEY_ALIAS",
        "keyPassword": "POJIAN_SIGNING_KEY_PASSWORD",
    }
    for name in ["POJIAN_SIGNING_KEYSTORE_BASE64", *fields.values()]:
        if not environ.get(name):
            raise ValueError(f"Missing required signing secret: {name}")
    try:
        key = base64.b64decode(environ["POJIAN_SIGNING_KEYSTORE_BASE64"], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Invalid signing keystore encoding") from None
    if not key:
        raise ValueError("Signing keystore is empty")
    directory = directory.resolve()
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    try:
        key_path = directory / "pojian-release.p12"
        with key_path.open("xb") as output:
            os.chmod(key_path, 0o600)
            output.write(key)
        values = {"storeFile": str(key_path), "storeType": "PKCS12"}
        values.update({name: environ[secret] for name, secret in fields.items()})
        properties = directory / "keystore.properties"
        with properties.open("x", encoding="ascii") as output:
            os.chmod(properties, 0o600)
            output.write("".join(f"{name}={property_value(value)}\n" for name, value in values.items()))
        return properties
    except BaseException:
        for name in ("pojian-release.p12", "keystore.properties"):
            (directory / name).unlink(missing_ok=True)
        directory.rmdir()
        raise


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: prepare-android-signing.py NEW_PRIVATE_DIRECTORY")
    try:
        prepare(Path(sys.argv[1]), os.environ)
    except (ValueError, OSError):
        sys.exit("Cannot restore fixed signing configuration; check signing secrets and destination directory.")
