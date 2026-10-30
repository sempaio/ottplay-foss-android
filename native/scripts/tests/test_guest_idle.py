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
spec = importlib.util.spec_from_file_location("guest_idle", ROOT / "scripts/ci-wait-for-guest-idle.py")
idle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(idle)

HEALTHY = {"cpu": 20.0, "memory": 2.0, "io": 3.0}


def psi_output(values=HEALTHY):
    return "\n".join(
        f"{key}\nsome avg10={value} avg60=9.99 avg300=9.99 total=12345\n"
        "full avg10=0.00 avg60=0.00 avg300=0.00 total=0"
        for key, value in values.items()
    )


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class GuestIdleTest(unittest.TestCase):
    def test_parses_some_avg10_without_confusing_full_or_longer_averages(self):
        self.assertEqual(idle.parse_pressure(psi_output().replace("\n", "\r\n")), HEALTHY)

    def test_missing_duplicate_and_invalid_metrics_are_not_healthy(self):
        examples = [
            psi_output({"cpu": 0, "memory": 0}),
            psi_output().replace("some avg10=2.0", "some avg60=2.0"),
            psi_output() + "\ncpu\nsome avg10=0",
            psi_output().replace("avg10=2.0", "avg10=2.0 avg10=0"),
            "",
        ]
        examples += [psi_output(HEALTHY | {"cpu": value}) for value in ("nan", "inf", -1, 101, "bad")]
        for output in examples:
            with self.subTest(output=output), self.assertRaises(idle.PressureReadError):
                idle.parse_pressure(output)

    def test_adb_reads_only_pressure_files_from_selected_device_with_a_timeout(self):
        result = subprocess.CompletedProcess([], 0, stdout=psi_output(), stderr="")
        with patch.object(idle.subprocess, "run", return_value=result) as run:
            self.assertEqual(idle.read_guest_pressure("emulator-5582", 5), HEALTHY)
        arguments, options = run.call_args
        self.assertEqual(arguments[0], [
            "adb", "-s", "emulator-5582", "shell",
            'su root sh -c \'for resource in cpu memory io; do '
            'echo "$resource"; cat "/proc/pressure/$resource" || exit 1; done\'',
        ])
        self.assertEqual(options["timeout"], 5)
        self.assertNotIn("shell", options)

    def test_physical_remote_and_malformed_serials_fail_before_adb_or_waiting(self):
        for serial in ("USB123456", "192.168.1.50:5555", "", "emulator-5554; reboot", "emulator-abc"):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                with patch.object(idle.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "must select a local emulator"):
                        idle.wait_for_guest_idle(serial, directory)
                    with self.assertRaises(ValueError):
                        idle.read_guest_pressure(serial, 5)
                    run.assert_not_called()
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_failed_and_timed_out_adb_reads_cannot_supply_healthy_samples(self):
        outcomes = [
            subprocess.CompletedProcess([], 1, stdout=psi_output(), stderr="device offline"),
            subprocess.CompletedProcess([], 0, stdout=psi_output({"cpu": 0}), stderr=""),
            subprocess.TimeoutExpired("adb", 5),
            FileNotFoundError("adb is unavailable"),
        ]
        for outcome in outcomes:
            with self.subTest(outcome=outcome):
                with patch.object(idle.subprocess, "run", side_effect=[outcome]):
                    with self.assertRaises(idle.PressureReadError):
                        idle.read_guest_pressure("emulator-5554", 5)

    def run_wait(self, directory, reader, clock, **kwargs):
        with patch.object(idle, "read_guest_pressure", side_effect=reader), \
                patch.object(idle.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(idle.time, "sleep", side_effect=clock.sleep), \
                contextlib.redirect_stdout(io.StringIO()):
            return idle.wait_for_guest_idle("emulator-5582", directory, **kwargs)

    def test_requires_seven_healthy_samples_and_appends_timestamped_evidence(self):
        clock = FakeClock()
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "guest-idle.jsonl"
            evidence.write_text('{"previous_run": true}\n')
            self.run_wait(directory, lambda *_: HEALTHY, clock)
            records = [json.loads(line) for line in evidence.read_text().splitlines()]
        self.assertEqual(clock.now, 30)
        self.assertEqual(records[0], {"previous_run": True})
        self.assertEqual(len(records[1:]), 7)
        self.assertEqual(records[-1]["stable_seconds"], 30)
        self.assertEqual(records[-1]["serial"], "emulator-5582")
        self.assertTrue(records[-1]["timestamp"].endswith("+00:00"))

    def test_each_limit_and_unreadable_sample_reset_the_entire_stability_window(self):
        for failure in [HEALTHY | {key: limit} for key, limit in idle.LIMITS.items()] + [
            idle.PressureReadError("missing PSI some avg10: io"),
        ]:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                clock = FakeClock()

                def read(*_):
                    if clock.now == 20:
                        if isinstance(failure, Exception):
                            raise failure
                        return failure
                    return HEALTHY

                evidence = self.run_wait(directory, read, clock)
                records = [json.loads(line) for line in evidence.read_text().splitlines()]
                self.assertEqual(clock.now, 55)
                self.assertFalse(records[4]["healthy"])
                self.assertEqual(records[4]["stable_seconds"], 0)
                self.assertEqual(records[-1]["stable_seconds"], 30)

    def test_deadline_is_bounded_and_timeout_identifies_unreadable_metrics_and_evidence(self):
        clock = FakeClock()

        def read(*_):
            raise idle.PressureReadError("ADB PSI read exceeded 5.0s")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(TimeoutError, "did not stabilize within 300s") as failure:
                self.run_wait(directory, read, clock)
            records = [json.loads(line) for line in (Path(directory) / "guest-idle.jsonl").read_text().splitlines()]
            self.assertIn("ADB PSI read exceeded 5.0s", str(failure.exception))
            self.assertIn(str(Path(directory) / "guest-idle.jsonl"), str(failure.exception))
        self.assertEqual(clock.now, 300)
        self.assertEqual(len(records), 60)
        self.assertTrue(all(not row["healthy"] and row["stable_seconds"] == 0 for row in records))

    def test_main_uses_serial_environment_or_default_and_requires_evidence_directory(self):
        for configured, expected in (("emulator-5582", "emulator-5582"), (None, "emulator-5554")):
            values = {"CI_EMULATOR_DIAGNOSTICS": "/tmp/guest-diagnostics"}
            if configured:
                values["ANDROID_SERIAL"] = configured
            with patch.dict(os.environ, values, clear=True), patch.object(idle, "wait_for_guest_idle") as wait:
                self.assertEqual(idle.main(), 0)
                wait.assert_called_once_with(expected, "/tmp/guest-diagnostics")
        with patch.dict(os.environ, {}, clear=True), patch.object(idle, "wait_for_guest_idle") as wait, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(idle.main(), 2)
            wait.assert_not_called()


if __name__ == "__main__":
    unittest.main()
