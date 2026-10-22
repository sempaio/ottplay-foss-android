import importlib.util
import contextlib
import hashlib
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
spec = importlib.util.spec_from_file_location("release", ROOT / "scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseBoundariesTest(unittest.TestCase):
    def info(self):
        return dict(channel="preview", versionName="0.2.0-preview", versionCode=2,
                    baseVersion="0.2.0", applicationId="play.ott.foss.nativeapp.preview",
                    tag="v0.2.0-preview.2", commit="a" * 40)

    def preflight(self, tags=(), jobs=None, dirty=False, event="workflow_dispatch"):
        required = ["Unit tests, lint and APKs", "Instrumented tests · phone-api35", "Instrumented tests · androidtv-api36"]
        def command(*args, **kwargs):
            if args[:3] == ("git", "status", "--porcelain"):
                return " M source.kt\n" if dirty else ""
            return json.dumps([[dict(name=tag) for tag in tags]])
        def api(path):
            if path.endswith("/jobs?per_page=100"):
                return {"jobs": jobs if jobs is not None else [dict(name=name, conclusion="success") for name in required]}
            return {"workflow_runs": [dict(id=42, event=event, html_url="https://github.com/example/app/actions/runs/42")]}
        with patch.object(release, "run", side_effect=command), patch.object(release, "gh", side_effect=api), patch.dict(os.environ, {}, clear=True):
            info = self.info()
            release.preflight(info, "example/app")
            return info

    def test_requires_both_real_os_jobs_not_only_green_host(self):
        with self.assertRaisesRegex(ValueError, "No successful host"):
            self.preflight(jobs=[dict(name="Unit tests, lint and APKs", conclusion="success")])

    def test_failed_or_skipped_device_job_cannot_authorize_publish(self):
        for conclusion in ("failure", "skipped", "cancelled"):
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.preflight(jobs=[dict(name="Unit tests, lint and APKs", conclusion="success"),
                                     dict(name="Instrumented tests · phone-api35", conclusion="success"),
                                     dict(name="Instrumented tests · androidtv-api36", conclusion=conclusion)])

    def test_existing_tag_and_reused_preview_code_are_rejected(self):
        for tag in ("v0.2.0-preview.2", "v0.1.0-preview.2", "v0.2.1-preview.1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.preflight(tags=[tag])

    def test_uncommitted_sources_cannot_be_released(self):
        with self.assertRaisesRegex(ValueError, "clean committed"):
            self.preflight(dirty=True)

    def test_prior_preview_and_current_full_matrix_are_accepted(self):
        self.assertIn("validatedRun", self.preflight(tags=["v0.1.0-preview.1"]))

    def test_push_full_matrix_is_accepted_but_pull_request_cannot_authorize_release(self):
        self.assertIn("validatedRun", self.preflight(event="push"))
        with self.assertRaisesRegex(ValueError, "No successful host"):
            self.preflight(event="pull_request")

    def test_production_does_not_fall_back_to_preview_secret_values(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, RELEASE_CHANNEL="production", RUNNER_TEMP=directory,
                       GITHUB_ENV=str(Path(directory) / "env"))
            for key in tuple(env):
                if key.startswith("RELEASE_SIGNING_"):
                    del env[key]
            for field in ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "CERT_SHA256"):
                env["PREVIEW_SIGNING_" + field] = "private-test-marker"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-prepare-signing.py")], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Signing is not configured", result.stderr)
            self.assertNotIn("private-test-marker", result.stdout + result.stderr)
            self.assertFalse((Path(directory) / "env").exists())

    def test_missing_signing_identity_never_builds_unsigned_preview(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "no debug/unsigned fallback"):
            release.signing_environment("preview", None)

    def test_signed_build_verifies_both_formats_and_emits_matching_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            keystore = root / "synthetic.keystore"
            keystore.write_bytes(b"synthetic fixture, not a private key")
            env = dict(OTTPLAY_KEYSTORE=str(keystore), OTTPLAY_STORE_PASSWORD="fixture",
                       OTTPLAY_KEY_ALIAS="fixture", OTTPLAY_KEY_PASSWORD="fixture",
                       OTTPLAY_EXPECTED_CERT_SHA256="b" * 64, ANDROID_HOME=str(root / "sdk"),
                       GITHUB_ACTIONS="true", GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="2")
            calls = []
            def command(*args, **kwargs):
                calls.append(args)
                if args[0] == "./gradlew":
                    for kind, extension in (("apk", "apk"), ("bundle", "aab")):
                        destination = root / f"app/build/outputs/{kind}/preview/app-preview.{extension}"
                        destination.parent.mkdir(parents=True)
                        destination.write_bytes(b"synthetic " + extension.encode())
                elif args[0].endswith("apksigner"):
                    return "Signer #1 certificate SHA-256 digest: " + "b" * 64
                elif args[0].endswith("aapt2"):
                    return "package: name='play.ott.foss.nativeapp.preview' versionCode='2' versionName='0.2.0-preview'"
                elif args[0] == "jarsigner":
                    return "jar verified."
                elif args[0] == "keytool":
                    return "SHA256: " + ":".join(["BB"] * 32)
                return ""
            with patch.object(release, "ROOT", root), patch.object(release, "run", side_effect=command), patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(io.StringIO()):
                release.build(self.info(), None)
            out = root / "build/distributions/v0.2.0-preview.2"
            manifest = json.loads((out / "release-manifest.json").read_text())
            self.assertEqual(manifest["signingCertificateSha256"], "b" * 64)
            self.assertEqual((manifest["buildRunId"], manifest["buildRunAttempt"]), (123, 2))
            self.assertEqual(len(manifest["artifacts"]), 2)
            for line in (out / "SHA256SUMS").read_text().splitlines():
                expected, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256((out / name).read_bytes()).hexdigest(), expected)
            self.assertIn(":app:testDebugUnitTest", calls[0])
            self.assertIn(":app:lintPreview", calls[0])
            self.assertTrue(any(call[0] == "jarsigner" and "-strict" in call for call in calls))

    def test_publish_tag_creation_race_does_not_create_or_overwrite_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "build/distributions/v0.2.0-preview.2"
            out.mkdir(parents=True)
            apk = out / "fixture.apk"
            apk.write_bytes(b"synthetic apk")
            info = self.info()
            manifest = info | {"artifacts": [dict(name=apk.name, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())]}
            (out / "release-manifest.json").write_text(json.dumps(manifest))
            calls = []
            def preflight(current, repository):
                current["validatedRun"] = "https://github.com/example/app/actions/runs/42"
            def command(*args, **kwargs):
                calls.append(args)
                raise subprocess.CalledProcessError(1, args)  # GitHub refuses a concurrently created ref.
            with patch.object(release, "ROOT", root), patch.object(release, "preflight", side_effect=preflight), patch.object(release, "run", side_effect=command):
                with self.assertRaises(subprocess.CalledProcessError):
                    release.publish(info, "example/app")
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][:4], ("gh", "api", "--method", "POST"))
            self.assertNotIn("--force", calls[0])

    def test_modified_artifact_is_rejected_before_tag_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "build/distributions/v0.2.0-preview.2"
            out.mkdir(parents=True)
            (out / "fixture.apk").write_bytes(b"changed")
            info = self.info()
            (out / "release-manifest.json").write_text(json.dumps(info | {"artifacts": [dict(name="fixture.apk", sha256="0" * 64)]}))
            with patch.object(release, "ROOT", root), patch.object(release, "preflight"), patch.object(release, "run") as command:
                with self.assertRaisesRegex(ValueError, "checksum changed"):
                    release.publish(info, "example/app")
                command.assert_not_called()


