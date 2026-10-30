#!/usr/bin/env python3
"""Check store text, contact fields and PNG headers; never create or alter screenshots."""
import argparse
import json
from pathlib import Path
import re
import struct
from urllib.parse import urlsplit
import zlib


ROOT = Path(__file__).resolve().parents[1]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ARTWORK = {
    "app/src/main/res/drawable-xhdpi/tv_banner.png": (320, 180),
    "store/assets/icon-512.png": (512, 512),
    "store/assets/feature-graphic-1024x500.png": (1024, 500),
    "store/assets/tv-banner-1280x720.png": (1280, 720),
}
TEXT_LIMITS = {"title.txt": 30, "short-description.txt": 80, "full-description.txt": 4000}


class Report:
    def __init__(self):
        self.errors = []
        self.missing_screenshots = []
        self.checked = []


def png_size(path):
    """Read and validate only the PNG signature/IHDR, not the compressed pixel data."""
    with path.open("rb") as stream:
        header = stream.read(33)
    if len(header) != 33 or header[:8] != PNG_SIGNATURE:
        raise ValueError("missing or truncated PNG signature/IHDR")
    if struct.unpack(">I", header[8:12])[0] != 13 or header[12:16] != b"IHDR":
        raise ValueError("PNG must start with a 13-byte IHDR")
    if zlib.crc32(header[12:29]) & 0xffffffff != struct.unpack(">I", header[29:33])[0]:
        raise ValueError("invalid PNG IHDR checksum")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", header[16:29])
    depths = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
    if not 0 < width <= 0x7fffffff or not 0 < height <= 0x7fffffff:
        raise ValueError("invalid PNG dimensions")
    if depth not in depths.get(color, set()) or compression != 0 or filtering != 0 or interlace not in (0, 1):
        raise ValueError("invalid PNG IHDR encoding")
    return width, height


def screenshot_errors(width, height, device):
    errors = []
    if not (320 <= width <= 3840 and 320 <= height <= 3840):
        errors.append("each side must be between 320 and 3840 pixels")
    if max(width, height) > 2 * min(width, height):
        errors.append("the longer side must not exceed twice the shorter side")
    if device == "tv" and width * 9 != height * 16:
        errors.append("TV screenshots must have landscape 16:9 dimensions")
    return errors


def validate(root=ROOT, require_screenshots=False):
    root = Path(root)
    report = Report()
    for relative, expected in ARTWORK.items():
        try:
            actual = png_size(root / relative)
            if actual != expected:
                raise ValueError(f"expected {expected[0]}x{expected[1]}, found {actual[0]}x{actual[1]}")
            report.checked.append(f"{relative}: {actual[0]}x{actual[1]}")
        except (OSError, ValueError) as error:
            report.errors.append(f"{relative}: {error}")

    store = root / "store"
    locales = sorted(path for path in store.iterdir() if path.is_dir() and re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", path.name)) if store.is_dir() else []
    if not locales:
        report.errors.append("store: no localized listing directories found")
    for locale in locales:
        for filename, limit in TEXT_LIMITS.items():
            relative = f"store/{locale.name}/{filename}"
            try:
                # Ignore the text file's terminating line break, not spaces or embedded newlines.
                value = (locale / filename).read_text(encoding="utf-8").rstrip("\r\n")
                if not value.strip():
                    raise ValueError("listing field is empty")
                if filename != "full-description.txt" and ("\n" in value or "\r" in value):
                    raise ValueError("title and short description must each be one line")
                if len(value) > limit:
                    raise ValueError(f"{len(value)} characters exceeds the {limit}-character limit")
                report.checked.append(f"{relative}: {len(value)}/{limit} characters")
            except (OSError, UnicodeError, ValueError) as error:
                report.errors.append(f"{relative}: {error}")

    try:
        contact = json.loads((store / "contact.json").read_text(encoding="utf-8"))
        if not isinstance(contact, dict):
            raise ValueError("must be a JSON object")
        publisher = contact.get("publisher")
        if not isinstance(publisher, str) or not publisher.strip():
            raise ValueError("publisher must be nonempty")
        email = contact.get("email")
        if not isinstance(email, str) or not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
            raise ValueError("email must be a usable contact address")
        policy = contact.get("privacyPolicyUrl")
        if not isinstance(policy, str) or any(character.isspace() for character in policy):
            raise ValueError("privacyPolicyUrl must be an HTTPS URL without whitespace")
        parsed = urlsplit(policy)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None or parsed.password is not None:
            raise ValueError("privacyPolicyUrl must use HTTPS and a host, without URL credentials")
        # Reading .port also rejects a malformed or out-of-range port.
        parsed.port
        report.checked.append("store/contact.json: publisher, email and HTTPS policy URL")
    except (OSError, UnicodeError, ValueError) as error:
        report.errors.append(f"store/contact.json: {error}")

    for device, minimum in (("phone", 2), ("tv", 1)):
        directory = store / "screenshots" / device
        count = 0
        if directory.exists() and not directory.is_dir():
            report.errors.append(f"store/screenshots/{device}: expected a directory")
        elif directory.is_dir():
            for path in sorted(directory.iterdir()):
                if path.name.startswith(".") or path.suffix.lower() in (".md", ".txt", ".json"):
                    continue
                relative = path.relative_to(root)
                if not path.is_file() or path.suffix.lower() != ".png":
                    report.errors.append(f"{relative}: screenshots must be PNG files")
                    continue
                try:
                    width, height = png_size(path)
                    issues = screenshot_errors(width, height, device)
                    if issues:
                        raise ValueError(f"{width}x{height}: " + "; ".join(issues))
                    count += 1
                    report.checked.append(f"{relative}: {width}x{height}")
                except (OSError, ValueError) as error:
                    report.errors.append(f"{relative}: {error}")
        if count < minimum:
            missing = f"store/screenshots/{device}: need at least {minimum} valid PNG screenshot(s), found {count}"
            report.missing_screenshots.append(missing)
            if require_screenshots:
                report.errors.append(missing)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-screenshots", action="store_true", help="Fail if fewer than two phone or one TV screenshot is present")
    args = parser.parse_args(argv)
    report = validate(require_screenshots=args.require_screenshots)
    for message in report.checked:
        print(f"OK: {message}")
    for message in report.missing_screenshots:
        print(f"MISSING: {message}")
    for message in report.errors:
        print(f"ERROR: {message}")
    print(f"Checked {len(report.checked)} item(s); {len(report.errors)} error(s); {len(report.missing_screenshots)} screenshot requirement(s) missing.")
    print("Checks cover PNG headers and listing fields only; image decoding, screenshot provenance and live URL accessibility require separate verification.")
    return 1 if report.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
