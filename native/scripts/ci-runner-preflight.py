#!/usr/bin/env python3
"""Validate the prepared k3s image; never install packages or change host policy."""

import argparse
import ctypes
import fcntl
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

BASE_TOOLS = ("bash", "python3", "git", "curl", "jq", "gh", "unzip", "tar", "gzip")
BROWSER_LIBRARIES = (
    "libnss3.so", "libnspr4.so", "libatk-1.0.so.0", "libatk-bridge-2.0.so.0",
    "libcups.so.2", "libdrm.so.2", "libdbus-1.so.3", "libxcb.so.1", "libX11.so.6",
    "libXcomposite.so.1", "libXdamage.so.1", "libXext.so.6", "libXfixes.so.3",
    "libXrandr.so.2", "libgbm.so.1", "libpango-1.0.so.0", "libcairo.so.2", "libasound.so.2",
)
EMULATOR_LIBRARIES = ("libX11.so.6", "libxcb.so.1", "libpulse.so.0", "libnss3.so")


def writable_directory(name, environ):
    value = environ.get(name, "")
    path = Path(value)
    if not value or "\n" in value or "\r" in value or not path.is_absolute() or not path.is_dir():
        raise ValueError(f"{name} must name an existing absolute directory")
    try:
        with tempfile.TemporaryFile(dir=path) as probe:
            probe.write(b"runner write probe")
            probe.flush()
    except OSError as error:
        raise ValueError(f"{name} must be writable by the runner user: {error}") from error
    return path.resolve()


def check_kvm(device=Path("/dev/kvm")):
    try:
        if not stat.S_ISCHR(device.stat().st_mode):
            raise ValueError("not a character device")
        descriptor = os.open(device, os.O_RDWR | os.O_CLOEXEC)
        try:
            # KVM_GET_API_VERSION: an actual open/ioctl, not merely group membership.
            if fcntl.ioctl(descriptor, 0xAE00, 0) != 12:
                raise ValueError("unsupported KVM API")
        finally:
            os.close(descriptor)
    except (OSError, ValueError) as error:
        raise ValueError(f"{device} must expose usable KVM to the runner user: {error}") from error


def check(profile, environ=None):
    environ = os.environ if environ is None else environ
    if profile not in ("base", "browser", "emulator", "release"):
        raise ValueError("unknown runner preflight profile")
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("k3s jobs require Linux x86_64")
    os_release = platform.freedesktop_os_release()
    if os_release.get("ID") != "ubuntu" or os_release.get("VERSION_ID") != "24.04":
        raise ValueError("k3s jobs require the prepared Ubuntu 24.04 image")
    commands = BASE_TOOLS
    if profile == "browser":
        commands += ("ffmpeg",)
    if profile == "emulator":
        commands += ("timeout", "free")
    missing = [command for command in commands if not shutil.which(command)]
    if missing:
        raise ValueError("runner image is missing tools: " + ", ".join(missing))
    subprocess.run([sys.executable, "-m", "pip", "--version"], check=True, capture_output=True)
    writable_directory("RUNNER_TEMP", environ)
    writable_directory("RUNNER_TOOL_CACHE", environ)
    libraries = BROWSER_LIBRARIES if profile == "browser" else EMULATOR_LIBRARIES if profile == "emulator" else ()
    for library in libraries:
        try:
            ctypes.CDLL(library)
        except OSError as error:
            raise ValueError(f"runner image is missing runtime library {library}: {error}") from error
    if profile == "emulator":
        check_kvm()


def prepare_sdk(environ=None):
    """Keep setup-android away from the image SDK and other jobs' installations."""
    environ = os.environ if environ is None else environ
    temporary = writable_directory("RUNNER_TEMP", environ)
    destination = environ.get("GITHUB_ENV", "")
    if not destination or not Path(destination).is_absolute():
        raise ValueError("GITHUB_ENV must be an absolute file path")
    root = Path(tempfile.mkdtemp(prefix="ottplay-android-", dir=temporary))
    sdk = root / "sdk"
    user = root / "user"
    sdk.mkdir()
    user.mkdir()
    with Path(destination).open("a", encoding="utf-8") as output:
        output.write(f"ANDROID_HOME={sdk}\nANDROID_SDK_ROOT={sdk}\nANDROID_USER_HOME={user}\n")
    return sdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("base", "browser", "emulator", "release", "prepare-sdk"))
    args = parser.parse_args()
    try:
        if args.profile == "prepare-sdk":
            prepare_sdk()
        else:
            check(args.profile)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Runner preflight failed: {error}\n")
    print(f"Runner preflight passed: {args.profile}")


if __name__ == "__main__":
    main()
