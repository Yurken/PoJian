#!/usr/bin/env python3
"""Verify the APK signature and require exactly the pinned signing certificate."""
from pathlib import Path
import re
import subprocess
import sys

if len(sys.argv) != 4:
    sys.exit("Usage: verify-apk-signing.py APKSIGNER APK CERTIFICATE_SHA256_FILE")
result = subprocess.run([sys.argv[1], "verify", "--verbose", "--print-certs", sys.argv[2]],
                        capture_output=True, text=True)
expected = Path(sys.argv[3]).read_text().strip()
actual = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", result.stdout, re.M)
if result.returncode or not re.fullmatch(r"[0-9a-f]{64}", expected) or actual != [expected]:
    sys.exit("APK signature verification failed or certificate differs from the fixed project identity.")
print("APK signature verified: " + expected)
