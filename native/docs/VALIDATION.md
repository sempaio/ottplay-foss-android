# Validation

Validate the exact source revision and artifact intended for distribution.
Record the commit, build variant, device image, test results and artifact hashes.
A successful emulator run does not establish physical-device compatibility or
store approval.

## Host checks

Use the JDK, Android SDK and Gradle Wrapper configured in the repository:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:assembleFull :app:assembleDebugAndroidTest
python3 -m unittest discover -s scripts/tests -v
python3 scripts/validate-localization.py
python3 scripts/validate-store-assets.py --require-screenshots
actionlint
git diff --check
```

The JVM tests cover source parsing, provider protocols, transport restrictions,
credential scoping, state persistence and playback control. The Python suite
covers release preflight, signing preparation, store assets and CI tooling.
Review lint output separately from test results.

## Device checks

Start a dedicated emulator or connect a test device, then run:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Run the complete suite on both the phone API 35 Google APIs image and Android TV
API 36 image used by CI. Manual runs of the **Android native** workflow enable
both with `run_device_tests=true`. An ordinary branch check without device jobs
is insufficient to qualify a release.

Instrumentation exercises Activity interaction, touch and D-pad navigation,
privacy access, encrypted storage, source refresh, HLS, platform ClearKey,
phone PiP, TV Home pause and playback-service lifecycle. The offline demo
contains silent audio; test audible playback with an authorized source when
needed. [CI-EMULATOR.md](CI-EMULATOR.md) describes the device configuration and
diagnostic reports.

Inspect failed assertions together with logcat, window/input state and system
ANR reports. A readiness timeout or system crash is a failed device run. A
passing targeted rerun does not represent a fresh complete-suite result.

## Release artifacts

Follow [RELEASING.md](RELEASING.md) to build and verify signed APK/AAB files.
Check the package, version, signing certificate and checksums of the exact files
being distributed. Install the APK on the intended phone and TV test devices
and check source loading, playback, remote or touch controls, pause, Home and
returning to the app. Keep account credentials and private keys out of reports.

Store screenshots and the demonstration video are described in
[store/README.md](../store/README.md). Their
[capture metadata](../store/capture-provenance.json) identifies the builds and
files used; recapture them when the submission candidate changes.

Physical-device decoders, hardware DRM, paid-provider acceptance, 16 KB runtime
behavior, delivered split APKs and Google Play certification require their own
checks. The [submission guide](PLAY-CONSOLE-SUBMISSION.md) covers store
preparation and the separate publication requirements.

## Compiler and tool diagnostics

The Kotlin build retains compiler warnings, and Gradle runs with
`org.gradle.warning.mode=all` so individual build-tool deprecations remain visible.
The 2026-10-16 review removed impossible nullable branches under OkHttp 5.4,
uses current Media3 track defaults, migrates Compose instrumentation rules to
`junit4.v2`, and replaces the deprecated Gradle task-registration delegate.
The complete instrumentation suite must pass after the Compose scheduler change;
compilation alone does not establish equivalent UI behavior.

Three narrowly scoped compatibility uses intentionally remain: the old Android
TV feature flag for supported devices without leanback, MediaItem bundle round
trips that exercise old controller IPC, and a rejected `ACTION_MULTIPLE` input.
Each has a local comment and `DEPRECATION` suppression at the corresponding
helper/test. No file-wide or project-wide warning suppression is used.

AGP 9.4.1 currently calls Gradle's deprecated `Configuration.setVisible(boolean)`
during configuration. This is an upstream build-plugin compatibility diagnostic,
not a source/compiler warning or an instruction to disable diagnostics. Track it
when updating AGP/Gradle; the current Gradle 9.8.1 build remains supported. The
SDK setup tool can separately report its own CLI deprecation on CI runners.
