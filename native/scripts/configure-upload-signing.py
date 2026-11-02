#!/usr/bin/env python3
"""Validate an existing upload identity, or create one with explicit --create-new consent.

Backups stay outside this repository. Optional GitHub secret setup does not enroll
the key in Play App Signing, upload an app, or publish a Google Play release.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ("OTTPLAY_KEYSTORE", "OTTPLAY_STORE_PASSWORD", "OTTPLAY_KEY_ALIAS",
          "OTTPLAY_KEY_PASSWORD", "OTTPLAY_EXPECTED_CERT_SHA256")
SECRET_SUFFIXES = ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "CERT_SHA256")
# Public identity of the separate preview channel; never use it as an upload key.
PREVIEW_CERT_SHA256 = "2021e3c927fff7c42daf395beacbf0ef738c6d878a827091afb48ddaa32c4dd8"


class SetupError(Exception):
    """A message safe to show without exposing command output or credential values."""


def command(arguments, *, env=None, data=None, failure):
    try:
        result = subprocess.run(arguments, env=env, input=data, check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return result.stdout
    except (OSError, subprocess.CalledProcessError):
        # keytool and gh can echo sensitive input in diagnostics. Never forward it.
        raise SetupError(failure) from None


def backup_paths(keystore, credentials):
    paths = [Path(value).expanduser() for value in (keystore, credentials)]
    if any(not path.is_absolute() or path.is_symlink() for path in paths):
        raise SetupError("Backup paths must be absolute, outside this repository, and not symbolic links")
    paths = [path.resolve() for path in paths]
    if paths[0] == paths[1] or any(path.is_relative_to(ROOT) for path in paths):
        raise SetupError("Use two different backup paths outside this repository")
    for path in paths:
        if path.exists():
            details = path.stat()
            if not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid() or details.st_mode & 0o077:
                raise SetupError("Existing backups must be regular files owned by this user with owner-only permissions (chmod 600)")
    if paths[0].exists() != paths[1].exists():
        raise SetupError("Incomplete existing backup; refusing overwrite or signing-key rotation")
    return paths


def load_credentials(keystore, credentials):
    try:
        raw = json.loads(credentials.read_text())
        if not isinstance(raw, dict) or any(not isinstance(raw.get(name), str) or not raw[name] for name in FIELDS):
            raise ValueError()
        values = {name: raw[name] for name in FIELDS}
        if not Path(values["OTTPLAY_KEYSTORE"]).is_absolute() or Path(values["OTTPLAY_KEYSTORE"]).resolve() != keystore:
            raise ValueError()
        if not re.fullmatch(r"[0-9a-fA-F]{64}", values["OTTPLAY_EXPECTED_CERT_SHA256"]):
            raise ValueError()
    except (ValueError, OSError, TypeError):
        raise SetupError("Credentials are invalid, incomplete, or refer to a different keystore; existing backups were preserved") from None
    return values


def validate_certificate(values):
    env = os.environ | values
    alias = values["OTTPLAY_KEY_ALIAS"]
    if re.search(r"debug|preview", alias, re.IGNORECASE):
        raise SetupError("Debug and preview identities cannot be used for upload signing")
    keytool = ["keytool", "-J-Duser.language=en", "-J-Duser.country=US"]
    store = ["-keystore", values["OTTPLAY_KEYSTORE"], "-storepass:env", "OTTPLAY_STORE_PASSWORD", "-alias", alias]
    certificate = command(keytool + ["-exportcert"] + store, env=env,
                          failure="Cannot read the upload certificate; verify the existing backup and credentials")
    digest = hashlib.sha256(certificate).hexdigest()
    expected = values.get("OTTPLAY_EXPECTED_CERT_SHA256")
    if expected is not None and digest != expected.lower():
        raise SetupError("Certificate does not match the expected SHA-256; refusing replacement or rotation")
    if digest == PREVIEW_CERT_SHA256:
        raise SetupError("The preview certificate cannot be used for upload signing")
    description = command(keytool + ["-printcert", "-v"], env=env, data=certificate,
                          failure="The upload certificate is invalid").decode("utf-8", errors="replace")
    owner = re.search(r"^Owner:\s*(.+)$", description, re.MULTILINE)
    if owner is None:
        raise SetupError("The upload certificate does not have a recognizable subject")
    if re.search(r"\b(debug|preview)\b", owner[1], re.IGNORECASE):
        raise SetupError("Debug and preview certificates cannot be used for upload signing")
    # Exporting a certificate alone does not establish access to its private key.
    # Creating a CSR validates the private-key password without modifying the backup.
    command(keytool + ["-certreq"] + store + ["-keypass:env", "OTTPLAY_KEY_PASSWORD"], env=env,
            failure="Cannot access the upload private key; existing backups were preserved")
    return digest


def require_empty_repository_signing(repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise SetupError("Use an explicit GitHub repository in owner/name form")
    response = command(["gh", "secret", "list", "--repo", repository, "--json", "name"],
                       failure="Cannot inspect repository secrets; no signing secrets were changed")
    try:
        existing = {item["name"] for item in json.loads(response)}
    except (ValueError, TypeError, KeyError):
        raise SetupError("Cannot verify the repository's existing signing configuration") from None
    if existing.intersection("RELEASE_SIGNING_" + suffix for suffix in SECRET_SUFFIXES):
        raise SetupError("Release signing secrets already exist; refusing to overwrite or rotate them")


def create_identity(keystore, credentials):
    password = secrets.token_urlsafe(48)
    values = dict(OTTPLAY_KEYSTORE=str(keystore), OTTPLAY_STORE_PASSWORD=password,
                  OTTPLAY_KEY_ALIAS="ottplay-upload", OTTPLAY_KEY_PASSWORD=password)
    keystore.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    credentials.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Stage files on the same filesystem as each destination; link() installs
    # without overwriting an existing backup, even if another process creates it.
    directory = Path(tempfile.mkdtemp(prefix=".ottplay-upload-", dir=keystore.parent))
    backup_directory = None
    preserve_staging = False
    try:
        staged_keystore = directory / "upload.p12"
        staged_values = values | {"OTTPLAY_KEYSTORE": str(staged_keystore)}
        command(["keytool", "-genkeypair", "-keystore", str(staged_keystore), "-storetype", "PKCS12",
                 "-storepass:env", "OTTPLAY_STORE_PASSWORD", "-keypass:env", "OTTPLAY_KEY_PASSWORD",
                 "-alias", "ottplay-upload", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                 "-dname", "CN=OTT Play Native Upload"], env=os.environ | staged_values,
                failure="Upload-key generation failed; existing backups were not replaced")
        os.chmod(staged_keystore, 0o600)
        values["OTTPLAY_EXPECTED_CERT_SHA256"] = validate_certificate(staged_values)
        # Retain a usable recovery pair if installation loses a race or fails mid-way.
        recovery = values | {"OTTPLAY_KEYSTORE": str(staged_keystore)}
        (directory / "recovery-credentials.json").write_text(json.dumps(recovery, indent=2) + "\n")
        os.chmod(directory / "recovery-credentials.json", 0o600)
        preserve_staging = True
        backup_directory = Path(tempfile.mkdtemp(prefix=".ottplay-upload-", dir=credentials.parent))
        try:
            staged_credentials = backup_directory / "credentials.json"
            with staged_credentials.open("x") as output:
                json.dump(values, output, indent=2)
                output.write("\n")
            os.chmod(staged_credentials, 0o600)
            try:
                os.link(staged_keystore, keystore)
                os.link(staged_credentials, credentials)
            except OSError:
                # Any already-installed file is a backup and must never be removed.
                raise SetupError("Could not install both backups without overwriting. Recovery key and credentials preserved in " + str(directory)) from None
        except OSError:
            raise SetupError("Could not finish backup installation. Recovery key and credentials preserved in " + str(directory)) from None
        preserve_staging = False
    finally:
        if backup_directory is not None:
            shutil.rmtree(backup_directory)
        if not preserve_staging:
            shutil.rmtree(directory)
    return values


def configure(keystore, credentials, *, create_new=False, repository=None):
    keystore, credentials = backup_paths(keystore, credentials)
    exists = keystore.exists()
    if exists and create_new:
        raise SetupError("Backups already exist; omit --create-new to validate and reuse them without rotation")
    if not exists and not create_new:
        raise SetupError("No existing backup pair; creating an upload key requires explicit --create-new authorization")
    if repository:
        require_empty_repository_signing(repository)
    if exists:
        values = load_credentials(keystore, credentials)
        digest = validate_certificate(values)
    else:
        previous_umask = os.umask(0o077)
        try:
            values = create_identity(keystore, credentials)
        finally:
            os.umask(previous_umask)
        digest = values["OTTPLAY_EXPECTED_CERT_SHA256"]
    if repository:
        secret_values = dict(KEYSTORE_BASE64=base64.b64encode(keystore.read_bytes()),
                             STORE_PASSWORD=values["OTTPLAY_STORE_PASSWORD"].encode(),
                             KEY_ALIAS=values["OTTPLAY_KEY_ALIAS"].encode(),
                             KEY_PASSWORD=values["OTTPLAY_KEY_PASSWORD"].encode(), CERT_SHA256=digest.encode())
        for name, value in secret_values.items():
            command(["gh", "secret", "set", "RELEASE_SIGNING_" + name, "--repo", repository], data=value,
                    failure="Repository signing setup did not finish; local backups were preserved. Inspect existing secrets before retrying")
    return digest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", required=True, help="Absolute keystore backup path outside this repository")
    parser.add_argument("--credentials", required=True, help="Absolute owner-only signing JSON backup path")
    parser.add_argument("--create-new", action="store_true", help="Explicitly authorize a NEW upload identity; never replaces existing backups")
    parser.add_argument("--repository", help="Configure RELEASE_SIGNING_* only when none already exist; does not publish an app")
    args = parser.parse_args(argv)
    try:
        digest = configure(args.keystore, args.credentials, create_new=args.create_new, repository=args.repository)
    except SetupError as error:
        print("Upload signing stopped: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError):
        print("Upload signing stopped: cannot access the supplied backups; preserve existing files and inspect their permissions", file=sys.stderr)
        return 1
    print("Upload signing identity verified. Certificate SHA-256: " + digest)
    print("Local backups preserved. No app was uploaded or published to Google Play.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
