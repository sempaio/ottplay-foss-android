#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?}"
: "${ANDROID_AVD_HOME:?}"
: "${CI_AVD_NAME:?}"
: "${CI_AVD_PROFILE:?}"
: "${CI_SYSTEM_IMAGE:?}"
: "${CI_EMULATOR_DIAGNOSTICS:?}"

mkdir -p "$ANDROID_AVD_HOME" "$CI_EMULATOR_DIAGNOSTICS"
avd_ram_mb=${CI_AVD_RAM_MB:-2048}
case "$avd_ram_mb" in
  2048|3072) ;;
  *) echo 'CI_AVD_RAM_MB must be 2048 or 3072.' >&2; exit 1 ;;
esac
printf 'no\n' | timeout 120s avdmanager create avd --force \
  --name "$CI_AVD_NAME" --package "$CI_SYSTEM_IMAGE" --device "$CI_AVD_PROFILE"
cat >> "$ANDROID_AVD_HOME/$CI_AVD_NAME.avd/config.ini" <<CONFIG
hw.cpu.ncore=2
hw.ramSize=$avd_ram_mb
vm.heapSize=512
disk.dataPartition.size=2048M
CONFIG

if [[ -n "${CI_AVD_DISPLAY:-}" ]]; then
  python3 scripts/ci-emulator-display.py configure \
    --avd-config "$ANDROID_AVD_HOME/$CI_AVD_NAME.avd/config.ini" \
    --display "$CI_AVD_DISPLAY"
fi
cp "$ANDROID_AVD_HOME/$CI_AVD_NAME.avd/config.ini" "$CI_EMULATOR_DIAGNOSTICS/avd-config.ini"

timeout 15s adb start-server
nohup "$ANDROID_HOME/emulator/emulator" -avd "$CI_AVD_NAME" -port 5554 \
  -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot \
  -camera-back none -partition-size 2048 \
  > "$CI_EMULATOR_DIAGNOSTICS/emulator.log" 2>&1 < /dev/null &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > "$CI_EMULATOR_DIAGNOSTICS/emulator.pid"

# sys.boot_completed can precede the launcher and an ADB reconnect. Do not probe
# input with a key here: dispatching MENU before the launcher has a focused window
# can trigger a launcher ANR whose system dialog steals focus from every UI test.
# Retry only framework setup; Gradle runs once in the next CI step.
boot_deadline=$((SECONDS + 600))
while (( SECONDS < boot_deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited before Android became ready.' >&2
    tail -n 100 "$CI_EMULATOR_DIAGNOSTICS/emulator.log" >&2
    exit 1
  fi
  if [[ "$(timeout 10s adb -s emulator-5554 get-state 2>/dev/null || true)" == 'device' ]] &&
     [[ "$(timeout 10s adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == '1' ]] &&
     timeout 10s adb -s emulator-5554 shell pm path android > "$CI_EMULATOR_DIAGNOSTICS/package-readiness.log" 2>&1 &&
     grep -q '^package:' "$CI_EMULATOR_DIAGNOSTICS/package-readiness.log" &&
     timeout 10s adb -s emulator-5554 shell settings put global window_animation_scale 0.0 &&
     timeout 10s adb -s emulator-5554 shell settings put global transition_animation_scale 0.0 &&
     timeout 10s adb -s emulator-5554 shell settings put global animator_duration_scale 0.0; then
    echo 'Android boot, package manager and animation settings are ready.'
    timeout 10s adb -s emulator-5554 shell getprop > "$CI_EMULATOR_DIAGNOSTICS/device-properties.txt"
    if [[ -n "${CI_AVD_DISPLAY:-}" ]]; then
      python3 scripts/ci-emulator-display.py verify \
        --display "$CI_AVD_DISPLAY" --serial emulator-5554 \
        --diagnostics-dir "$CI_EMULATOR_DIAGNOSTICS"
    fi
    if [[ "${CI_STABILIZE_GUEST:-false}" == 'true' ]]; then
      python3 scripts/ci-wait-for-guest-idle.py
    fi
    timeout 10s adb -s emulator-5554 shell wm dismiss-keyguard
    python3 scripts/ci-wait-for-launcher.py
    exit 0
  fi
  sleep 2
done

echo 'Timed out waiting for Android framework readiness (600 seconds).' >&2
exit 1
