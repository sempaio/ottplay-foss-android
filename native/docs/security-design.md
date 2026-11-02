# Security design and verification

## Scope and trust boundaries

The project provides the native Kotlin/Compose Android and Android TV player.

Untrusted playlists, guide metadata, provider URLs and settings imports enter a native media application. Source settings use Android Keystore-backed AES-GCM, while manual JSON exports are unencrypted and may contain secrets. Release/preview builds require HTTPS; the separate Full sideload build permits legacy HTTP. Scope provider headers to their intended requests and never forward DRM license headers to provisioning endpoints.

## HTTPS certificate key policy

Every owned OkHttp transport created through `RemoteTransportPolicy.secure`
checks the platform-verified certificate chain before sending an origin HTTP
request. This includes the selected trust anchor, redirects, pooled connections
and resumed TLS sessions. Normal platform trust, hostname verification and
certificate pinning remain enabled; no global TLS defaults are replaced.

The minimums are RSA 2048-bit modulus with a positive odd exponent of at least
3, EC 224-bit field **and** subgroup order, or DSA 2048-bit prime and 224-bit
subgroup. Ed25519 and Ed448 are accepted only when a named JCA decoder confirms
the encoded key form. Unknown key types fail closed. These requirements apply
to HTTPS in both the strict and HTTP-compatible distributions. Plain HTTP in
the Full/debug distributions has no TLS certificate protection.

This is a certificate-key policy for the owned HTTP clients, not a claim about
every TLS key-exchange parameter, external player, proxy or device. It does not
prevent an authorized remote server from acting maliciously. Proxy CONNECT
negotiation precedes origin TLS; the guard protects origin HTTP requests, not
proxy authentication. The application has no owned WebSocket transport; any
future WebSocket implementation needs a separate review because OkHttp network
interceptors do not cover WebSockets.

Real Android API 30 / AndroidOpenSSL tests show that the unmodified provider
accepts trusted RSA 1024-bit leaf, intermediate and root certificates. With the
guard, each is rejected before HTTP headers/body are sent, under TLS 1.2 and
1.3. Strong RSA/EC, untrusted-CA and wrong-host controls are included. Additional
tests prove enforcement after a 307 redirect, on a reused connection, and on an
actually resumed TLS 1.2 session identified by matching nonempty session IDs.
JVM tests independently check exact key-size boundaries and real named Edwards
provider keys. Other Android versions run through the normal CI device matrix;
these results do not assert that a physical device or published APK was tested.

After building and installing the debug/test APKs on a test emulator, run the
focused regression using that emulator's explicit serial from `adb devices`:

```sh
adb -s TEST_EMULATOR_SERIAL shell am instrument -w -r \
  -e class play.ott.nativeapp.security.TlsKeyStrengthInstrumentedTest \
  play.ott.foss.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
```

## Source and operating documentation

- [docs/privacy-policy.md](../docs/privacy-policy.md)
- [docs/MIGRATION.md](../docs/MIGRATION.md)
- [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml)
- [README.md](../README.md)

## Regression evidence

- [app/src/test/java/play/ott/nativeapp/playback/RequestPolicyTest.kt](../app/src/test/java/play/ott/nativeapp/playback/RequestPolicyTest.kt)
- [app/src/test/java/play/ott/nativeapp/playback/ScopedDrmCallbackTest.kt](../app/src/test/java/play/ott/nativeapp/playback/ScopedDrmCallbackTest.kt)
- [app/src/test/java/play/ott/nativeapp/data/SettingsBackupPolicyTest.kt](../app/src/test/java/play/ott/nativeapp/data/SettingsBackupPolicyTest.kt)

Run the documented commands in [CONTRIBUTING.md](../CONTRIBUTING.md) and the
[CI workflow](../.github/workflows/android.yml). Preserve negative tests for rejected inputs,
unavailable dependencies, authorization failures and cancellation. A passing
test run describes its fixtures and environment; it does not certify every
upstream service, hardware model or production deployment.

## Remaining security assessment

Review every distribution variant against transport/cryptography criteria and record exact device/emulator evidence. Signed release metadata and privacy-policy behavior must match the released APK.

Report new issues through [SECURITY.md](../SECURITY.md). An OpenSSF assessment
records evidence and applicability; it is not a guarantee that a system is safe.
