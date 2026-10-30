#!/usr/bin/env python3
"""Configure a CI framebuffer before boot and verify its physical guest metrics."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys


ADB_TIMEOUT_SECONDS = 10


def parse_display(value):
    match = re.fullmatch(r"([1-9][0-9]{0,3})x([1-9][0-9]{0,3})@([1-9][0-9]{0,2})", value)
    if not match:
        raise ValueError("Display must be WIDTHxHEIGHT@DENSITY with positive integer values, such as 720x1600@280.")
    return dict(zip(("width", "height", "density"), map(int, match.groups())))


def configure_display(avd_config, display):
    expected = parse_display(display)
    updates = {
        "hw.lcd.width": expected["width"],
        "hw.lcd.height": expected["height"],
        "hw.lcd.density": expected["density"],
        "skin.name": f'{expected["width"]}x{expected["height"]}',
        "skin.path": "_no_skin",
    }
    avd_config = Path(avd_config)
    with avd_config.open(encoding="utf-8", newline="") as source:
        original = source.read()
    lines = []
    for line in original.splitlines(keepends=True):
        key = line.partition("=")[0].strip()
        if key not in updates:
            lines.append(line)
    newline = "\r\n" if "\r\n" in original else "\n"
    updated = "".join(lines)
    if updated and not updated.endswith(("\n", "\r")):
        updated += newline
    updated += "".join(f"{key}={value}{newline}" for key, value in updates.items())
    if updated != original:
        with avd_config.open("w", encoding="utf-8", newline="") as destination:
            destination.write(updated)
    return expected


def read_metric(serial, metric):
    command = ["adb", "-s", serial, "shell", "wm", metric]
    observation = {"command": command, "stdout": "", "stderr": "", "returncode": None, "error": None}
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=ADB_TIMEOUT_SECONDS, check=False)
        observation.update(stdout=result.stdout, stderr=result.stderr, returncode=result.returncode)
        if result.returncode:
            observation["error"] = f"ADB wm {metric} failed with exit code {result.returncode}"
    except subprocess.TimeoutExpired as error:
        for name, value in (("stdout", error.stdout), ("stderr", error.stderr)):
            observation[name] = value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
        observation["error"] = f"ADB wm {metric} exceeded {ADB_TIMEOUT_SECONDS}s"
    except OSError as error:
        observation["error"] = f"Cannot execute ADB wm {metric}: {error}"
    return observation


def verify_display(display, serial, diagnostics_dir):
    expected = parse_display(display)
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Serial must select a local emulator (emulator-<port>), not a physical or remote device.")
    diagnostics_dir = Path(diagnostics_dir)
    diagnostics_dir.mkdir(parents=True, exist_ok=True)
    evidence_path = diagnostics_dir / "display-verification.json"
    observations = {metric: read_metric(serial, metric) for metric in ("size", "density")}
    expected_lines = {
        "size": f'Physical size: {expected["width"]}x{expected["height"]}',
        "density": f'Physical density: {expected["density"]}',
    }
    errors = []
    for metric, observation in observations.items():
        lines = [line.strip() for line in observation["stdout"].splitlines() if line.strip()]
        if observation["error"]:
            errors.append(observation["error"])
        elif any(line.startswith("Override ") for line in lines):
            errors.append(f"wm {metric} has an override; physical framebuffer configuration is required")
        elif lines != [expected_lines[metric]]:
            errors.append(f"Expected only '{expected_lines[metric]}', observed {lines!r}")
    evidence = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "serial": serial,
        "expected": expected,
        "observations": observations,
        "verified": not errors,
        "errors": errors,
    }
    evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if errors:
        raise RuntimeError(f"Display verification failed: {'; '.join(errors)}. Evidence: {evidence_path}")
    return evidence_path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    configure = commands.add_parser("configure")
    configure.add_argument("--avd-config", required=True)
    configure.add_argument("--display", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--display", required=True)
    verify.add_argument("--serial", required=True)
    verify.add_argument("--diagnostics-dir", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "configure":
            configure_display(args.avd_config, args.display)
            print(f"Configured physical display {args.display} in {args.avd_config}")
        else:
            evidence = verify_display(args.display, args.serial, args.diagnostics_dir)
            print(f"Verified physical display {args.display} on {args.serial}; evidence: {evidence}")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (OSError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
