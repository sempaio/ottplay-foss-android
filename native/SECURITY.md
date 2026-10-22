# Security policy

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/open-ott-play/ottplay-android/security/advisories/new).
If the form is unavailable, contact a maintainer through the repository's GitHub
profile to arrange a private channel before sharing sensitive details. Public
issues are for non-sensitive defects and feature requests.

Include the affected commit/release, prerequisites, a minimal synthetic
reproduction, expected and actual results, and impact. Do not include live
credentials, personal data or access to devices you do not own.

## Response and supported versions

Maintainers aim to acknowledge a private report within 14 days, investigate its
scope, and agree on remediation and disclosure with the reporter. If no reply
arrives within 14 days, follow up privately. Confirmed security defects are
prioritized by impact; critical defects take precedence over feature work.
Address confirmed critical vulnerabilities in maintained project code
immediately and other confirmed medium-or-higher vulnerabilities within 60 days
of public disclosure. Record upstream limitations and practical mitigations;
documentation alone does not make an unresolved vulnerability fixed.

Security fixes target the current default branch and latest published release,
where one exists. Older snapshots are not maintained security branches. Release
notes must identify security fixes, affected versions and upgrade actions without
disclosing credentials. This policy is a commitment for handling reports, not
a claim that no vulnerabilities exist or that past reports met a response SLA.

## Project boundary

This project provides the native Kotlin/Compose Android and Android TV player.

Untrusted playlists, guide metadata, provider URLs and settings imports enter a native media application. Source settings use Android Keystore-backed AES-GCM, while manual JSON exports are unencrypted and may contain secrets. Release/preview builds require HTTPS; the separate Full sideload build permits legacy HTTP. Scope provider headers to their intended requests and never forward DRM license headers to provisioning endpoints.

See [security design and validation boundaries](docs/security-design.md).
