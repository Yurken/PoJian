import base64
import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("signing", Path(__file__).parents[1] / "prepare-android-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "private"
        self.env = {
            "POJIAN_SIGNING_KEYSTORE_BASE64": base64.b64encode(b"fixture keystore").decode(),
            "POJIAN_SIGNING_STORE_PASSWORD": " test:password\\",
            "POJIAN_SIGNING_KEY_ALIAS": "pojian",
            "POJIAN_SIGNING_KEY_PASSWORD": "key=password",
        }

    def test_restores_exact_bytes_with_private_permissions(self):
        properties = signing.prepare(self.destination, self.env)
        self.assertEqual(b"fixture keystore", (self.destination / "pojian-release.p12").read_bytes())
        self.assertEqual(0o700, stat.S_IMODE(self.destination.stat().st_mode))
        for file in self.destination.iterdir():
            self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
        self.assertIn("storePassword=\\ test\\:password\\\\\n", properties.read_text())

    def test_missing_secret_creates_no_files(self):
        for missing in self.env:
            with self.subTest(missing=missing), self.assertRaises(ValueError):
                signing.prepare(self.destination, {key: value for key, value in self.env.items() if key != missing})
            self.assertFalse(self.destination.exists())

    def test_bad_base64_creates_no_files(self):
        for invalid in ("not base64!", " "):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                signing.prepare(self.destination, dict(self.env, POJIAN_SIGNING_KEYSTORE_BASE64=invalid))
            self.assertFalse(self.destination.exists())

    def test_never_overwrites_existing_key(self):
        signing.prepare(self.destination, self.env)
        with self.assertRaises(FileExistsError):
            signing.prepare(self.destination, self.env)
        self.assertEqual(b"fixture keystore", (self.destination / "pojian-release.p12").read_bytes())

    def test_java_properties_escaping(self):
        self.assertEqual(r"\ \=\:\#\!\\\u000a\u000d\u0009\u4e2d\ud83d\udd11",
                         signing.property_value(" =:#!\\\n\r\t中🔑"))


if __name__ == "__main__":
    unittest.main()