class StablePromotionTest(unittest.TestCase):
    def setUp(self):
        self.info = ReleaseBoundariesTest().info() | {"worktreeDirty": False}
        self.tag = self.info["tag"]
        self.files = {f"ottplay-native-{self.tag[1:]}.{ext}": b"signed fixture " + ext.encode()
                      for ext in ("apk", "aab")}
        self.manifest = self.info | {"signingCertificateSha256": "b" * 64, "buildRunId": 123,
                                    "buildRunAttempt": 2, "artifacts": [
            {"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            for name, data in self.files.items()]}
        self.build_run = dict(head_sha=self.info["commit"], head_branch="main", event="workflow_dispatch",
                              path=".github/workflows/release.yml", status="completed", conclusion="success", run_attempt=2)
        self.main_sha = self.info["commit"]
        self.tag_sha = self.info["commit"]
        self.extra_asset = False
        self.corrupt_download = False
        self.change_on_recheck = False
        self.commands = []

    def execute(self):
        payload = self.files | {"release-manifest.json": (json.dumps(self.manifest) + "\n").encode()}
        payload["SHA256SUMS"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in payload.items()).encode()
        assets = [dict(id=index, name=name, size=len(data), updated_at="2026-10-17T00:00:00Z",
                       digest="sha256:" + hashlib.sha256(data).hexdigest()) for index, (name, data) in enumerate(payload.items())]
        if self.extra_asset:
            assets.append(dict(id=99, name="unexpected.apk", size=1, updated_at="2026-10-17T00:00:00Z"))
        published = dict(id=7, tag_name=self.tag, draft=False, prerelease=True, assets=assets, body="Original preview notes")

        def api(path):
            if path.endswith("git/ref/heads/main"):
                return {"object": {"type": "commit", "sha": self.main_sha}}
            if "/git/ref/tags/" in path:
                return {"object": {"type": "commit", "sha": self.tag_sha}}
            if path.endswith("actions/runs/123"):
                return self.build_run
            if path.endswith("releases/7") and self.change_on_recheck:
                return published | {"assets": assets[:-1]}
            if "/releases/" in path:
                return published
            self.fail(f"Unexpected API call: {path}")

        def command(*args, **kwargs):
            self.commands.append(args)
            if args[:3] == ("git", "status", "--porcelain"):
                return ""
            if args[:3] in (("gh", "release", "download"), ("gh", "run", "download")):
                destination = Path(args[args.index("--dir") + 1])
                destination.mkdir(parents=True)
                for name, data in payload.items():
                    if self.corrupt_download and args[1] == "release" and name.endswith(".apk"):
                        data += b"tampered"
                    (destination / name).write_bytes(data)
                return ""
            if args[:4] == ("gh", "api", "--method", "PATCH"):
                return "{}"
            self.fail(f"Unexpected command: {args}")

        with patch.object(release, "run", side_effect=command), patch.object(release, "gh", side_effect=api), \
                patch.object(release, "validate_matrix", side_effect=lambda info, repo: info.update(validatedRun="https://github.com/example/app/actions/runs/42")), \
                patch.dict(os.environ, {"OTTPLAY_EXPECTED_CERT_SHA256": "b" * 64}, clear=True), contextlib.redirect_stdout(io.StringIO()):
            release.promote(self.info, "example/app")

    def assert_rejected(self, message):
        with self.assertRaisesRegex(ValueError, message):
            self.execute()
        self.assertFalse(any(command[:4] == ("gh", "api", "--method", "PATCH") for command in self.commands))

    def test_stable_only_changes_release_metadata_after_identical_signed_bytes(self):
        self.execute()
        mutations = [command for command in self.commands if command[:4] == ("gh", "api", "--method", "PATCH")]
        self.assertEqual(len(mutations), 1)
        self.assertIn("prerelease=false", mutations[0])
        self.assertIn("make_latest=true", mutations[0])
        self.assertFalse(any("--tag" in command or "./gradlew" in command or "upload" in command for command in self.commands))

    def test_production_cannot_be_promoted_with_preview_identity(self):
        self.info["channel"] = "production"
        self.assert_rejected("existing preview application")

    def test_main_must_still_be_exact_qualified_source(self):
        self.main_sha = "c" * 40
        self.assert_rejected("current main")

    def test_tag_must_match_exact_qualified_source(self):
        self.tag_sha = "c" * 40
        self.assert_rejected("current source")

    def test_signature_identity_mismatch_is_rejected(self):
        self.manifest["signingCertificateSha256"] = "c" * 64
        self.assert_rejected("signing certificate")

    def test_android_identity_mismatch_is_rejected(self):
        self.manifest["applicationId"] = "play.ott.foss.nativeapp"
        self.assert_rejected("Android identity")

    def test_missing_build_provenance_is_rejected(self):
        del self.manifest["buildRunAttempt"]
        self.assert_rejected("provenance")

    def test_wrong_build_source_event_attempt_or_result_is_rejected(self):
        for field, value in (("head_sha", "c" * 40), ("head_branch", "other"), ("event", "pull_request"),
                             ("path", ".github/workflows/other.yml"), ("status", "in_progress"),
                             ("conclusion", "failure"), ("run_attempt", 3)):
            with self.subTest(field=field):
                original = self.build_run[field]
                self.build_run[field] = value
                self.assert_rejected("signed-build run")
                self.build_run[field] = original

    def test_modified_published_apk_is_rejected(self):
        self.corrupt_download = True
        self.assert_rejected("differs from its signed-build artifact")

    def test_invalid_artifact_checksum_is_rejected_even_if_copies_match(self):
        self.manifest["artifacts"][0]["sha256"] = "0" * 64
        self.assert_rejected("checksum or size")

    def test_extra_assets_are_rejected(self):
        self.extra_asset = True
        self.assert_rejected("unexpected asset set")

    def test_concurrent_release_mutation_is_rejected(self):
        self.change_on_recheck = True
        self.assert_rejected("changed while validating")


if __name__ == "__main__":
    unittest.main()
