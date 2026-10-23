#!/usr/bin/env python3
"""Bind immutable, locally signed APKs to the exact native sources used by CI.

Fallback until dedicated signing secrets can be provisioned; never generates a new key.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
receipt = root / 'starlink/layout-build.json'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_digest():
    paths = subprocess.check_output(['git', 'ls-files', '-z', '--', 'native'], cwd=root).decode().split('\0')
    value = hashlib.sha256()
    for name in sorted(filter(None, paths)):
        path = root / name
        value.update(name.encode() + b'\0' + path.read_bytes() + b'\0')
    return value.hexdigest()

apks = ['STARLINK-IPTV-layout.apk', 'STARLINK-IPTV-layout-androidTest.apk']
actual = {'native_sources_sha256': source_digest(), 'apks': {name: digest(root / 'device-apks' / name) for name in apks}}
if len(sys.argv) == 2 and sys.argv[1] == 'record':
    receipt.write_text(json.dumps(actual, indent=2) + '\n')
else:
    expected = json.loads(receipt.read_text())
    assert actual == expected, 'Pinned APK does not match native sources. Rebuild with the stable key or configure STARLINK_LAYOUT signing secrets.'
print('Signed layout APKs and native source fingerprint verified.')
