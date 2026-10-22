import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("launcher_readiness", ROOT / "scripts/ci-wait-for-launcher.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

HOME = "com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity"
RESOLVED = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" + HOME + "\n"
WINDOWS = """WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{abc u0 NavigationBar}:
    mHasSurface=true isReadyForDisplay()=true
  Window #1 Window{123 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}:
    mHasSurface=true isReadyForDisplay()=true
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  mCurrentFocus=Window{123 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}
  mFocusedApp=ActivityRecord{789 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}
"""


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class LauncherReadinessTest(unittest.TestCase):
    def test_resolves_brief_home_and_normalizes_relative_activity(self):
        self.assertEqual(launcher.parse_home(RESOLVED.replace("\n", "\r\n")), HOME)
        self.assertEqual(launcher.parse_focused_window(WINDOWS), HOME)
        tv = "com.google.android.tvlauncher/.MainActivity"
        self.assertEqual(launcher.parse_home(tv), "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity")

    def test_missing_malformed_and_ambiguous_output_cannot_pass(self):
        for output in ("", "No activity found", RESOLVED + HOME, "x; reboot/y"):
            with self.subTest(output=output), self.assertRaises(launcher.LauncherReadError):
                launcher.parse_home(output)
        for output in ("", "mCurrentFocus=null", WINDOWS + "mCurrentFocus=null\n",
                       "mCurrentFocus=Window{123 u0 NotificationShade}",
                       "mFocusedApp=ActivityRecord{abc u0 " + HOME + " t1}"):
            with self.subTest(output=output), self.assertRaises(launcher.LauncherReadError):
                launcher.parse_focused_window(output)

    def test_live_anr_and_crash_windows_fail_even_if_home_still_reported_focused(self):
        for title in ("Application Not Responding: com.google.android.apps.nexuslauncher",
                      "Application Error: com.android.systemui"):
            for line in (f"  Window #2 Window{{5b94b97 u0 {title}}}:\n",
                         f"  mCurrentFocus=Window{{5b94b97 u0 {title}}}\n"):
                with self.subTest(line=line), self.assertRaises(launcher.LauncherBlockedError):
                    launcher.parse_focused_window(WINDOWS + line)
        self.assertEqual(launcher.parse_focused_window(WINDOWS + "Last ANR: Application Not Responding: old.package\n"), HOME)

    def test_adb_commands_are_read_only_bounded_and_select_local_emulator(self):
        result = subprocess.CompletedProcess([], 0, stdout=RESOLVED, stderr="")
        with patch.object(launcher.subprocess, "run", return_value=result) as run:
            self.assertEqual(launcher.read_adb("emulator-5582", launcher.HOME_COMMAND, 5), RESOLVED)
        self.assertEqual(run.call_args.args[0], ["adb", "-s", "emulator-5582", "shell", *launcher.HOME_COMMAND])
        self.assertEqual(run.call_args.kwargs["timeout"], 5)
        self.assertNotIn("shell", run.call_args.kwargs)

    def test_nonlocal_serial_rejected_before_io(self):
        for serial in ("USB1234", "192.168.1.50:5555", "", "emulator-5554; reboot", "emulator-abc"):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory, patch.object(launcher.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "local emulator"):
                    launcher.wait_for_launcher(serial, directory)
                run.assert_not_called()
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_failed_or_timed_out_adb_read_cannot_pass(self):
        for failure in (subprocess.CompletedProcess([], 1, stdout=RESOLVED, stderr="device offline"),
                        subprocess.TimeoutExpired("adb", 5), FileNotFoundError("adb missing")):
            with self.subTest(failure=failure), patch.object(launcher.subprocess, "run", side_effect=[failure]):
                with self.assertRaises(launcher.LauncherReadError):
                    launcher.read_adb("emulator-5554", launcher.HOME_COMMAND, 5)

    def run_wait(self, directory, reader, clock, **kwargs):
        with patch.object(launcher, "read_adb", side_effect=reader), \
                patch.object(launcher.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(launcher.time, "sleep", side_effect=clock.sleep), \
                contextlib.redirect_stdout(io.StringIO()):
            return launcher.wait_for_launcher("emulator-5554", directory, **kwargs)

    def test_requires_stable_home_focus_after_setup_and_keeps_evidence(self):
        clock = FakeClock()
        calls = []

        def read(serial, command, timeout):
            calls.append(command)
            if command == launcher.HOME_COMMAND:
                return RESOLVED
            self.assertIn(command, (("dumpsys", "window", "windows"), ("dumpsys", "window", "displays")))
            return WINDOWS if clock.now != 1 else WINDOWS.replace("mCurrentFocus=Window{123 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}", "mCurrentFocus=null")

        with tempfile.TemporaryDirectory() as directory:
            path = self.run_wait(directory, read, clock)
            records = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual((Path(directory) / "launcher-window.txt").read_text(), WINDOWS + "\n" + WINDOWS)
        self.assertEqual(clock.now, 4)
        self.assertEqual(len(calls), 15)
        self.assertFalse(records[1]["ready"])
        self.assertEqual(records[-1]["stable_seconds"], 2)
        self.assertEqual(records[-1]["home"], HOME)
        self.assertTrue(records[-1]["timestamp"].endswith("+00:00"))

    def test_anr_fails_immediately_records_evidence_and_does_not_dismiss(self):
        clock = FakeClock()
        blocked = WINDOWS + "  Window #2 Window{5b94b97 u0 Application Not Responding: com.google.android.apps.nexuslauncher}:\n"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(launcher.LauncherBlockedError, "Evidence:"):
                self.run_wait(directory, [RESOLVED, blocked], clock)
            records = [json.loads(line) for line in (Path(directory) / "launcher-readiness.jsonl").read_text().splitlines()]
        self.assertEqual(clock.now, 0)
        self.assertEqual(len(records), 1)
        self.assertFalse(records[0]["ready"])
        self.assertIn("Application Not Responding", records[0]["error"])

    def test_resolves_home_again_when_boot_fallback_changes(self):
        clock = FakeClock()
        fallback = "com.android.tv.settings/com.android.tv.settings.system.FallbackHome"

        def read(_serial, command, _timeout):
            home = fallback if clock.now < 4 else HOME
            if command == launcher.HOME_COMMAND:
                return home
            return "mCurrentFocus=Window{123 u0 " + home + "}\n"

        with tempfile.TemporaryDirectory() as directory:
            path = self.run_wait(directory, read, clock)
            records = [json.loads(line) for line in path.read_text().splitlines()]
        self.assertEqual(clock.now, 6)
        self.assertFalse(records[2]["ready"])
        self.assertEqual(records[4]["stable_seconds"], 0)
        self.assertEqual(records[-1]["home"], HOME)

    def test_never_ready_times_out_and_retains_bounded_last_dump(self):
        clock = FakeClock()
        dump = "mCurrentFocus=null\n" + "x" * (launcher.MAX_EVIDENCE_CHARS + 1)

        def read(_serial, command, _timeout):
            return RESOLVED if command == launcher.HOME_COMMAND else dump

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(TimeoutError, "within 3s"):
                self.run_wait(directory, read, clock, timeout_seconds=3)
            self.assertEqual(len((Path(directory) / "launcher-window.txt").read_text()), launcher.MAX_EVIDENCE_CHARS)
            records = [json.loads(line) for line in (Path(directory) / "launcher-readiness.jsonl").read_text().splitlines()]
        self.assertEqual(clock.now, 3)
        self.assertEqual(len(records), 3)
        self.assertTrue(all(not row["ready"] for row in records))

    def test_home_resolution_consuming_deadline_does_not_start_second_adb_read(self):
        clock = FakeClock()
        calls = []

        def read(_serial, command, _timeout):
            calls.append(command)
            clock.now = 3
            return RESOLVED

        with tempfile.TemporaryDirectory() as directory, self.assertRaises(TimeoutError):
            self.run_wait(directory, read, clock, timeout_seconds=3)
        self.assertEqual(calls, [launcher.HOME_COMMAND])

    def test_main_uses_configured_emulator_and_requires_diagnostics(self):
        with patch.dict(os.environ, {"CI_EMULATOR_DIAGNOSTICS": "/tmp/launcher", "ANDROID_SERIAL": "emulator-5582"}, clear=True), \
                patch.object(launcher, "wait_for_launcher") as wait:
            self.assertEqual(launcher.main(), 0)
            wait.assert_called_once_with("emulator-5582", "/tmp/launcher")
        with patch.dict(os.environ, {}, clear=True), patch.object(launcher, "wait_for_launcher") as wait, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(launcher.main(), 2)
            wait.assert_not_called()


if __name__ == "__main__":
    unittest.main()
