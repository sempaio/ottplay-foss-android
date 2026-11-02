#!/usr/bin/env python3
"""Check packaged translations, format arguments and ottplay-foss language parity."""
import argparse
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

FOSS_TAGS = dict(zip(
    "eng arm bel bul fra ger gre heb hun ita lat lit pol por rou rus spa tur ukr uzb".split(),
    "en hy be bg fr de el he hu it lv lt pl pt ro ru es tr uk uz".split(),
))
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
FORMATS = re.compile(r"%(?:[1-9]\d*\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")


def resources(directory):
    result = {}
    for path in sorted(directory.glob("*.xml")):
        for item in ET.parse(path).getroot():
            if item.tag not in ("string", "plurals") or item.get("translatable") == "false":
                continue
            key = item.get("name")
            if key in result:
                raise ValueError(f"Duplicate resource {key} in {directory}")
            values = {child.get("quantity"): "".join(child.itertext()) for child in item} if item.tag == "plurals" else {"text": "".join(item.itertext())}
            result[key] = (item.tag, values)
    return result


def check_translation(base, translated, tag):
    errors = []
    for key in sorted(base.keys() - translated.keys()):
        errors.append(f"{tag}: missing {key}")
    for key in sorted(translated.keys() - base.keys()):
        errors.append(f"{tag}: unknown {key}")
    for key in base.keys() & translated.keys():
        kind, original = base[key]
        translated_kind, values = translated[key]
        if kind != translated_kind:
            errors.append(f"{tag}/{key}: resource type changed")
            continue
        if kind == "plurals" and "other" not in values:
            errors.append(f"{tag}/{key}: missing plural other")
        expected = Counter(FORMATS.findall(next(iter(original.values()))))
        for quantity, text in values.items():
            if not text.strip():
                errors.append(f"{tag}/{key}/{quantity}: empty translation")
            if Counter(FORMATS.findall(text)) != expected:
                errors.append(f"{tag}/{key}/{quantity}: format arguments differ")
    if tag != "en" and base and translated == base:
        errors.append(f"{tag}: English copies are not a translation")
    return errors


def validate(root, foss_root=None):
    res = root / "app/src/main/res"
    configured = [item.get(ANDROID_NAME) for item in ET.parse(res / "xml/locales_config.xml").getroot()]
    errors = []
    required = set(FOSS_TAGS.values())
    if len(configured) != len(set(configured)):
        errors.append("Duplicate languages in locales_config.xml")
    if not required <= set(configured):
        errors.append(f"Missing FOSS languages: {sorted(required - set(configured))}")
    kotlin = (root / "app/src/main/java/play/ott/nativeapp/i18n/AppLanguages.kt").read_text()
    picker = set(re.findall(r'AppLanguage\("([a-z-]+)"', kotlin))
    if picker != set(configured):
        errors.append("Picker languages differ from Android locale configuration")
    if foss_root:
        source = (foss_root / "src/index.ts").read_text()
        match = re.search(r"function selectLang\(\).*?var langCodes = \[(.*?)\]", source, re.S)
        if not match:
            errors.append("Cannot read ottplay-foss selectLang language list")
        else:
            codes = set(re.findall(r'"_([a-z]+)"', match.group(1)))
            unknown = codes - FOSS_TAGS.keys()
            if unknown:
                errors.append(f"New FOSS languages need an Android mapping: {sorted(unknown)}")
    base = resources(res / "values")
    for tag in configured:
        if tag == "en":
            continue
        folder = "iw" if tag == "he" else tag
        errors.extend(check_translation(base, resources(res / ("values-" + folder)), tag))
    return errors, len(base), len(configured)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--foss-root", type=Path)
    args = parser.parse_args()
    errors, count, locales = validate(args.root, args.foss_root)
    if errors:
        print("\n".join(errors))
        raise SystemExit(1)
    print(f"Localization PASS: {locales} languages, {count} resources per language; placeholders and picker match.")
