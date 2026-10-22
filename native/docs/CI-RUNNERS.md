# CI runner selection

The repository variable `CI_RUNNER_MODE` selects runners before jobs start.
Only `k3s` opts in; `github`, an empty value or any other value preserves the
existing hosted runners. This does not change public `ottplay-foss` workflows.

The Android workflows use three fixed ARC scale-set names for trusted main runs:

- `ottplay-k3s-linux-x64`: reusable scope and unsigned build/test jobs.
- `ottplay-k3s-kvm-x64`: both phone API 35 and Android TV API 36 instrumented jobs.
- `ottplay-k3s-release-x64`: signed build and publication, isolated from build/test jobs.

Scope receives its runner directly through the pinned reusable workflow input;
there is no hosted bootstrap. Existing job names, event rules and dependencies
remain intact. Publication still requires a successful complete device matrix
for the exact release SHA, verifies signatures/checksums, and cannot overwrite
an existing release. Changing pools does not bypass that gate.

## Public repositories and event admission

Both repositories are public. The opt-in applies only to `push`,
`workflow_dispatch` or `schedule` on `refs/heads/main`. All pull requests
(including same-repository PRs), merge queues and manual runs on other branches
stay on GitHub-hosted runners even when `CI_RUNNER_MODE=k3s`. Step provisioning
uses `runner.environment`, so a hosted PR still installs its OS dependencies.

Workflow conditions are routing defaults, not a security boundary against a PR
that edits YAML. Before admitting these public repositories, runner-group or
controller admission must restrict self-hosted jobs to the reviewed workflow
files on `refs/heads/main`. Do not enable a repository-wide unrestricted public
runner pool. If the deployment cannot enforce trusted-main workflow admission,
leave the pools disabled and use GitHub-hosted execution. Smoke jobs are manual
and main-only, but need the same infrastructure enforcement. Adding these files
does not provision pools, change repository variables or trigger admission.

## Admission and switching

Provision all three pools before enabling `k3s` for this repository. Review the
current `main` commit and record its full 40-character SHA, then dispatch
**k3s runner admission** (`runner-smoke.yml`) on that current `main`. This workflow
ignores the mode variable and can therefore start pools that have no idle pods.
Its stable jobs are `smoke-linux`, `smoke-kvm`, and `smoke-release`, on the
corresponding fixed labels above. It uses no signing secrets.

Require all jobs to succeed on the reviewed SHA within the last 24 hours and
verify their runner labels. Supply `--expected-sha FULL40 --smoke-run RUN_ID` to
the infrastructure operator before setting the repository variable to `k3s`.
The reviewed SHA must still match both current `main` and the smoke run; review
and smoke a new commit if `main` advances. This explicit review does not assume
paid branch-protection features. A runner merely being registered/online does
not prove that KVM works.
Explicitly set `CI_RUNNER_MODE=github` to return to hosted execution. Already
queued/running jobs keep their original selection; cancel and rerun them
explicitly if necessary. These workflows do not mutate variables or automatically
rerun failures in another pool.

## Image and KVM requirements

Use Ubuntu 24.04 x86_64 with `bash`, Python 3 plus pip, `git`, `curl`, `jq`, `gh`,
`unzip`, `tar`, `gzip`, and writable `RUNNER_TEMP`/`RUNNER_TOOL_CACHE`. Emulator
images also need `timeout`, `free`, and the Android Emulator Linux dependencies
(including X11, XCB, PulseAudio and NSS libraries). JDK 17 is selected by the pinned
setup action; the release smoke checks `keytool`, `jarsigner` and `apksigner`.
Allow package/toolchain downloads and enough ephemeral storage for SDK packages,
build outputs and the phone/TV system images and userdata.

The KVM pool must expose a real `/dev/kvm` character device with read/write access
to the runner user. The preflight opens it and verifies KVM API version 12;
`emulator -accel-check` then verifies the installed emulator. A `kvm` group without
the device is insufficient. Keep the KVM pool disabled and this repository on
hosted mode until nested virtualization and the Kubernetes device-plugin/resource
admission are available. No workflow creates devices or changes host permissions.
The smoke does not boot a guest or replace the complete instrumented test matrix.

Before `setup-android`, self-hosted jobs allocate a unique SDK beneath
`RUNNER_TEMP` and export **both** `ANDROID_HOME` and `ANDROID_SDK_ROOT`, plus
`ANDROID_USER_HOME`. The action installs command-line tools there. The image SDK
and other jobs' files are untouched. Hosted-only NDK/CMake deletion and sudo/udev
setup remain explicitly excluded on self-hosted runners. Signing material is
still removed by the existing unconditional cleanup step; release pods must also
be disposable and restricted to trusted release jobs.

Each manual smoke must also pass the image-owned `/opt/ottplay/smoke.sh`
for its pool. This checks UID 1001, absence of host Docker/containerd sockets and
a Kubernetes API token, writable paths, Python venv support, and HTTPS access. A
missing script fails admission; the KVM variant additionally checks acceleration.

Local contracts: `python3 -m unittest discover -s scripts/tests -v` and
`actionlint .github/workflows/*.yml`. Local mocks validate routing and rejection
paths; only a successful live admission run certifies the provisioned pools.
