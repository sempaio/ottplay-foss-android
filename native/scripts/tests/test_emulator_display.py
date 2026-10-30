import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("emulator_display", ROOT / "scripts/ci-emulator-display.py")
display = importlib.util.module_from_spec(spec)
spec.loader.exec_module(display)

DISPLAY = "720x1600@280"


def result(output, *, returncode=0, stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout=output, stderr=stderr)


def healthy_results():
    return [result("Physical size: 720x1600\r\n"), result("Physical density: 280\n")]


class EmulatorDisplayTest(unittest.TestCase):
    def test_replaces_duplicate_pixel_profile_keys_preserving_other_settings_and_comments(self):
        unrelated = "# Pixel 7 profile\r\nhw.cpu.ncore=2\r\nhw.ramSize=3072\r\nimage.sysdir.1=system-images/android-35/google_apis/x86_64/\r\n"
        original = unrelated + (
            "hw.lcd.width=1080\r\nhw.lcd.height=2400\r\nhw.lcd.density=420\r\n"
            "skin.name=pixel_7\r\nskin.path=/sdk/skins/pixel_7\r\n"
            " hw.lcd.width = 1440\r\nhw.lcd.height=3120\r\nhw.lcd.density=560\r\n"
            "skin.name=other\r\nskin.path=other\r\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.ini"
            path.write_bytes(original.encode())
            display.configure_display(path, DISPLAY)
            configured = path.read_bytes().decode()
            self.assertEqual(configured, unrelated + (
                "hw.lcd.width=720\r\nhw.lcd.height=1600\r\nhw.lcd.density=280\r\n"
                "skin.name=720x1600\r\nskin.path=_no_skin\r\n"
            ))
            before_stat = path.stat()
            display.configure_display(path, DISPLAY)
            self.assertEqual(path.read_bytes().decode(), configured)
            self.assertEqual(path.stat().st_mtime_ns, before_stat.st_mtime_ns)

    def test_adds_missing_geometry_after_unterminated_unrelated_setting(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.ini"
            path.write_text("hw.cpu.ncore=2", encoding="utf-8")
            display.configure_display(path, DISPLAY)
            self.assertTrue(path.read_text().startswith("hw.cpu.ncore=2\nhw.lcd.width=720\n"))

    def test_malformed_display_cannot_write_configuration_or_run_adb(self):
        invalid = ("", "720x1600", "720X1600@280", "720x1600@280\n", "0x1600@280", "720x0@280",
                   "720x1600@0", "-720x1600@280", "720x1600@280; reboot", "720.0x1600@280", "720x1600@0280")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.ini"
            path.write_text("hw.lcd.width=1080\n", encoding="utf-8")
            for value in invalid:
                with self.subTest(value=value), patch.object(display.subprocess, "run") as run:
                    with self.assertRaises(ValueError):
                        display.configure_display(path, value)
                    with self.assertRaises(ValueError):
                        display.verify_display(value, "emulator-5554", Path(directory) / "diagnostics")
                    run.assert_not_called()
                    self.assertEqual(path.read_text(), "hw.lcd.width=1080\n")
                    self.assertFalse((Path(directory) / "diagnostics").exists())

    def test_verifies_physical_metrics_with_bounded_read_only_commands_for_selected_serial(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(display.subprocess, "run", side_effect=healthy_results()) as run:
            evidence_path = display.verify_display(DISPLAY, "emulator-5582", directory)
            evidence = json.loads(evidence_path.read_text())
            self.assertTrue(evidence["verified"])
            self.assertEqual(evidence["expected"], {"width": 720, "height": 1600, "density": 280})
            self.assertEqual(evidence["serial"], "emulator-5582")
            self.assertEqual(evidence["observations"]["size"]["stdout"], "Physical size: 720x1600\r\n")
            self.assertTrue(evidence["timestamp"].endswith("+00:00"))
            for call, metric in zip(run.call_args_list, ("size", "density")):
                self.assertEqual(call.args[0], ["adb", "-s", "emulator-5582", "shell", "wm", metric])
                self.assertEqual(call.kwargs["timeout"], 10)
                self.assertFalse(call.kwargs["check"])
                self.assertNotIn("shell", call.kwargs)
            self.assertEqual(run.call_count, 2)

    def test_rejects_nonlocal_serials_before_adb_and_evidence_creation(self):
        for serial in ("USB1234", "192.168.1.2:5555", "", "emulator-abc", "emulator-5554; reboot"):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                with patch.object(display.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "local emulator"):
                    display.verify_display(DISPLAY, serial, directory)
                run.assert_not_called()
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_wrong_physical_metrics_overrides_and_invalid_output_fail_with_raw_evidence(self):
        failures = (
            ("size", "Physical size: 1080x2400\n"),
            ("density", "Physical density: 420\n"),
            ("size", "Physical size: 720x1600\nOverride size: 720x1600\n"),
            ("density", "Physical density: 280\nOverride density: 280\n"),
            ("size", "Physical size: 1080x2400\nOverride size: 720x1600\n"),
            ("size", ""),
            ("density", "Physical density: 280\nPhysical density: 280\n"),
            ("size", "720x1600\n"),
        )
        for metric, output in failures:
            results = healthy_results()
            results[0 if metric == "size" else 1] = result(output)
            with self.subTest(metric=metric, output=output), tempfile.TemporaryDirectory() as directory:
                with patch.object(display.subprocess, "run", side_effect=results), self.assertRaisesRegex(RuntimeError, "Evidence:"):
                    display.verify_display(DISPLAY, "emulator-5554", directory)
                evidence = json.loads((Path(directory) / "display-verification.json").read_text())
                self.assertFalse(evidence["verified"])
                self.assertEqual(evidence["observations"][metric]["stdout"], output)
                self.assertEqual(evidence["expected"]["density"], 280)
                self.assertTrue(evidence["errors"])

    def test_adb_failures_and_timeouts_record_diagnostics_and_cannot_pass(self):
        failures = (
            result("Physical size: 720x1600\n", returncode=1, stderr="device offline"),
            subprocess.TimeoutExpired("adb", 10, output=b"partial output", stderr=b"partial error"),
            FileNotFoundError("adb missing"),
        )
        for failure in failures:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                with patch.object(display.subprocess, "run", side_effect=[failure, healthy_results()[1]]) as run:
                    with self.assertRaises(RuntimeError):
                        display.verify_display(DISPLAY, "emulator-5554", directory)
                evidence = json.loads((Path(directory) / "display-verification.json").read_text())
                self.assertFalse(evidence["verified"])
                self.assertTrue(evidence["observations"]["size"]["error"])
                self.assertEqual(run.call_count, 2)
                if isinstance(failure, subprocess.TimeoutExpired):
                    self.assertEqual(evidence["observations"]["size"]["stdout"], "partial output")
                    self.assertEqual(evidence["observations"]["size"]["stderr"], "partial error")
                elif isinstance(failure, subprocess.CompletedProcess):
                    self.assertEqual(evidence["observations"]["size"]["stderr"], "device offline")

    def test_cli_uses_requested_paths_and_nonzero_status_on_validation_failure(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            path = Path(directory) / "config.ini"
            path.write_text("hw.lcd.width=1080\n", encoding="utf-8")
            self.assertEqual(display.main(["configure", "--avd-config", str(path), "--display", DISPLAY]), 0)
            arguments = ["verify", "--display", DISPLAY, "--serial", "emulator-5582", "--diagnostics-dir", directory]
            with patch.object(display.subprocess, "run", side_effect=healthy_results()):
                self.assertEqual(display.main(arguments), 0)
            with patch.object(display.subprocess, "run", side_effect=[result("wrong"), healthy_results()[1]]):
                self.assertEqual(display.main(arguments), 1)
            self.assertEqual(display.main(["configure", "--avd-config", str(path), "--display", "invalid"]), 2)


if __name__ == "__main__":
    unittest.main()
