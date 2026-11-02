# OpenSSF Passing evidence

This is a source-backed work record for the [OpenSSF Best Practices Passing
criteria](https://www.bestpractices.dev/en/criteria/0). The
[machine-readable assessment](../.bestpractices.json) is a reviewed point-in-time
snapshot of all 67 Passing answers and their justifications, synchronized on
2026-10-17 from the saved public assessment. The
[official project 15592](https://www.bestpractices.dev/en/projects/15592) is the
authoritative current assessment; its Passing progress was 91% at this snapshot.
Unmet cryptographic and dependency criteria remain explicitly unmet.

## Available evidence

- Purpose, installation and operating limitations: [README](../README.md).
- Public Git source, commit history and change proposals: [open-ott-play/ottplay-android](https://github.com/open-ott-play/ottplay-android).
- Bug reports and retained discussion: [issues](https://github.com/open-ott-play/ottplay-android/issues).
- Contribution requirements, test policy and test invocation: [CONTRIBUTING](../CONTRIBUTING.md).
- Private reporting process and response policy: [SECURITY](../SECURITY.md).
- Trust boundaries, source references and negative-test evidence: [security design](security-design.md).
- Build/test automation: [workflow](../.github/workflows/android.yml) and [actual run results](https://github.com/open-ott-play/ottplay-android/actions).
- Project license: [MIT](../LICENSE); third-party terms still apply.

## Initial scanner inventory — 2026-10-08

The authenticated GitHub inventory found 9 open code-scanning alerts
(including Scorecard policy findings) and 0 open Dependabot alerts.
This is a dated baseline, not a statement about the post-merge state. A scanner
configuration error or disabled scanner must not be recorded as zero findings.
Check [Code scanning](https://github.com/open-ott-play/ottplay-android/security/code-scanning) and
[Dependabot](https://github.com/open-ott-play/ottplay-android/security/dependabot) after the changed default branch is
analyzed.

## Unresolved criteria and applicability

Review every distribution variant against transport/cryptography criteria and record exact device/emulator evidence. Signed release metadata and privacy-policy behavior must match the released APK.

On 2026-10-16 the maintainer confirmed that secure-design/common-error knowledge
and the absence of outside-GitHub bug, enhancement and vulnerability reports
over the preceding 12 months apply to this project. The public issue inventory
contains no non-PR reports, and private vulnerability reporting is enabled.
The current open code-scanning inventory contains eight Scorecard process
findings and no CodeQL source finding. Zero open Dependabot alerts are an
inventory result, not a complete dependency audit.

The current signed preview is
[v0.2.1-preview.5](https://github.com/open-ott-play/ottplay-android/releases/tag/v0.2.1-preview.5),
with version code 5 and source `b34fd3ff8741eac40db78a81864d34771d6b3156`.
Its public release manifest records the same preview signing certificate as
the release runbook. This source already includes the exact certificate-key
policy; later source changes still need separate release qualification.

Review each cryptographic criterion across the complete application, external
DRM/platform components and build/release tools. Measure whole-source coverage
and review per-language analysis; a passing host suite or one TLS test matrix
does not establish all these properties. Do not transfer another repository's
test results or badge identifier.

[Governance](../GOVERNANCE.md), [conduct rules](../CODE_OF_CONDUCT.md), and the
[roadmap](../ROADMAP.md) describe present responsibilities and limitations.
No backup maintainer or tested one-week access-recovery mechanism exists.

Record each completed analysis with its source SHA, commands/tool versions,
result and material limitations. The README links this repository's saved public
assessment through its own dynamic badge; the local snapshot does not replace
the current assessment.

## Release notes and source-history scan (2026-10-16)

All four published previews now have substantive behavior and upgrade notes.
Preview.4 introduced the explicit HTTPS certificate-key floor; preview.5 closes
the playlist line-limit bypass. The preview.2 notes preserve the distinction
between its original build commit and the later documentation-history rewrite.
These documentation corrections did not replace any release asset or tag.
See the [published release notes](https://github.com/open-ott-play/ottplay-android/releases).

A default-rule Gitleaks 8.30.1 scan after unshallowing the repository inspected
85 commits (about 2.37 MB), using `gitleaks git --log-opts=--all`, with no path or detector
exclusions. Its single finding at `ClearKeyPlaybackInstrumentedTest.kt:251`
in commit `77f56c8d1945a2c70a03ae618927e8c15dd30ad1` is the public synthetic
ClearKey used to generate the bundled test media. The adjacent
`androidTest/assets/` generation instructions describe that key; the test uses
its local loopback license server and synthetic authorization values. It is not
an operator credential. No private credential was identified in this scan;
this does not guarantee detection of arbitrary unknown secret formats.

## Required build tools and current emulator qualification (2026-10-17)

The required application build uses Temurin/OpenJDK 17, Gradle 9.8.1,
Android Gradle Plugin 9.4.1, Kotlin 2.4.20, Android SDK platform 37.0
(`compileSdk = 37`) and build-tools 36.0.0. This Java/Kotlin application does not
require NDK, CMake, Xcode or a proprietary compiler. The reviewed Gradle and Kotlin
plugin POMs declare Apache-2.0. The actual SDK platform and build-tools packages
carry component-specific open-source notices; [section 3.5 of the SDK terms](https://developer.android.com/studio/terms) leaves
those components governed by their open-source licenses (see also [AOSP licensing](https://source.android.com/docs/setup/about/licenses)). This is a review of the
required build components, not a claim that every SDK package or Android firmware
component is free software. Google APIs and Android TV emulator images are test
fixtures rather than compiler inputs. Proprietary DRM runtime limitations remain
separate and unresolved in the assessment.

The installed platform 37.0 `NOTICE.txt` SHA-256 was
`6f3c947e9827793a4984bd5d0e4a306b76c95a65186fe779feec748fc739c56f`;
its `package.xml` SHA-256 was
`bdfcb4979ce2bad0768f44afb5f876acbcc5359ee43ed492fe2a0a197932e70d`.
The build-tools 36.0.0 notice SHA-256 was
`9387f47fb60a6ce126f45ec6030950bc205b0fedf86dc7b82cb4349ac15be3b6`.
These identify the inspected packages, not every future SDK distribution.

[Integration run 39000272962](https://github.com/open-ott-play/ottplay-android/actions/runs/39000272962)
passed the build/unit/lint job and both Android TV API 36 and phone API 35
instrumentation jobs for merged source
`94b18bb21204fb51e9c96fbacd00487dafe749a0`. These are actual emulator results;
they do not certify physical devices, all DRM services or every operating-system
release. The warning handling and retained upstream tool notices are documented
in [validation guidance](VALIDATION.md).
