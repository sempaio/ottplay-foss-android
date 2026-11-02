# Signed builds and publication

The version is stored in `version.properties`: currently `0.2.1`, `versionCode=7`. Increase the version code before every new published preview or production release. The workflow does not move an existing tag, replace a release or overwrite assets.

Preview and stable share one Android package ID and certificate. The optional
production application has a separate identity:

- **Preview:** `play.ott.foss.nativeapp.preview`, an R8-optimized APK and a signed AAB; next tag `v0.2.1-preview.7`. The final tag component is the `versionCode`. New previews are GitHub prereleases for testing, with no store submission. The previous [v0.2.1-preview.6](https://github.com/open-ott-play/ottplay-android/releases/tag/v0.2.1-preview.6) was promoted to stable without changing its signed assets.
- **Stable:** the exact qualified preview APK/AAB, promoted from prerelease to the
  latest stable GitHub Release. It updates the existing preview installation with
  the same package ID and signing certificate. The historical tag and Android
  `versionName` keep their `preview` text; only the release's status and notes change.
- **Production:** `play.ott.foss.nativeapp`, APK and AAB signed with an explicitly supplied production/upload key; next tag `v0.2.1`. Missing production secrets stop the workflow. Preview/debug key fallback and unsigned APK publication are prohibited.

Both channels enforce HTTPS for remote sources, provider APIs, artwork, EPG, media segments and DRM requests, including redirects. The separate `full` build uses application ID `play.ott.foss.nativeapp.full` and permits HTTP for legacy personal sources. It is built with `./gradlew :app:assembleFull` and is not a Play submission artifact. Debug also permits HTTP for local test fixtures and is not publishable.

When upgrading from an older build, cached catalogs containing private HTTP headers require a successful refresh before playback. The app displays a refresh instruction and preserves the encrypted old rows, source settings and preferences. This establishes the permitted server for each credential-bearing header; explicit per-entry playlist headers can target the entry's media origin, while source-level headers stay scoped to the source server.

The preview installs alongside the earlier 0.1.0 app. Because its package ID differs, Android does not transfer the database or Keystore automatically: export sources from the old version and import the file into the preview. The export contains plaintext credentials; keep it local. Later previews can update an existing preview when the same signing key is retained and `versionCode` increases.

## HTTPS certificate compatibility

The source now rejects an HTTPS origin before sending an HTTP request when
any certificate in its verified chain uses an undersized or unsupported key.
This also applies to HTTPS sources in the Full distribution. Existing servers
with weak leaf, intermediate or root keys must renew the affected chain using
supported keys; disabling hostname/CA validation or switching to plaintext is
not a safe workaround. See the [certificate policy and validation scope](security-design.md#https-certificate-key-policy).
The recorded source for preview.5 already includes this certificate-key policy.
Later changes on `main` still require their own qualification and a new release;
the existing preview assets and version metadata are not rewritten.

## Changes delivered in preview code 6

- Preserve semantic audio and subtitle preferences across streams instead of
  binding them only to transient track indexes.
- Update the vendored shared-core package and remove Android compiler warnings
  while retaining the existing preview signing identity and application ID.
- Refresh the project assessment and SDK evidence. Existing published preview.5
  artifacts keep their original source and validation records.

## Changes prepared for preview code 7

- Update Kotlin plugins together to 2.4.21 and group future updates.
- Keep CodeQL actions on one compatible version and group future updates.
- Refresh the verified shared-core receipt and declare repository code owners.

The next preview uses version code 7 and can update a preview installation signed
with the established certificate. Publication still requires successful host,
phone and Android TV checks on the exact release commit. Production signing and
store submission remain separate; preview artifacts are not production packages.

## Stable preview certificate

Preview signing and the five `PREVIEW_SIGNING_*` secrets are configured for `open-ott-play/ottplay-android` with the owner's explicit consent. The public certificate SHA-256 is `2021e3c927fff7c42daf395beacbf0ef738c6d878a827091afb48ddaa32c4dd8`.

The current stable release, promoted from preview.6, has `release-manifest.json` and `SHA256SUMS` assets identifying commit `a2089cd1dc0af427458c6313e7f825c8a35d37d5`, package `play.ott.foss.nativeapp.preview`, version `0.2.1-preview`, and `versionCode=6`. Use those assets when verifying its APK or AAB. A future publication must increase the code beyond 6; do not reuse the published tag.

The [historical release report](validation/native-0.2-preview-release-results.json) records the source build ID, APK/AAB signatures and checksums for [v0.2.0-preview.2](https://github.com/open-ott-play/ottplay-android/releases/tag/v0.2.0-preview.2).

`scripts/configure-preview-signing.py` creates a **preview-only** RSA key and stores the keystore and password JSON outside the repository with permissions `0600`. Repeated calls reuse the existing key and check its fingerprint; an incomplete file pair causes an error instead of silent key rotation. The Android SDK debug key is not used. Creating the key and uploading it to GitHub require the owner's explicit authorization.

```bash
python3 scripts/configure-preview-signing.py \
  --keystore "$HOME/.android/ottplay-native-preview.keystore" \
  --credentials "$HOME/.android/ottplay-native-preview-signing.json" \
  --repository open-ott-play/ottplay-android
```

With `--repository`, the script passes values to `gh secret set` through stdin. Console output contains only backup paths and the public certificate SHA-256. Do not put the private key, passwords or credentials JSON in Git, artifacts, messages or logs. Keep a protected backup outside this computer: losing the key prevents updates to the installed preview.

Five repository secrets are required with the `PREVIEW_SIGNING_` prefix: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `CERT_SHA256`. Production uses the same five suffixes with the `RELEASE_SIGNING_` prefix. SHA-256 is 64 hexadecimal characters without colons.

## Upload identity

This section applies only to the separate production application. It is not
needed for preview → stable updates of the existing preview installation.

For an existing Play app, retain its registered upload key or complete Google's upload-key reset procedure. Never create an unrelated replacement and assume it can update that app. `scripts/configure-upload-signing.py` validates an owner-only keystore/credentials pair, its expected certificate and access to the private key. Debug and preview identities are rejected.

Only after the owner confirms that a new upload identity is appropriate:

```bash
python3 scripts/configure-upload-signing.py \
  --keystore "$HOME/.android/ottplay-native-upload.p12" \
  --credentials "$HOME/.android/ottplay-native-upload-signing.json" \
  --create-new \
  --repository open-ott-play/ottplay-android
```

Omit `--create-new` to validate and reuse an existing pair. Omit `--repository` for local validation only. The script refuses to replace any existing release secret and never prints passwords or key material. Keep protected off-device backups. Creating/configuring an upload key does not enroll Play App Signing or submit an app.

## Languages and reviewer captures

The current source includes all 20 OTT-play FOSS interface languages, follows Android language preferences and falls back to English. The manual picker is under **More / Settings → Language**; **System default** clears the app override. AAB language splitting is disabled so a user can switch to any supported language offline after installation.

Run `python3 scripts/validate-localization.py` to check resource completeness, format arguments and picker/locale agreement. This structural check does not establish translation quality or device behavior.

Before recording reviewer steps or screenshots, select the intended app language explicitly and record it with the candidate's commit and artifact hash. The English walkthrough in [PLAY-CONSOLE-SUBMISSION.md](PLAY-CONSOLE-SUBMISSION.md) uses the current resource labels. Compose instrumentation should use the stable tags `open-settings`, `settings-language`, `language-list` and `language-<tag>` (including `language-system` and `language-he`) rather than Russian or English display text. Existing recordings and validation reports keep their original build and language provenance; they do not validate the new language picker.

## Local builds

Use JDK 17, Android SDK `platforms;android-37.0` and `build-tools;36.0.0`. The Wrapper pins Gradle and verifies the distribution SHA-256.

```bash
python3 scripts/release.py build --channel preview \
  --credentials "$HOME/.android/ottplay-native-preview-signing.json"
```

For an existing production key, securely set `OTTPLAY_KEYSTORE` (an absolute path), `OTTPLAY_STORE_PASSWORD`, `OTTPLAY_KEY_ALIAS`, `OTTPLAY_KEY_PASSWORD` and `OTTPLAY_EXPECTED_CERT_SHA256`, then run `python3 scripts/release.py build --channel production`. Passwords are not passed as process arguments. A JSON file with these field names and permissions `0600` is also accepted through `--credentials`.

The command runs core/app unit tests, lint for the selected variant, and `assemblePreview`/`bundlePreview` or `assembleRelease`/`bundleRelease`. It verifies the APK with `apksigner`, compares its signer with the expected certificate, checks the APK package ID/versionCode/versionName with `aapt2`, then verifies the AAB with `jarsigner -verify -strict` and checks its certificate fingerprint. Any failure stops the release. Ordinary host CI may still build an unsigned release to check compilation; that is not a publishable artifact for this channel.

Output is written to `build/distributions/<tag>/`: signed `.apk` and `.aab` files, a `release-manifest.json` containing the commit/package/version/fingerprint/sizes/hashes, and `SHA256SUMS`. Install the APK with `adb install -r <apk>`; an AAB cannot be installed directly. Check a downloaded set with `shasum -a 256 -c SHA256SUMS` on macOS or `sha256sum -c SHA256SUMS` on Linux.

## Releasing through GitHub Actions

The normal delivery path for the current application is **preview → stable**.
`production` remains available for a separately configured application; it does
not replace an installed preview.

1. Commit the new version and all changes to `main`. **Android native** automatically runs host, phone API 35 and real Android TV API 36 jobs on pushes to `main` and merge-queue commits, except for proven documentation-only changes. Ordinary PR updates run host checks without the emulator matrix. Wait for all three jobs on the exact release commit. If its automatic run was skipped, run **Android native** manually with `run_device_tests=true`; skipped jobs cannot qualify a release. PR and merge-queue runs validate changes but cannot satisfy the publication gate. Ordinary APK artifacts and successful reports expire after one day, and failed/cancelled reports after three; the gate uses successful job records, not downloadable artifacts. Signed release artifacts and reports keep their 30-day retention, and published APK/AAB assets remain attached to the GitHub Release.
2. Confirm that the five signing secrets for the selected channel are configured. Run **Signed Android release** manually on `main` and select `preview` or `production`.
3. The workflow checks a clean checkout, matching SHA, an unused tag, increasing version/code and a successful full matrix on the same commit. It then repeats host tests/lint, builds and verifies both signatures, and produces checksums.
4. The key is decoded only into a private temporary runner directory and removed in an `always()` step. Configuration cache is disabled for signed builds. Artifacts contain release files and reports, not the keystore. The build job has read-only repository access.
5. A separate publish job with `contents: write` downloads only this run's verified assets; it receives no signing secrets. Checks are repeated before publication. A new Git ref is created atomically, a draft release is created with all assets, and the release is then published. A preview is marked as a prerelease and does not become the latest production release.

The script enforces append-only behaviour within its pipeline. It does not change GitHub repository rules or prevent an administrator from manually changing a tag or release. The owner can enable GitHub immutable releases or tag rules for that protection. If upload/publication stops after creating the tag or draft, another run refuses to overwrite it: investigate the cause, then use a new version or explicitly manage the unfinished draft manually.

### Promote the current preview to stable

After the preview workflow has completed successfully, run **Signed Android
release** again on `main` with `channel=stable`. Keep `main` at the qualified
preview commit until promotion completes. The job checks the full device matrix,
the preview tag, package/version/certificate, and the successful signed-build run
and attempt recorded in its manifest. It downloads both the published assets and
the original workflow artifact and requires identical bytes, sizes and checksums.
It then marks that same GitHub Release stable and latest, preserving its tag and
every asset. No keystore or password is loaded, and no APK/AAB is rebuilt.

Promote within the signed artifact's 30-day retention. If the artifact has expired,
the source has changed, or an older preview lacks run provenance, publish and
qualify a new preview with a higher version code. The job fails rather than
claiming that different or unavailable bytes were verified. Release operations
share a concurrency group so building and promotion cannot race each other.

Install the stable APK with `adb install -r <apk>`. It updates earlier previews
signed by the same certificate without uninstalling or migrating app data; when
that exact preview is already installed, the bytes are already the stable build.
Keep the signing key for all future preview and stable updates. The separate
production package remains an independent app.

Signing an APK and AAB does not publish them to Google Play. With Play App Signing, the AAB is signed with an upload key and Google signs user APKs with the app signing key; an APK signed with the upload key may not update an app installed from Play. Store policies, Play Console, ownership of the production identity and physical-device validation remain separate steps. See [Android app signing](https://developer.android.com/studio/publish/app-signing) and the [GitHub Releases API](https://docs.github.com/en/rest/releases/releases).

Release tooling checks: `python3 -m unittest discover -s scripts/tests` and `actionlint .github/workflows/release.yml`. They verify rejection of missing signing configuration, production fallback, reused tags/codes and a missing successful full device matrix. Each signed build also verifies the actual signatures.
