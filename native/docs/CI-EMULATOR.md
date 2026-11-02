# CI phone rendering budget

The pinned shared toolkit classifies the complete Git diff before Android CI
installs its toolchains. Changes limited to ordinary documentation, including
`store/README.md`, skip the host and emulator jobs. `docs/privacy-policy.md` is
always a full-validation input because it generates the public privacy page.
App source, fixture Markdown, store assets, workflow changes and unknown paths
also run full validation.

Skipped jobs are not recorded as successful tests. Manual `android.yml` runs
always execute host checks; select `run_device_tests=true` to run both real
emulators and qualify that exact commit for a signed release. The signed release
workflow still requires a successful host, phone and TV matrix for its source
commit. Documentation-only CI cannot satisfy that requirement.

The API 35 Google APIs phone emulator uses a 720-by-1600 physical framebuffer
at 280 dpi. The original Pixel 7 profile uses 1080 by 2400 at 420 dpi. Scaling
both dimensions and density by two thirds preserves the 9:20 aspect ratio and
approximately 411-by-914 dp viewport while reducing framebuffer pixels by 55.6%.
The Android TV emulator continues to use its TV hardware profile.

`scripts/ci-emulator-display.py` replaces the AVD's physical dimensions, density,
and skin settings before boot. It then reads `wm size` and `wm density` and
requires the expected physical configuration with no runtime display overrides.
The setup uses AVD properties instead of the [deprecated display command-line options](https://developer.android.com/studio/run/emulator-commandline#deprecated).

The device-report artifact records the AVD configuration, display verification,
startup pressure, emulator log, logcat, and system ANR traces. These distinguish
a correctly configured emulator from a successful test run: both are required.

The full phone and TV test suites still run once, including actual decoding,
fullscreen controls, the first-use immersive prompt, Home, and phone PiP.
App assertions and system ANR failures remain active. CI framebuffer sizing
does not establish physical-device or high-resolution rendering acceptance.
