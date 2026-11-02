#!/usr/bin/env python3
"""Wait for first-boot work on a dedicated local Google APIs userdebug emulator.

Only the fixed, read-only PSI command uses su; adb and the app stay unprivileged.
"""
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time


LIMITS = {"cpu": 70.0, "memory": 10.0, "io": 10.0}
SAMPLE_COMMAND = (
    'for resource in cpu memory io; do '
    'echo "$resource"; cat "/proc/pressure/$resource" || exit 1; done'
)
ROOT_SAMPLE_COMMAND = "su root sh -c " + shlex.quote(SAMPLE_COMMAND)


class PressureReadError(RuntimeError):
    pass


def validate_emulator_serial(serial):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("ANDROID_SERIAL must select a local emulator (emulator-<port>), not a physical device.")


def parse_pressure(output):
    """Read each named PSI 'some avg10'; absent or invalid data is never idle."""
    values = {}
    sections = set()
    resource = None
    for line in output.splitlines():
        line = line.strip()
        if not line:
            continue
        if line in LIMITS:
            if line in sections:
                raise PressureReadError(f"duplicate PSI section: {line}")
            sections.add(line)
            resource = line
        elif line.startswith("some ") and resource is not None:
            averages = [field[6:] for field in line.split()[1:] if field.startswith("avg10=")]
            if resource in values or len(averages) != 1:
                raise PressureReadError(f"missing or duplicate PSI some avg10 for {resource}")
            try:
                value = float(averages[0])
            except ValueError as error:
                raise PressureReadError(f"invalid PSI some avg10 for {resource}") from error
            if not math.isfinite(value) or not 0 <= value <= 100:
                raise PressureReadError(f"out-of-range PSI some avg10 for {resource}")
            values[resource] = value
        elif not line.startswith("full "):
            raise PressureReadError(f"unexpected PSI output in {resource or 'unnamed'} section")
    missing = LIMITS.keys() - values.keys()
    if missing:
        raise PressureReadError(f"missing PSI some avg10: {', '.join(sorted(missing))}")
    return values


def read_guest_pressure(serial, timeout):
    validate_emulator_serial(serial)
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", ROOT_SAMPLE_COMMAND],
            capture_output=True, text=True, timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise PressureReadError(f"ADB PSI read exceeded {timeout:.1f}s") from error
    except OSError as error:
        raise PressureReadError(f"cannot execute ADB: {error}") from error
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip() or "no diagnostic output"
        raise PressureReadError(f"ADB PSI read failed ({result.returncode}): {detail[:500]}")
    return parse_pressure(result.stdout)


def wait_for_guest_idle(serial, diagnostics_directory, *, timeout_seconds=300,
                        stable_seconds=30, interval_seconds=5):
    validate_emulator_serial(serial)
    diagnostics_directory = Path(diagnostics_directory)
    diagnostics_directory.mkdir(parents=True, exist_ok=True)
    evidence_path = diagnostics_directory / "guest-idle.jsonl"
    started = time.monotonic()
    deadline = started + timeout_seconds
    stable_since = None
    last_sample = None
    print(
        f"Waiting up to {timeout_seconds}s for {serial}: PSI some avg10 "
        f"cpu<70%, memory<10%, io<10% for {stable_seconds}s; evidence: {evidence_path}",
        flush=True,
    )
    with evidence_path.open("a", encoding="utf-8") as evidence:
        while time.monotonic() < deadline:
            sample_started = time.monotonic()
            remaining = deadline - sample_started
            if remaining <= 0:
                break
            error = None
            pressures = None
            try:
                pressures = read_guest_pressure(serial, min(interval_seconds, remaining))
            except PressureReadError as failure:
                error = str(failure)
            now = time.monotonic()
            healthy = pressures is not None and all(pressures[key] < limit for key, limit in LIMITS.items())
            if healthy:
                if stable_since is None:
                    stable_since = now
            else:
                stable_since = None
            stable_for = now - stable_since if stable_since is not None else 0
            last_sample = {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "serial": serial,
                "elapsed_seconds": round(now - started, 3),
                "some_avg10_percent": pressures,
                "healthy": healthy,
                "stable_seconds": round(stable_for, 3),
                "error": error,
            }
            evidence.write(json.dumps(last_sample, sort_keys=True) + "\n")
            evidence.flush()
            if now <= deadline and healthy and stable_for >= stable_seconds:
                print(f"Guest pressure stayed below all limits for {stable_for:.1f}s; Android is ready.", flush=True)
                return evidence_path
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(remaining, max(0, interval_seconds - (time.monotonic() - sample_started))))
    raise TimeoutError(
        f"Guest {serial} did not stabilize within {timeout_seconds}s: require PSI some avg10 "
        f"cpu<70%, memory<10%, io<10% continuously for {stable_seconds}s. "
        f"Missing or unreadable metrics reset the stability window. "
        f"Last sample: {json.dumps(last_sample, sort_keys=True)}. Evidence: {evidence_path}"
    )


def main():
    diagnostics_directory = os.environ.get("CI_EMULATOR_DIAGNOSTICS")
    if not diagnostics_directory:
        print("CI_EMULATOR_DIAGNOSTICS must name the emulator evidence directory.", file=sys.stderr)
        return 2
    try:
        wait_for_guest_idle(os.environ.get("ANDROID_SERIAL", "emulator-5554"), diagnostics_directory)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (OSError, TimeoutError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
