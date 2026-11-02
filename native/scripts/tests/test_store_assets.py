import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("store_assets", ROOT / "scripts/validate-store-assets.py")
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


def ihdr(width, height):
    """Binary header fixture only; no screenshot or encoded image is generated."""
    chunk = b"IHDR" + struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return assets.PNG_SIGNATURE + struct.pack(">I", 13) + chunk + struct.pack(">I", zlib.crc32(chunk) & 0xffffffff)


class StoreAssetsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for relative, dimensions in assets.ARTWORK.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(ihdr(*dimensions))
        locale = self.root / "store/en-US"
        locale.mkdir()
        for filename in assets.TEXT_LIMITS:
            (locale / filename).write_text("Test listing\n", encoding="utf-8")
        self.contact = self.root / "store/contact.json"
        self.contact.write_text(json.dumps({"publisher": "Test publisher", "email": "contact@example.com", "privacyPolicyUrl": "https://example.com/privacy"}))

    def test_png_signature_and_crc_are_checked_before_dimensions(self):
        good = ihdr(320, 180)
        for content in (b"not a PNG", good[:24], good[:-1] + bytes([good[-1] ^ 1])):
            with self.subTest(content=content), patch.object(Path, "open", return_value=io.BytesIO(content)):
                with self.assertRaises(ValueError):
                    assets.png_size(Path("fixture"))
        with patch.object(Path, "open", return_value=io.BytesIO(good)):
            self.assertEqual((320, 180), assets.png_size(Path("fixture")))

    def test_missing_screenshots_are_nonfatal_until_explicitly_required(self):
        draft = assets.validate(self.root)
        self.assertEqual([], draft.errors)
        self.assertEqual(2, len(draft.missing_screenshots))
        final = assets.validate(self.root, require_screenshots=True)
        self.assertEqual(2, len(final.errors))

    def test_existing_invalid_screenshot_fails_in_both_modes(self):
        phone = self.root / "store/screenshots/phone"
        phone.mkdir(parents=True)
        (phone / "header-fixture.png").write_bytes(ihdr(1080, 2400))
        for require in (False, True):
            with self.subTest(require=require):
                report = assets.validate(self.root, require_screenshots=require)
                self.assertTrue(any("longer side" in error for error in report.errors))

    def test_screenshot_boundaries_and_landscape_tv_ratio(self):
        for size in ((320, 320), (320, 640), (3840, 1920)):
            self.assertEqual([], assets.screenshot_errors(*size, "phone"))
        self.assertEqual([], assets.screenshot_errors(1280, 720, "tv"))
        for size, kind in (((319, 640), "phone"), ((3841, 2000), "phone"), ((1080, 2400), "phone"), ((720, 1280), "tv"), ((1280, 721), "tv")):
            with self.subTest(size=size, kind=kind):
                self.assertTrue(assets.screenshot_errors(*size, kind))

    def test_text_limits_count_unicode_characters_and_reject_multiline_title(self):
        title = self.root / "store/en-US/title.txt"
        title.write_text("я" * 30 + "\n", encoding="utf-8")
        self.assertEqual([], assets.validate(self.root).errors)
        for value in ("я" * 31, "Two\nlines"):
            title.write_text(value, encoding="utf-8")
            self.assertTrue(any("title.txt" in error for error in assets.validate(self.root).errors))

    def test_contact_requires_https_without_credentials_and_valid_email(self):
        good = {"publisher": "Test publisher", "email": "contact@example.com", "privacyPolicyUrl": "https://example.com/privacy"}
        for overrides in ({"privacyPolicyUrl": "http://example.com/privacy"}, {"privacyPolicyUrl": "https://user:secret@example.com/privacy"}, {"privacyPolicyUrl": "https://example.com:70000/"}, {"email": "not-email"}, {"publisher": " "}):
            self.contact.write_text(json.dumps(good | overrides))
            self.assertTrue(any("store/contact.json" in error for error in assets.validate(self.root).errors))


if __name__ == "__main__":
    unittest.main()
