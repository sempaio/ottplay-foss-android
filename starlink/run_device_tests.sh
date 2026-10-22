#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-reports
adb install -r device-apks/STARLINK-IPTV-debug.apk
adb install -r device-apks/STARLINK-IPTV-debug-androidTest.apk
adb shell am instrument -w -r -e class play.ott.nativeapp.ui.StarlinkAcceptanceSuite br.starlink.iptv.test/androidx.test.runner.AndroidJUnitRunner | tee device-reports/instrumentation.txt
python3 starlink/verify_device_output.py device-reports/instrumentation.txt
