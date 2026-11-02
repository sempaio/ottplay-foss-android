#!/usr/bin/env python3
"""Decode only the selected channel's configured signing identity on a CI runner."""
import base64
import os
from pathlib import Path
import sys

channel = os.environ["RELEASE_CHANNEL"]
if channel not in ("preview", "production"):
    sys.exit("Unknown release channel")
prefix = "PREVIEW_SIGNING_" if channel == "preview" else "RELEASE_SIGNING_"
fields = ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "CERT_SHA256")
missing = [prefix + field for field in fields if not os.environ.get(prefix + field)]
if missing:
    sys.exit("Signing is not configured; release is blocked. Missing secret names: " + ", ".join(missing))
values = {field: os.environ[prefix + field] for field in fields}
if channel == "production" and values["CERT_SHA256"].lower() == os.environ.get("PREVIEW_SIGNING_CERT_SHA256", "").lower():
    sys.exit("Production signing must use a different certificate from preview")
if any("\n" in values[field] or "\r" in values[field] for field in fields if field != "KEYSTORE_BASE64"):
    sys.exit("Signing values must be single-line values")
os.umask(0o077)
destination = Path(os.environ["RUNNER_TEMP"]) / "ottplay-release-signing"
destination.mkdir(mode=0o700, exist_ok=True)
keystore = destination / "signing.keystore"
try:
    contents = base64.b64decode("".join(values["KEYSTORE_BASE64"].split()), validate=True)
except ValueError:
    sys.exit("Configured signing keystore is not valid base64")
if not 128 <= len(contents) <= 1024 * 1024:
    sys.exit("Configured signing keystore has an unexpected size")
keystore.write_bytes(contents)
with open(os.environ["GITHUB_ENV"], "a") as output:
    output.write(f"OTTPLAY_KEYSTORE={keystore}\n")
    output.write(f"OTTPLAY_SIGNING_VARIANT={'preview' if channel == 'preview' else 'release'}\n")
    for source, target in (("STORE_PASSWORD", "STORE_PASSWORD"), ("KEY_ALIAS", "KEY_ALIAS"),
                           ("KEY_PASSWORD", "KEY_PASSWORD"), ("CERT_SHA256", "EXPECTED_CERT_SHA256")):
        output.write(f"OTTPLAY_{target}={values[source]}\n")
print(f"Configured {channel} signing identity loaded into private runner storage.")
