import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("localization", ROOT / "scripts/validate-localization.py")
localization = importlib.util.module_from_spec(spec)
spec.loader.exec_module(localization)


class LocalizationTest(unittest.TestCase):
    def test_translation_cannot_drop_or_change_format_arguments(self):
        base = {"entry": ("string", {"text": "Open %1$s: %2$d items"})}
        for value in ("Ouvrir %1$s", "Ouvrir %1$s : %2$s éléments", ""):
            self.assertTrue(localization.check_translation(base, {"entry": ("string", {"text": value})}, "fr"))
        self.assertEqual([], localization.check_translation(base, {"entry": ("string", {"text": "%2$d éléments : ouvrir %1$s"})}, "fr"))

    def test_each_plural_form_needs_arguments_and_other_fallback(self):
        base = {"count": ("plurals", {"one": "%1$d item", "other": "%1$d items"})}
        for forms in ({"one": "%1$d élément"}, {"one": "%1$d élément", "other": "éléments"}):
            self.assertTrue(localization.check_translation(base, {"count": ("plurals", forms)}, "fr"))

    def test_language_must_have_real_and_complete_translations(self):
        base = {"welcome": ("string", {"text": "Welcome"})}
        self.assertTrue(localization.check_translation(base, {}, "de"))
        self.assertTrue(localization.check_translation(base, base, "de"))
        self.assertEqual([], localization.check_translation(base, {"welcome": ("string", {"text": "Willkommen"})}, "de"))


if __name__ == "__main__":
    unittest.main()
