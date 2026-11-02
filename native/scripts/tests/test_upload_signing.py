import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("upload_signing", ROOT / "scripts/configure-upload-signing.py")
upload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upload)

CERTIFICATE = b"synthetic certificate fixture; not an actual signing key"
CERT_SHA256 = hashlib.sha256(CERTIFICATE).hexdigest()
KEYSTORE = b"synthetic keystore fixture; not an actual private key"
PASSWORD = "private-password-marker-never-print"


class UploadSigningTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name).resolve()
        self.keystore = self.directory / "upload.p12"
        self.credentials = self.directory / "upload.json"
        self.calls = []

    def existing_pair(self, **overrides):
        values = dict(OTTPLAY_KEYSTORE=str(self.keystore), OTTPLAY_STORE_PASSWORD=PASSWORD,
                      OTTPLAY_KEY_ALIAS="owner-upload", OTTPLAY_KEY_PASSWORD=PASSWORD,
                      OTTPLAY_EXPECTED_CERT_SHA256=CERT_SHA256) | overrides
        self.keystore.write_bytes(KEYSTORE)
        self.credentials.write_text(json.dumps(values))
        self.keystore.chmod(0o600)
        self.credentials.chmod(0o600)
        return values

    def process(self, arguments, **kwargs):
        self.calls.append((arguments, kwargs))
        output = b""
        if "-genkeypair" in arguments:
            Path(arguments[arguments.index("-keystore") + 1]).write_bytes(KEYSTORE)
        elif "-exportcert" in arguments:
            output = CERTIFICATE
        elif "-printcert" in arguments:
            output = b"Owner: CN=OTT Play Native Upload\nIssuer: CN=OTT Play Native Upload\n"
        elif "-certreq" in arguments:
            output = b"synthetic public CSR"
        elif arguments[:3] == ["gh", "secret", "list"]:
            output = b"[]"
        return subprocess.CompletedProcess(arguments, 0, stdout=output, stderr=b"")

    def test_missing_backups_require_explicit_create_authorization(self):
        with patch.object(upload.subprocess, "run") as process:
            with self.assertRaisesRegex(upload.SetupError, "explicit --create-new"):
                upload.configure(str(self.keystore), str(self.credentials), repository="owner/app")
            process.assert_not_called()
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_incomplete_backup_is_never_overwritten_even_with_create_flag(self):
        for path in (self.keystore, self.credentials):
            path.write_bytes(b"irreplaceable existing backup")
            path.chmod(0o600)
            with patch.object(upload.subprocess, "run") as process:
                with self.assertRaisesRegex(upload.SetupError, "Incomplete"):
                    upload.configure(str(self.keystore), str(self.credentials), create_new=True)
                process.assert_not_called()
            self.assertEqual(path.read_bytes(), b"irreplaceable existing backup")
            path.unlink()

    def test_explicit_create_cannot_rotate_an_existing_identity(self):
        self.existing_pair()
        before = (self.keystore.read_bytes(), self.credentials.read_bytes())
        with patch.object(upload.subprocess, "run") as process:
            with self.assertRaisesRegex(upload.SetupError, "already exist"):
                upload.configure(str(self.keystore), str(self.credentials), create_new=True)
            process.assert_not_called()
        self.assertEqual(before, (self.keystore.read_bytes(), self.credentials.read_bytes()))

    def test_reuse_validates_certificate_and_private_key_without_rewriting_backups(self):
        self.existing_pair()
        before = [(path.read_bytes(), path.stat().st_mtime_ns) for path in (self.keystore, self.credentials)]
        with patch.object(upload.subprocess, "run", side_effect=self.process):
            self.assertEqual(upload.configure(str(self.keystore), str(self.credentials)), CERT_SHA256)
        self.assertEqual(before, [(path.read_bytes(), path.stat().st_mtime_ns) for path in (self.keystore, self.credentials)])
        self.assertTrue(any("-certreq" in arguments for arguments, _ in self.calls))
        self.assertFalse(any("-genkeypair" in arguments for arguments, _ in self.calls))

    def test_paths_inside_repository_relative_paths_and_symlinks_are_rejected(self):
        for path in ("relative.p12", str(ROOT / "inside.p12")):
            with self.subTest(path=path), self.assertRaises(upload.SetupError):
                upload.configure(path, str(self.credentials), create_new=True)
        self.existing_pair()
        symlink = self.directory / "linked.p12"
        symlink.symlink_to(self.keystore)
        with self.assertRaisesRegex(upload.SetupError, "symbolic links"):
            upload.configure(str(symlink), str(self.credentials))

    def test_group_readable_backups_are_rejected(self):
        self.existing_pair()
        self.credentials.chmod(0o640)
        with self.assertRaisesRegex(upload.SetupError, "owner-only"):
            upload.configure(str(self.keystore), str(self.credentials))
        self.assertEqual(self.credentials.stat().st_mode & 0o777, 0o640)

    def test_missing_or_mismatched_expected_certificate_cannot_upload_secrets(self):
        for expected in ("", "0" * 64):
            self.existing_pair(OTTPLAY_EXPECTED_CERT_SHA256=expected)
            with patch.object(upload.subprocess, "run", side_effect=self.process):
                with self.assertRaises(upload.SetupError):
                    upload.configure(str(self.keystore), str(self.credentials), repository="owner/app")
            self.assertFalse(any(arguments[:3] == ["gh", "secret", "set"] for arguments, _ in self.calls))

    def test_debug_and_preview_subjects_are_rejected_even_with_an_upload_alias(self):
        self.existing_pair()
        for subject in ("Android Debug", "OTT Play Native Preview"):
            def process(arguments, **kwargs):
                if "-printcert" in arguments:
                    return subprocess.CompletedProcess(arguments, 0, stdout=("Owner: CN=" + subject + "\n").encode())
                return self.process(arguments, **kwargs)
            with self.subTest(subject=subject), patch.object(upload.subprocess, "run", side_effect=process):
                with self.assertRaisesRegex(upload.SetupError, "Debug and preview"):
                    upload.configure(str(self.keystore), str(self.credentials))
        with patch.object(upload, "PREVIEW_CERT_SHA256", CERT_SHA256), patch.object(upload.subprocess, "run", side_effect=self.process):
            with self.assertRaisesRegex(upload.SetupError, "preview certificate"):
                upload.configure(str(self.keystore), str(self.credentials))

    def test_invalid_certificate_or_private_key_password_does_not_leak_diagnostics(self):
        self.existing_pair()
        for failing_command in ("-printcert", "-certreq"):
            def process(arguments, **kwargs):
                if failing_command in arguments:
                    raise subprocess.CalledProcessError(1, arguments, output=PASSWORD.encode(), stderr=PASSWORD.encode())
                return self.process(arguments, **kwargs)
            output = io.StringIO()
            with patch.object(upload.subprocess, "run", side_effect=process), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                status = upload.main(["--keystore", str(self.keystore), "--credentials", str(self.credentials)])
            self.assertEqual(status, 1)
            self.assertNotIn(PASSWORD, output.getvalue())
            self.assertNotIn("Traceback", output.getvalue())
            self.assertEqual(self.keystore.read_bytes(), KEYSTORE)

    def test_explicit_creation_stores_owner_only_backups_and_uses_password_environment(self):
        with patch.object(upload.subprocess, "run", side_effect=self.process), patch.object(upload.secrets, "token_urlsafe", return_value=PASSWORD):
            digest = upload.configure(str(self.keystore), str(self.credentials), create_new=True)
        self.assertEqual(digest, CERT_SHA256)
        values = json.loads(self.credentials.read_text())
        self.assertEqual(values["OTTPLAY_KEYSTORE"], str(self.keystore))
        self.assertEqual(values["OTTPLAY_EXPECTED_CERT_SHA256"], CERT_SHA256)
        self.assertEqual(values["OTTPLAY_STORE_PASSWORD"], PASSWORD)
        self.assertEqual(self.keystore.read_bytes(), KEYSTORE)
        self.assertEqual(self.keystore.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.credentials.stat().st_mode & 0o777, 0o600)
        self.assertEqual(len(list(self.directory.iterdir())), 2)
        generation = next(arguments for arguments, _ in self.calls if "-genkeypair" in arguments)
        self.assertIn("CN=OTT Play Native Upload", generation)
        self.assertEqual(generation[generation.index("-keyalg") + 1], "RSA")
        self.assertEqual(generation[generation.index("-keysize") + 1], "3072")
        for arguments, kwargs in self.calls:
            self.assertNotIn(PASSWORD, " ".join(arguments))
            self.assertEqual(kwargs["env"]["OTTPLAY_STORE_PASSWORD"], PASSWORD)
            self.assertEqual(kwargs["stderr"], subprocess.PIPE)

    def test_existing_remote_secret_blocks_creation_and_any_overwrite(self):
        def process(arguments, **kwargs):
            self.calls.append((arguments, kwargs))
            return subprocess.CompletedProcess(arguments, 0, stdout=b'[{"name":"RELEASE_SIGNING_KEY_ALIAS"}]')
        with patch.object(upload.subprocess, "run", side_effect=process):
            with self.assertRaisesRegex(upload.SetupError, "refusing to overwrite"):
                upload.configure(str(self.keystore), str(self.credentials), create_new=True, repository="owner/app")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_github_upload_uses_stdin_only_and_does_not_publish(self):
        self.existing_pair()
        output = io.StringIO()
        with patch.object(upload.subprocess, "run", side_effect=self.process), contextlib.redirect_stdout(output):
            status = upload.main(["--keystore", str(self.keystore), "--credentials", str(self.credentials), "--repository", "owner/app"])
        self.assertEqual(status, 0)
        writes = [(arguments, kwargs) for arguments, kwargs in self.calls if arguments[:3] == ["gh", "secret", "set"]]
        self.assertEqual({arguments[3] for arguments, _ in writes}, {"RELEASE_SIGNING_" + suffix for suffix in upload.SECRET_SUFFIXES})
        for arguments, kwargs in writes:
            self.assertNotIn(PASSWORD, " ".join(arguments))
            self.assertIsInstance(kwargs["input"], bytes)
        self.assertEqual(writes[0][1]["input"], base64.b64encode(KEYSTORE))
        self.assertNotIn(PASSWORD, output.getvalue())
        self.assertNotIn(base64.b64encode(KEYSTORE).decode(), output.getvalue())
        self.assertFalse(any("release" in arguments or "publish" in arguments for arguments, _ in self.calls))

    def test_partial_remote_failure_keeps_local_backups_and_redacts_stderr(self):
        self.existing_pair()
        before = self.credentials.read_bytes()
        def process(arguments, **kwargs):
            if "RELEASE_SIGNING_STORE_PASSWORD" in arguments:
                raise subprocess.CalledProcessError(1, arguments, stderr=PASSWORD.encode())
            return self.process(arguments, **kwargs)
        output = io.StringIO()
        with patch.object(upload.subprocess, "run", side_effect=process), contextlib.redirect_stderr(output):
            status = upload.main(["--keystore", str(self.keystore), "--credentials", str(self.credentials), "--repository", "owner/app"])
        self.assertEqual(status, 1)
        self.assertNotIn(PASSWORD, output.getvalue())
        self.assertIn("local backups were preserved", output.getvalue())
        self.assertEqual(self.credentials.read_bytes(), before)
        self.assertEqual(self.keystore.read_bytes(), KEYSTORE)

    def test_backup_installation_race_preserves_recoverable_generated_identity(self):
        original_link = os.link
        def raced_link(source, destination):
            if destination == self.credentials:
                self.credentials.write_bytes(b"concurrently created backup")
                self.credentials.chmod(0o600)
            return original_link(source, destination)
        with patch.object(upload.subprocess, "run", side_effect=self.process), patch.object(upload.os, "link", side_effect=raced_link):
            with self.assertRaisesRegex(upload.SetupError, "Recovery key and credentials preserved"):
                upload.configure(str(self.keystore), str(self.credentials), create_new=True)
        self.assertEqual(self.credentials.read_bytes(), b"concurrently created backup")
        recovery_files = list(self.directory.glob(".ottplay-upload-*/recovery-credentials.json"))
        self.assertEqual(len(recovery_files), 1)
        recovery = json.loads(recovery_files[0].read_text())
        self.assertEqual(Path(recovery["OTTPLAY_KEYSTORE"]).read_bytes(), KEYSTORE)
        self.assertEqual(recovery["OTTPLAY_EXPECTED_CERT_SHA256"], CERT_SHA256)
        self.assertTrue(recovery["OTTPLAY_STORE_PASSWORD"])


if __name__ == "__main__":
    unittest.main()
