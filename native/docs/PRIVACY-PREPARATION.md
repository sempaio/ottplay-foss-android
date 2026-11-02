# Privacy publication preparation

The offline privacy screen is implemented in `app/src/main/java/play/ott/nativeapp/ui/PrivacyPolicy.kt` and is available from onboarding and settings. Its factual content is centralized in `privacyPolicySections()`. Transport wording follows the build's `BuildConfig.ALLOW_INSECURE_HTTP` flag. Publisher identity and contact details are centralized in `PrivacyPublisher`: `alvit` and `alvit.work@gmail.com`, matching the OTT-play project's existing public policy. The English public copy is `docs/privacy-policy.md`.

The standalone public policy has been published at **[https://astral-oasis-sbqd.here.now/](https://astral-oasis-sbqd.here.now/)** using the project's authenticated permanent here.now account. The URL is recorded in `store/contact.json`; the page is built from `docs/privacy-policy.md` by `scripts/build-privacy-site.py` into `docs/site/index.html`. The Native repository is private, so repository URLs are not public policy or support destinations.

**Play Console has not yet been filled by this preparation.** Publication of the policy page is separate from app submission and device acceptance. Before a Play submission, the release owner must:

1. Use the same publisher identity and support contact in the final Play listing. Update `PrivacyPublisher` and the public copy together if the publishing entity changes.
2. Generate the public policy for the intended build: Play/release and preview use HTTPS-only wording, whereas HTTP-compatible full builds use an explicit cleartext warning. Do not publish blanket encryption-in-transit statements that also cover the HTTP-compatible build.
3. Enter the published URL above in Play Console. Rebuild and republish the same site when the public text changes, and keep the public and offline copies consistent. Check public accessibility again before submission. The app's offline reader does not depend on a browser or a network connection.
4. Complete Data safety using the implemented data flows and relevant Google exceptions. Absence of analytics does not mean that provider credentials and playback requests never leave the device. User-directed transfers are not automatically reportable sharing.
5. Record actual results for the final text and interaction on both phone and Android TV. `PrivacyPolicyInstrumentedTest` covers opening from onboarding/settings, TV directional reading, closing, and TV settings visibility; the existence of the tests is not a passing result.

The filled submission draft and its remaining evidence requirements are in [PLAY-CONSOLE-SUBMISSION.md](PLAY-CONSOLE-SUBMISSION.md).

The policy distinguishes encrypted source/catalog storage from other private local state; plaintext user-requested backups; provider, EPG, artwork and DRM requests; and removing app data versus deleting the provider's account. If deletion or network behavior changes, update these descriptions before publishing.

Google requirements: [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en) and [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
