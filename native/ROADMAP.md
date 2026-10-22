# Maintenance roadmap

Priorities for October 2026 through October 2027:

- Keep the native player, source adapters, guide parsing, import/export and
  encrypted storage compatible with maintained Android and dependency versions.
- Preserve HTTPS in preview/release, origin-scoped credentials and exact TLS
  certificate-key checks. Evaluate changes separately from the optional Full
  HTTP compatibility distribution.
- Maintain source-bound host, phone and Android TV test evidence, including
  failure cases for imports, storage and credential forwarding. Do not infer
  physical-device or paid-provider acceptance from emulator results.
- Review external DRM/platform components and release-tool dependencies before
  asserting complete cryptographic qualification; record measured coverage
  rather than treating a configured threshold as an observed result.
- Keep documentation, published preview identities and toolchain instructions
  synchronized with their corresponding source and release manifests.
- Establish a real backup maintainer and access-recovery process before
  claiming continuity. None is currently available.

These are priorities rather than delivery dates. Subscription provision,
undocumented commercial-provider compatibility, Android versions below API 26,
and a guarantee of Google Play approval are outside the current scope.
