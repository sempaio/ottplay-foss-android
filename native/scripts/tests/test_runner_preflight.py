"""Exercise runner-image admission without touching host packages or KVM."""

import importlib.util
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runner_preflight", ROOT / "scripts/ci-runner-preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class RunnerPreflightTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.env = {"RUNNER_TEMP": str(self.root), "RUNNER_TOOL_CACHE": str(self.root)}
        for obj, name, value in (
            (preflight.platform, "system", "Linux"),
            (preflight.platform, "machine", "x86_64"),
            (preflight.platform, "freedesktop_os_release", {"ID": "ubuntu", "VERSION_ID": "24.04"}),
            (preflight.shutil, "which", "/usr/bin/fixture"),
        ):
            patcher = patch.object(obj, name, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.command = patch.object(preflight.subprocess, "run").start()
        self.addCleanup(patch.stopall)

    def test_generic_image_uses_only_read_only_pip_command_and_removes_write_probe(self):
        preflight.check("base", self.env)
        self.assertEqual(list(self.root.iterdir()), [])
        self.command.assert_called_once_with(
            [preflight.sys.executable, "-m", "pip", "--version"], check=True, capture_output=True)

    def test_rejects_wrong_architecture_os_distribution_or_release(self):
        for method, value in (("system", "Darwin"), ("machine", "aarch64"),
                              ("freedesktop_os_release", {"ID": "debian", "VERSION_ID": "24.04"}),
                              ("freedesktop_os_release", {"ID": "ubuntu", "VERSION_ID": "22.04"})):
            with self.subTest(method=method, value=value), patch.object(preflight.platform, method, return_value=value):
                with self.assertRaisesRegex(ValueError, "require"):
                    preflight.check("base", self.env)

    def test_missing_tool_and_python_pip_fail_before_download_or_install(self):
        with patch.object(preflight.shutil, "which", side_effect=lambda name: None if name == "gh" else "/bin/tool"):
            with self.assertRaisesRegex(ValueError, "missing tools: gh"):
                preflight.check("base", self.env)
        self.command.assert_not_called()
        self.command.side_effect = subprocess.CalledProcessError(1, "pip")
        with self.assertRaises(subprocess.CalledProcessError):
            preflight.check("base", self.env)

    def test_temp_and_toolcache_must_exist_be_absolute_and_be_writable(self):
        for name in ("RUNNER_TEMP", "RUNNER_TOOL_CACHE"):
            for value in ("", "relative", str(self.root / "missing"), str(self.root) + "\ninvalid"):
                with self.subTest(name=name, value=value), self.assertRaisesRegex(ValueError, name):
                    preflight.check("base", dict(self.env, **{name: value}))
        with patch.object(preflight.tempfile, "TemporaryFile", side_effect=PermissionError("read-only")):
            with self.assertRaisesRegex(ValueError, "writable"):
                preflight.check("base", self.env)

    def test_browser_requires_ffmpeg_and_all_chromium_runtime_libraries(self):
        with patch.object(preflight.shutil, "which", side_effect=lambda name: None if name == "ffmpeg" else "/bin/tool"):
            with self.assertRaisesRegex(ValueError, "ffmpeg"):
                preflight.check("browser", self.env)
        with patch.object(preflight.ctypes, "CDLL") as library:
            preflight.check("browser", self.env)
            self.assertEqual([call.args[0] for call in library.call_args_list], list(preflight.BROWSER_LIBRARIES))
        with patch.object(preflight.ctypes, "CDLL", side_effect=OSError("not installed")):
            with self.assertRaisesRegex(ValueError, "runtime library"):
                preflight.check("browser", self.env)

    def test_emulator_requires_libraries_and_kvm_not_only_group_membership(self):
        with patch.object(preflight.ctypes, "CDLL") as libraries, patch.object(preflight, "check_kvm") as kvm:
            preflight.check("emulator", self.env)
            kvm.assert_called_once_with()
            self.assertEqual([call.args[0] for call in libraries.call_args_list], list(preflight.EMULATOR_LIBRARIES))
        with self.assertRaisesRegex(ValueError, "usable KVM"):
            preflight.check_kvm(self.root / "missing-kvm")
        ordinary = self.root / "kvm"
        ordinary.touch()
        with self.assertRaisesRegex(ValueError, "character device"):
            preflight.check_kvm(ordinary)

    def test_kvm_opens_device_read_write_checks_api_and_closes_on_failure(self):
        with (patch.object(Path, "stat", return_value=type("Status", (), {"st_mode": stat.S_IFCHR})()),
              patch.object(preflight.os, "open", return_value=91) as opening,
              patch.object(preflight.os, "close") as close,
              patch.object(preflight.fcntl, "ioctl", return_value=12) as ioctl):
            preflight.check_kvm()
            opening.assert_called_once_with(Path("/dev/kvm"), os.O_RDWR | os.O_CLOEXEC)
            ioctl.assert_called_once_with(91, 0xAE00, 0)
            close.assert_called_once_with(91)
            ioctl.return_value = 0
            with self.assertRaisesRegex(ValueError, "unsupported KVM API"):
                preflight.check_kvm()
            self.assertEqual(close.call_count, 2)
            opening.side_effect = PermissionError("denied")
            with self.assertRaisesRegex(ValueError, "denied"):
                preflight.check_kvm()

    def test_sdk_paths_are_unique_writable_and_override_both_android_variables(self):
        destination = self.root / "env"
        env = dict(self.env, GITHUB_ENV=str(destination), ANDROID_HOME="/opt/read-only-sdk", ANDROID_SDK_ROOT="/opt/shared")
        first = preflight.prepare_sdk(env)
        second = preflight.prepare_sdk(env)
        self.assertNotEqual(first, second)
        for sdk in (first, second):
            self.assertTrue(sdk.is_dir())
            self.assertEqual(sdk.parent.parent, self.root)
            self.assertIn(f"ANDROID_HOME={sdk}\nANDROID_SDK_ROOT={sdk}\n", destination.read_text())
            self.assertIn(f"ANDROID_USER_HOME={sdk.parent / 'user'}\n", destination.read_text())
        self.assertEqual(env["ANDROID_HOME"], "/opt/read-only-sdk")
        self.command.assert_not_called()

    def test_sdk_rejects_missing_env_file_before_allocating_any_directories(self):
        with self.assertRaisesRegex(ValueError, "GITHUB_ENV"):
            preflight.prepare_sdk(self.env)
        self.assertEqual(list(self.root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
