#!/usr/bin/env python3
"""Proves scripts/secret_scan.py detects what it claims to, using fake values built at runtime
(so this file itself never contains a real-looking secret). Run: python3 scripts/test_secret_scan.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import secret_scan  # noqa: E402


class SecretScanTest(unittest.TestCase):
    def kinds(self, path, text):
        return {k for _, _, k in secret_scan.scan_text(path, text)}

    def test_detects_credentials(self):
        self.assertIn("Google API key", self.kinds("a.kt", 'val k = "' + "AIza" + "B" * 35 + '"'))
        self.assertIn("private key", self.kinds("k.pem", "-----BEGIN " + "PRIVATE KEY-----"))
        self.assertIn("Google service account", self.kinds("s.json", '{"type": "service_' + 'account"}'))
        self.assertIn("GitHub token", self.kinds("a.yml", "token: ghp_" + "a1" * 18))
        fake_uuid = "-".join(["1234abcd", "12ab", "34cd", "56ef", "1234567890ab"])
        self.assertIn("App Check debug token", self.kinds("a.kt", "debug" + 'Token = "' + fake_uuid + '"'))

    def test_vins_flagged_in_source_but_not_tests(self):
        vin = "WBA" + "FR7C5" + "5BC" + "123456"  # 17 chars, fake
        self.assertIn("possible VIN", self.kinds("app/src/main/Foo.kt", f'val v = "{vin}"'))
        self.assertEqual(set(), self.kinds("app/src/test/FooTest.kt", f'val v = "{vin}"'))

    def test_ordinary_code_is_clean(self):
        self.assertEqual(set(), self.kinds("a.kt", 'val model = "gemini-3.8-flash"; val id = "0123456789ABCDEF0"'))

    def test_forbidden_files(self):
        for name in ["app/google-services.json", "release.jks", "local.properties", "phone.logcat"]:
            self.assertTrue(any(p.search(name) for p in secret_scan.FORBIDDEN_FILES), name)
        self.assertFalse(any(p.search("app/build.gradle.kts") for p in secret_scan.FORBIDDEN_FILES))


if __name__ == "__main__":
    unittest.main()
