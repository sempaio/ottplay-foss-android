#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-reports
trap 'adb pull /sdcard/Android/data/br.starlink.iptv.layout/files/screenshots device-reports/screenshots >/dev/null 2>&1 || true' EXIT
adb install -r device-apks/STARLINK-IPTV-layout.apk
adb install -r device-apks/STARLINK-IPTV-layout-androidTest.apk
adb shell am instrument -w -r -e class play.ott.nativeapp.ui.StarlinkAcceptanceSuite br.starlink.iptv.layout.test/androidx.test.runner.AndroidJUnitRunner | tee device-reports/instrumentation.txt
python3 starlink/verify_device_output.py device-reports/instrumentation.txt

