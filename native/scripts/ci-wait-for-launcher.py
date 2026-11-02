#!/usr/bin/env python3
"""Observe the local emulator's HOME window before injecting any input."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


COMPONENT = re.compile(r"([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)")
HOME_COMMAND = (
    "cmd", "package", "resolve-activity", "--brief", "--user", "0",
    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
)
MAX_EVIDENCE_CHARS = 100_000


class LauncherReadError(RuntimeError):
    pass


class LauncherBlockedError(RuntimeError):
    pass


def validate_emulator_serial(serial):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("ANDROID_SERIAL must select a local emulator (emulator-<port>), not a physical device.")


def normalize_component(value):
    match = COMPONENT.fullmatch(value)
    if match is None:
        raise LauncherReadError(f"Invalid Android activity component: {value[:200]}")
    package, activity = match.groups()
    return package + "/" + (package + activity if activity.startswith(".") else activity)


def parse_home(output):
    components = [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]
    if len(components) != 1:
        raise LauncherReadError("HOME resolution did not return exactly one activity component")
    return normalize_component(components[0])


def reject_error_windows(output):
    # Check actual window records, not the historical ANR diagnostics also
    # included in dumpsys. An OS error dialog must fail CI, never be dismissed.
    for line in output.splitlines():
        if re.match(r"\s*(?:Window #\d+ |mCurrentFocus=|mFocusedWindow=)Window\{", line) and (
            "Application Not Responding:" in line or "Application Error:" in line
        ):
            raise LauncherBlockedError(f"Android error dialog blocks launcher readiness: {line.strip()[:500]}")


def parse_focused_window(output):
    reject_error_windows(output)
    focus_lines = re.findall(r"^\s*mCurrentFocus=(.*)$", output, flags=re.MULTILINE)
    if len(focus_lines) != 1:
        raise LauncherReadError("Window dump did not report exactly one current focus")
    match = re.fullmatch(r"Window\{[^\s{}]+ u\d+ ([^{}]+)\}\s*", focus_lines[0])
    if match is None:
        raise LauncherReadError(f"No focused activity window: {focus_lines[0][:500]}")
    return normalize_component(match.group(1))


def read_adb(serial, command, timeout):
    validate_emulator_serial(serial)
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", *command],
            capture_output=True, text=True, timeout=timeout, check=False,
        )
    except (subprocess.TimeoutExpired, OSError) as error:
        raise LauncherReadError(f"Cannot read Android launcher state: {error}") from error
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip() or "no diagnostic output"
        raise LauncherReadError(f"ADB launcher read failed ({result.returncode}): {detail[:500]}")
    return result.stdout


def wait_for_launcher(serial, diagnostics_directory, *, timeout_seconds=120,
                      stable_seconds=2, interval_seconds=1):
    validate_emulator_serial(serial)
    diagnostics_directory = Path(diagnostics_directory)
    diagnostics_directory.mkdir(parents=True, exist_ok=True)
    evidence_path = diagnostics_directory / "launcher-readiness.jsonl"
    dump_path = diagnostics_directory / "launcher-window.txt"
    started = time.monotonic()
    deadline = started + timeout_seconds
    stable_since = None
    previous_home = None
    last_sample = None
    print(f"Waiting up to {timeout_seconds}s for {serial}'s HOME window to own input focus; evidence: {evidence_path}", flush=True)
    with evidence_path.open("a", encoding="utf-8") as evidence:
        while time.monotonic() < deadline:
            home = focus = error = None
            blocked = None
            try:
                home = parse_home(read_adb(serial, HOME_COMMAND, min(10, deadline - time.monotonic())))
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise LauncherReadError("Launcher deadline expired while resolving HOME")
                windows = read_adb(serial, ("dumpsys", "window", "windows"), min(10, remaining))
                dump_path.write_text(windows[:MAX_EVIDENCE_CHARS], encoding="utf-8")
                reject_error_windows(windows)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise LauncherReadError("Launcher deadline expired while reading windows")
                # Separate live sections exclude the historical window/focus
                # snapshots retained in `dumpsys window lastanr`.
                displays = read_adb(serial, ("dumpsys", "window", "displays"), min(10, remaining))
                dump_path.write_text((displays + "\n" + windows)[:MAX_EVIDENCE_CHARS], encoding="utf-8")
                focus = parse_focused_window(displays)
            except LauncherBlockedError as failure:
                blocked = failure
                error = str(failure)
            except LauncherReadError as failure:
                error = str(failure)
            now = time.monotonic()
            # Android TV may temporarily resolve HOME to Settings' first-boot
            # placeholder. Its focus does not mean the actual launcher is ready.
            ready = home is not None and not home.endswith(".FallbackHome") and focus == home and error is None
            if ready:
                if stable_since is None or previous_home != home:
                    stable_since = now
            else:
                stable_since = None
            previous_home = home
            stable_for = now - stable_since if stable_since is not None else 0
            last_sample = {
                "timestamp": datetime.now(timezone.utc).isoformat(), "serial": serial,
                "elapsed_seconds": round(now - started, 3), "home": home, "focus": focus,
                "ready": ready, "stable_seconds": round(stable_for, 3), "error": error,
            }
            evidence.write(json.dumps(last_sample, sort_keys=True) + "\n")
            evidence.flush()
            if blocked is not None:
                raise LauncherBlockedError(f"{blocked}. Evidence: {evidence_path}; window dump: {dump_path}") from blocked
            if now <= deadline and ready and stable_for >= stable_seconds:
                print(f"HOME window {home} owned input focus for {stable_for:.1f}s.", flush=True)
                return evidence_path
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(interval_seconds, remaining))
    raise TimeoutError(
        f"Launcher did not own input focus within {timeout_seconds}s. "
        f"Last sample: {json.dumps(last_sample, sort_keys=True)}. Evidence: {evidence_path}; window dump: {dump_path}"
    )


def main():
    diagnostics_directory = os.environ.get("CI_EMULATOR_DIAGNOSTICS")
    if not diagnostics_directory:
        print("CI_EMULATOR_DIAGNOSTICS must name the emulator evidence directory.", file=sys.stderr)
        return 2
    try:
        wait_for_launcher(os.environ.get("ANDROID_SERIAL", "emulator-5554"), diagnostics_directory)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (OSError, TimeoutError, LauncherBlockedError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
