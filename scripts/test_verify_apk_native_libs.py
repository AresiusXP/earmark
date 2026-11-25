#!/usr/bin/env python3
"""Exercise the APK native-library gate with small ZIP fixtures."""
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_apk_native_libs.py")
ARM32 = "armeabi-v7a"
ARM64 = "arm64-v8a"
TORRENT = "libtorrent4j.so"


class VerifyApkNativeLibsTest(unittest.TestCase):
    def check(self, libraries, ok, message=""):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app-release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for abi, name, content in libraries:
                    archive.writestr(f"lib/{abi}/{name}", content)
            result = subprocess.run([sys.executable, str(SCRIPT), str(apk)], capture_output=True, text=True)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, ok, output)
        self.assertIn(message, output)

    def test_matching_libraries_pass(self):
        self.check([(abi, name, b"native") for abi in (ARM32, ARM64) for name in (TORRENT, "libother.so")], True)

    def test_missing_abi_fails(self):
        self.check([(ARM64, TORRENT, b"native")], False, ARM32)

    def test_missing_libtorrent_from_both_abis_fails(self):
        self.check([(abi, "libother.so", b"native") for abi in (ARM32, ARM64)], False, TORRENT)

    def test_missing_libtorrent_from_one_abi_fails(self):
        for missing, present in ((ARM32, ARM64), (ARM64, ARM32)):
            with self.subTest(missing=missing):
                self.check([(missing, "libother.so", b"native"), (present, TORRENT, b"native"), (present, "libother.so", b"native")], False, f"{missing}: {TORRENT}")

    def test_asymmetric_library_fails(self):
        self.check([(ARM32, TORRENT, b"native"), (ARM64, TORRENT, b"native"), (ARM64, "libother.so", b"native")], False, f"{ARM32}: libother.so")

    def test_extra_abi_must_also_have_all_libraries(self):
        self.check([(ARM32, TORRENT, b"native"), (ARM64, TORRENT, b"native"), ("x86_64", "libother.so", b"native")], False, "x86_64: libtorrent4j.so")

    def test_empty_library_fails(self):
        self.check([(ARM32, TORRENT, b""), (ARM64, TORRENT, b"native")], False, "empty native library")

    def test_invalid_apk_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "invalid.apk"
            apk.write_text("not a zip")
            result = subprocess.run([sys.executable, str(SCRIPT), str(apk)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verification failed", result.stderr)


if __name__ == "__main__":
    unittest.main()
