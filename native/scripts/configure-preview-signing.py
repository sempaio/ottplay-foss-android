#!/usr/bin/env python3
"""Create/reuse an explicitly requested PREVIEW identity; optionally configure GH secrets."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--keystore", required=True, help="Absolute preview-only backup path, outside Git")
parser.add_argument("--credentials", required=True, help="Absolute owner-only JSON backup path, outside Git")
parser.add_argument("--repository", help="Explicit repository to configure via gh secret set")
args = parser.parse_args()
keystore = Path(args.keystore).expanduser()
credentials = Path(args.credentials).expanduser()
root = Path(__file__).resolve().parents[1]
if not keystore.is_absolute() or not credentials.is_absolute():
    sys.exit("Both backup paths must be absolute and outside this repository")
keystore, credentials = keystore.resolve(), credentials.resolve()
if keystore == credentials or keystore.is_relative_to(root) or credentials.is_relative_to(root):
    sys.exit("Both backup paths must be absolute and outside this repository")
os.umask(0o077)
if keystore.exists() != credentials.exists():
    sys.exit("Incomplete existing preview backup; refusing to overwrite or rotate the signing key")
if keystore.exists():
    if keystore.stat().st_mode & 0o077 or credentials.stat().st_mode & 0o077:
        sys.exit("Preview backups must have owner-only permissions (chmod 600)")
    values = json.loads(credentials.read_text())
    if Path(values["OTTPLAY_KEYSTORE"]) != keystore:
        sys.exit("Credentials refer to a different keystore; refusing to rotate")
else:
    password = secrets.token_urlsafe(36)
    values = dict(OTTPLAY_KEYSTORE=str(keystore), OTTPLAY_STORE_PASSWORD=password,
                  OTTPLAY_KEY_ALIAS="ottplay-preview", OTTPLAY_KEY_PASSWORD=password)
    keystore.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    credentials.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    env = os.environ | values
    subprocess.run(["keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
                    "-storepass:env", "OTTPLAY_STORE_PASSWORD", "-keypass:env", "OTTPLAY_KEY_PASSWORD",
                    "-alias", "ottplay-preview", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                    "-dname", "CN=OTT Play Native Preview, O=Preview Builds"], env=env, check=True, stdout=subprocess.DEVNULL)
    os.chmod(keystore, 0o600)
env = os.environ | values
certificate = subprocess.run(["keytool", "-exportcert", "-keystore", str(keystore), "-storepass:env", "OTTPLAY_STORE_PASSWORD",
                              "-alias", values["OTTPLAY_KEY_ALIAS"]], env=env, check=True, stdout=subprocess.PIPE).stdout
digest = hashlib.sha256(certificate).hexdigest()
if values.get("OTTPLAY_EXPECTED_CERT_SHA256", digest) != digest:
    sys.exit("Existing certificate does not match its recorded fingerprint")
values["OTTPLAY_EXPECTED_CERT_SHA256"] = digest
if not credentials.exists():
    with credentials.open("x") as output:
        json.dump(values, output, indent=2)
        output.write("\n")
    os.chmod(credentials, 0o600)
if args.repository:
    secret_values = dict(KEYSTORE_BASE64=base64.b64encode(keystore.read_bytes()).decode(),
                         STORE_PASSWORD=values["OTTPLAY_STORE_PASSWORD"], KEY_ALIAS=values["OTTPLAY_KEY_ALIAS"],
                         KEY_PASSWORD=values["OTTPLAY_KEY_PASSWORD"], CERT_SHA256=digest)
    for name, value in secret_values.items():
        subprocess.run(["gh", "secret", "set", "PREVIEW_SIGNING_" + name, "--repo", args.repository],
                       input=value, text=True, check=True, stdout=subprocess.DEVNULL)
print("Preview-only signing configured. Certificate SHA-256: " + digest)
print("Backup paths: " + str(keystore) + " ; " + str(credentials))
