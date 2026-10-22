#!/usr/bin/env python3
"""Validate the actual APK with Android build tools; never use account credentials."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
args = parser.parse_args()
apk = args.apk.resolve()
assert apk.is_file() and apk.stat().st_size > 0, 'APK missing or empty'
sdk = Path(os.environ['ANDROID_HOME'])
tools = sdk / 'build-tools/36.0.0'
badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
for marker in ["name='br.starlink.iptv'", "application-label:'STARLINK IPTV'", "name='android.permission.INTERNET'", "launchable-activity:", "leanback-launchable-activity:"]:
    assert marker in badging, f'Missing APK marker: {marker}'
subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], check=True)
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    assert b'https://panel.taila5c5c2.ts.net' in dex, 'M81 server missing from executable'
    assert b'/app/starlink-iptv/playlist.m3u' in dex, 'STARLINK playlist missing from executable'
    assert b'fixture-password' not in dex, 'Test fixture packaged into production executable'
print(f'Validated: {apk.name}; bytes={apk.stat().st_size}; SHA256={hashlib.sha256(apk.read_bytes()).hexdigest()}')
print(badging)
