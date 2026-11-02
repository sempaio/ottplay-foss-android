"""Lock runner routing and hosted-only provisioning without a YAML dependency.

GitHub syntax is independently checked by actionlint; these tests inspect the
small, deliberately explicit job/step blocks that control runner admission.
"""

import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERIC = "ottplay-k3s-linux-x64"
KVM = "ottplay-k3s-kvm-x64"
RELEASE = "ottplay-k3s-release-x64"


def jobs(name):
    source = (ROOT / ".github/workflows" / name).read_text()
    body = source.split("\njobs:\n", 1)[1]
    return {match[0]: match[1] for match in re.findall(r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)", body, re.M | re.S)}


def steps(job):
    return re.findall(r"^      - (.*?)(?=^      - |\Z)", job, re.M | re.S)


def routed(label, hosted):
    return "${{ vars.CI_RUNNER_MODE == 'k3s' && github.ref == 'refs/heads/main' && (github.event_name == 'push' || github.event_name == 'workflow_dispatch' || github.event_name == 'schedule') && '" + label + "' || '" + hosted + "' }}"


def named_step(job, name):
    return next(step for step in steps(job) if step.startswith("name: " + name + "\n"))


class WorkflowRoutingTests(unittest.TestCase):
    def test_public_prs_and_nonmain_runs_stay_hosted_even_in_k3s_mode(self):
        for name in ['android.yml', 'release.yml']:
            for job, source in jobs(name).items():
                match = re.search(r"(?:runs-on|runner): (\$\{\{ .*? \}\})", source)
                if not match:
                    continue
                expression = match[1][3:-2].strip()
                hosted = "ubuntu-latest" if name == "core.yml" or job == "scope" else "ubuntu-24.04"
                label = RELEASE if name == "release.yml" else KVM if job == "emulator" else GENERIC
                for mode in ("", "github", "k3s", "unexpected"):
                    for ref in ("refs/heads/main", "refs/heads/feature", "refs/pull/1/merge"):
                        for event in ("push", "workflow_dispatch", "schedule", "pull_request", "pull_request_target", "merge_group"):
                            code = expression.replace("vars.CI_RUNNER_MODE", repr(mode)).replace("github.ref", repr(ref)).replace("github.event_name", repr(event))
                            code = code.replace("&&", "and").replace("||", "or")
                            tree = ast.parse(code, mode="eval")
                            allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.Constant)
                            self.assertTrue(all(isinstance(node, allowed) for node in ast.walk(tree)))
                            result = eval(compile(tree, "<runner-expression>", "eval"), {"__builtins__": {}})
                            trusted = mode == "k3s" and ref == "refs/heads/main" and event in ("push", "workflow_dispatch", "schedule")
                            with self.subTest(workflow=name, job=job, mode=mode, ref=ref, event=event):
                                self.assertEqual(result, label if trusted else hosted)

    def test_scope_build_emulator_signing_and_publication_use_separate_pools(self):
        android = jobs("android.yml")
        release = jobs("release.yml")
        self.assertEqual(set(android), {"scope", "build", "emulator"})
        self.assertEqual(set(release), {"build", "publish", "stable"})
        self.assertIn("runner: " + routed(GENERIC, "ubuntu-latest"), android["scope"])
        self.assertRegex(android["scope"], r"change-scope.yml@[0-9a-f]{40}\n")
        for job, label in ((android["build"], GENERIC), (android["emulator"], KVM),
                           (release["build"], RELEASE), (release["publish"], RELEASE), (release["stable"], RELEASE)):
            self.assertIn("runs-on: " + routed(label, "ubuntu-24.04"), job)
            hardened = steps(job)
            self.assertIn("uses: step-security/harden-runner@ccd8616d44fd3846e67624a50d5aad6d37bf2d25", hardened[0])
            self.assertIn("egress-policy: audit", hardened[0])
            sequence = hardened[1:]
            self.assertTrue(sequence[0].startswith("uses: actions/checkout@"))
            self.assertIn("persist-credentials: false", sequence[0])
            self.assertIn("ci-runner-preflight.py", sequence[1])
            self.assertIn("if: runner.environment == 'self-hosted'", sequence[1])

    def test_host_mutations_are_hosted_only_and_kvm_is_verified_before_tests(self):
        emulator = jobs("android.yml")["emulator"]
        mutations = [step for step in steps(emulator) if "sudo " in step]
        self.assertEqual(len(mutations), 2)
        for step in mutations:
            self.assertIn("if: runner.environment == 'github-hosted'", step)
        self.assertIn("ci-runner-preflight.py emulator", next(step for step in steps(emulator) if "ci-runner-preflight.py emulator" in step))
        acceleration = named_step(emulator, "Verify emulator acceleration on the self-hosted image")
        self.assertIn("if: runner.environment == 'self-hosted'", acceleration)
        self.assertIn("-accel-check", acceleration)
        self.assertLess(emulator.index("-accel-check"), emulator.index(":app:assembleDebug"))

    def test_every_android_setup_has_prior_selfhost_sdk_environment(self):
        for filename in ("android.yml", "release.yml"):
            for name, job in jobs(filename).items():
                if "android-actions/setup-android@" not in job:
                    continue
                with self.subTest(filename=filename, job=name):
                    preparation = named_step(job, "Prepare an isolated writable Android SDK")
                    self.assertIn("if: runner.environment == 'self-hosted'", preparation)
                    self.assertIn("ci-runner-preflight.py prepare-sdk", preparation)
                    self.assertLess(job.index("prepare-sdk"), job.index("android-actions/setup-android@"))

    def test_release_sha_and_full_matrix_admission_are_unchanged(self):
        android = jobs("android.yml")
        release = jobs("release.yml")
        self.assertIn("name: Unit tests, lint and APKs", android["build"])
        self.assertIn("name: Instrumented tests · ${{ matrix.device }}", android["emulator"])
        for device in ("phone-api35", "androidtv-api36"):
            self.assertIn("device: " + device, android["emulator"])
        self.assertIn("inputs.run_device_tests", android["emulator"])
        self.assertIn("github.event_name == 'merge_group'", android["emulator"])
        self.assertIn("needs: [scope, build]", android["emulator"])
        self.assertIn("if: github.ref == 'refs/heads/main'", release["build"])
        for command in ("check", "build"):
            self.assertIn("scripts/release.py " + command, release["build"])
        self.assertIn("scripts/release.py publish", release["publish"])
        self.assertIn("needs: build", release["publish"])
        self.assertIn('gh run download "$GITHUB_RUN_ID"', release["publish"])
        cleanup = named_step(release["build"], "Remove signing material from the runner")
        self.assertIn("if: always()", cleanup)
        self.assertIn('"${RUNNER_TEMP:?}/ottplay-release-signing"', cleanup)

    def test_manual_admission_smokes_all_pools_without_secrets_or_hosted_bootstrap(self):
        source = (ROOT / ".github/workflows/runner-smoke.yml").read_text()
        workflow = jobs("runner-smoke.yml")
        expected = {"smoke-linux": GENERIC, "smoke-kvm": KVM, "smoke-release": RELEASE}
        self.assertEqual(set(workflow), set(expected))
        self.assertIn("  workflow_dispatch:\n", source)
        self.assertNotRegex(source, r"(?m)^  (push|pull_request|schedule):")
        self.assertNotIn("CI_RUNNER_MODE", source)
        self.assertNotIn("secrets.", source)
        self.assertNotIn("sudo", source)
        for name, label in expected.items():
            job = workflow[name]
            self.assertIn("runs-on: " + label + "\n", job)
            self.assertIn("if: github.ref == 'refs/heads/main'", job)
            self.assertIn("persist-credentials: false", job)
            self.assertIn("ci-runner-preflight.py prepare-sdk", job)
            self.assertIn("python3 -m unittest discover -s scripts/tests -v", job)
            self.assertIn("run: /opt/ottplay/smoke.sh " + name.removeprefix("smoke-"), job)
            self.assertLess(job.index("android-actions/setup-android@"), job.index("/opt/ottplay/smoke.sh"))
        self.assertIn("-accel-check", workflow["smoke-kvm"])
        self.assertLess(workflow["smoke-kvm"].index("-accel-check"), workflow["smoke-kvm"].index("/opt/ottplay/smoke.sh"))
        self.assertIn("apksigner", workflow["smoke-release"])


if __name__ == "__main__":
    unittest.main()
