#!/usr/bin/env bash
# Isolated synthetic Android QA. Does not access M81 accounts or private streams.
set -Eeuo pipefail
cd "$(git rev-parse --show-toplevel)/native"

status=0
run_suite() {
  local suite="$1"
  local filter="$2"
  echo "::group::Android QA ${suite}"
  rm -rf app/build/outputs/androidTest-results/connected
  if ./gradlew --no-daemon --max-workers=1 -I consolidation-qa.init.gradle \
      :app:connectedParaVoceQaAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.class=${filter}"; then
    echo "Suite ${suite}: Gradle completed successfully"
  else
    echo "::error title=Instrumented QA::Suite ${suite} failed; retain XML evidence"
    status=1
  fi
  mkdir -p "app/build/qa-fixtures/${suite}"
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then
    cp -a app/build/outputs/androidTest-results/connected/. "app/build/qa-fixtures/${suite}/"
  fi
  echo "::endgroup::"
}

# Separate filters prevent the method-specific playback selection from masking
# or replacing the three personalization tests in Gradle's XML output.
run_suite for_you play.ott.nativeapp.ui.ForYouConsolidationInstrumentedTest
run_suite stop_background play.ott.nativeapp.ui.RealActivityPlaybackInstrumentedTest
run_suite catalog_ui play.ott.nativeapp.ui.NativeUiInstrumentedTest
run_suite customer_ui play.ott.nativeapp.ui.CustomerUiInstrumentedTest
run_suite storage play.ott.nativeapp.data.StorageInstrumentedTest
run_suite native_player play.ott.nativeapp.playback.NativePlaybackInstrumentedTest
run_suite hls_player play.ott.nativeapp.playback.HlsPlaybackInstrumentedTest
run_suite drm_player play.ott.nativeapp.playback.ClearKeyPlaybackInstrumentedTest

exit "$status"
