# Google Play submission draft — OTT-play Native

Prepared on **2026-09-13**, updated **2026-09-14**, for package **`play.ott.foss.nativeapp`**, candidate **0.2.1 / versionCode 3**. This is a filled working draft based on source behavior. **Play Console has not been filled or submitted by this document.** Device and release validation are tracked separately; no passing device result is asserted here.

**Current Console status, 2026-09-14:** authenticated inspection showed **Create app enabled**, contact-phone verification complete, and no app records. The account-verification gate observed on 2026-09-13 is historical and no longer prevents app creation. App creation, upload, app-content declarations and submission remain to be completed; an enabled button does not establish production-access eligibility or app approval.

Publisher: **alvit**. Support/privacy: **alvit.work@gmail.com**. Privacy-policy field: **https://astral-oasis-sbqd.here.now/**. Listing copy and graphics are in [store](../store/README.md). The Native repository is private; do not use its source or issue URLs as publicly accessible listing or reviewer resources.

## App access / sign-in details

Draft selection: **All functionality is available without access restrictions** for the player itself. The application has no publisher account, paywall, registration, OTP, membership check or geographic access gate. The offline demo exposes the player UI without sign-in. Optional provider accounts are external accounts entered by the user; they do not unlock a publisher-managed app tier. Do not classify this no-login player as requiring an app account merely because it accepts user-supplied provider credentials.

Instruction name: **Offline demo — no account required**.

Username/password: **leave unused for this instruction**; there is no demo login. Do not put invented credentials in these fields.

Copy-ready reviewer notes, wherever the live Console offers an instructions field or Google requests a walkthrough:

> The UI follows the Android language preferences, with English as its fallback. The in-app Language setting can override this and includes all 20 languages offered by OTT-play FOSS. On first launch, choose “Try the demo”, then open “Test pattern · 8 seconds”. This is an original eight-second offline video with silent audio. No account, network, payment or OTP is needed. “Privacy policy” opens the offline privacy policy. If already configured, open “More” / “Settings” and choose “Add a demo source”. Provider accounts are optional and belong to the user's chosen service. These button names are for English; choose Settings → Language → English if needed.

This permits review of setup, the library, basic playback, settings and privacy. It **does not demonstrate authenticated Xtream/Stalker, remote EPG, live catch-up or protected-service DRM behavior**. These optional bring-your-own-source paths are not an unconditional account prerequisite for reviewing the player. If Google requests access to a restricted external backend, the publisher must supply valid permitted test endpoints and credentials/resources in additional instructions. Do not claim that the local demo verifies those integrations. Google requires access details where functionality is restricted; the current help page calls this area **Sign-in details**. [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en).

## Reviewer walkthrough

1. Fresh install: read the welcome-screen privacy policy, close it with Back, and choose **Try the demo**. No permission dialog or provider login is required for this local demo.
2. The movie library contains **Test pattern · 8 seconds**. Select it using touch or TV D-pad/OK. The silent audio track is intentional.
3. Show playback controls and open **Player options** to inspect scaling, speed and available tracks. A track option depends on the selected media; the demo does not contain every format or subtitle configuration.
4. Back leaves fullscreen and exposes the current-playing bar. Stop using the player controls to end playback. Add/remove favorites and remove the demo via **Sources** to inspect local library controls.
5. Phone: inspect PiP on a supported device and the **Background playback** setting. TV: press Home during playback; playback should pause, and TV does not offer PiP or the phone background setting. Record actual results separately.
6. **Sources → Add by URL** exposes M3U/Xtream/Stalker configuration. The Play build accepts HTTPS only. Use authorized reviewer fixtures for network and provider scenarios; no commercial content rights are supplied by the app.

## Data safety draft

Scope this form to the Play package and all its active Play versions/tracks. The Full package is separate. The app has no configured publisher analytics, advertising or crash-reporting endpoint, but it sends provider requests off the device.

### Starting answers

- Does the app collect or share any of the listed user-data types? **Yes**, as a conservative draft for provider account identifiers, portal identifiers and requested playback content.
- Data encrypted in transit? **Yes for the HTTPS-only Play package's app-managed network requests**, subject to verifying the final AAB and all active Play versions. This answer must not be reused for the HTTP-compatible Full package. User-directed document exports are plaintext files, and the chosen document provider controls any later cloud upload.
- Does the app offer account creation? **No**. Entering an existing provider account is configuration, not creating an app account. The app-account deletion requirement is triggered by in-app account creation. [Account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
- Does the app provide deletion of all collected remote data? **Do not claim this badge on present evidence.** It deletes its local state and accepts support inquiries, but cannot delete an arbitrary provider's server logs/account. Describe those boundaries in the policy.
- Independent security review / Families commitment badges: **No claim**. No corresponding external certification or target-audience decision is established here.

The per-type labels below are proposed mappings for Console review, not a claim about an uninspected provider's retention. Draft all listed remote types as **optional** because users can use the bundled offline player without an account and choose which source to add. Their purpose is **App functionality**, including authentication to the user's chosen external provider. **Account management** describes an account with the app developer; this app does not provide such accounts, so entering external provider credentials does not establish that purpose. Do not select advertising, analytics or personalization purposes unless actual behavior changes. [Google's data-purpose definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

### Provider account information

**Draft types:** Personal info → **User IDs** (provider username/account identifier); Personal info → **Other info** for authentication secrets and access information when the current form requires a separate category. Google does not expose a dedicated password data-type label; confirm this mapping against the live form instead of inventing one.

**Flow:** entered source settings → provider API requests, and sometimes credential-bearing stream/guide URLs or configured headers. Xtream puts username/password in requests; Stalker sends the entered portal identifier and session bearer token. The receiving endpoint obtains the needed authentication data; the publisher has no intermediary account server.

**Draft answers:** collected **Yes**; sharing **Yes unless the documented user-directed-transfer exception is adopted for the particular recipient/flow**; optional **Yes**; ephemeral **No claim**, since provider logging/retention is not established. Code: `core/.../XtreamProvider.kt`, `StalkerProvider.kt`, `ProviderHttp.kt`; `app/.../data/NativeRepository.kt`.

### Portal and DRM identifiers

**Draft type:** Device or other IDs → **Device or other IDs**. The MAC-style portal identifier is entered by the user and is not read from device hardware. DRM challenges/provisioning are platform-generated; their exact device/account fields depend on the DRM system and receiving service.

**Flow:** Stalker authentication and native DRM license/provisioning requests. Draft collection **Yes**, optional **Yes**, purpose **App functionality**, including provider authentication and playback authorization. Keep sharing conservative where the destination or data is not sufficiently documented for an exception. Do not assert that the app collects advertising ID, IMEI or the hardware MAC: its source does not implement those reads. Code: `StalkerProvider.kt`, `app/.../playback/ScopedDrmCallback.kt`.

### Playback requests and background guide refresh

**Draft type:** App activity → **App interactions** for channel/programme/media requests sent to providers. This concerns remote requests identifying selected content, not a publisher analytics history.

**Flow:** selected media, segments, subtitles, keys, artwork and DRM endpoints; guide requests also occur through WorkManager approximately every six hours on an unmetered network. Providers and hosts receive IP/User-Agent connection metadata. No location lookup is implemented by this app. IP-based location/identifier reporting depends on actual usage; do not infer geolocation collection merely from an IP reaching an endpoint.

Draft collection **Yes**, optional **Yes**, purpose **App functionality**. For implicit artwork/DRM/background recipients, keep sharing **Yes** in this draft until their qualification for an exception is recorded. No assertion of ephemeral server processing. Code: `app/.../playback/ItemMediaSourceFactory.kt`, `ScopedDrmCallback.kt`, `data/EpgRefreshWorker.kt`, `ui/OttNativeApp.kt`.

### Data that stays local and user-requested exports

Source storage, catalog/EPG caches, favorites, resume positions and library search are processed locally by the app. The app does not upload a file's full contents merely because a local playlist/settings file is imported. The local-only portion is outside collection disclosure; subsequent URLs/credentials used in network requests are covered above.

Export serializes credentials and preferences into a plaintext JSON file at the destination the user selects. This is a user-directed transfer. Record the **sharing exception** for this action; do not treat the full JSON as a publisher upload. A local destination causes no app-controlled off-device request; a cloud document provider has its own upload behavior. If the planned distribution relies on a particular cloud integration, assess that integration separately before finalizing Files and docs or encryption answers. Code: `MainActivity.kt`, `PlayerViewModel.exportSettings`, `NativeRepository.exportSettings`.

The demo and offline privacy reader make no network requests. No app-managed ads, analytics or crash-upload flow was found in the direct dependencies and implementation. Voluntary support email is sent outside the application; it is not an in-app message collection endpoint.

### Exceptions and final review

Google excludes local-only processing from collection and permits specific user-directed transfers to be excluded from sharing. A sharing exception does not itself remove collection. End-to-end encryption and ephemeral handling have separate conditions; ordinary HTTPS alone does not establish either. Optionality, IP classification and server retention must follow the actual use. These distinctions underpin the draft above. [Official Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

Before saving the final form, resolve the conditional sharing choices and DRM/provider retention evidence. Do not paste a universal “no data collected/shared” declaration based only on the absence of analytics. Preserve the final Console answers and supporting flow decisions with the release evidence.

## Foreground-service declaration

Declared service type: **mediaPlayback**. Permission: **android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK**, alongside the base foreground-service permission. Manifest service: `play.ott.nativeapp.playback.PlaybackService`. Choose **Media Playback** and, where the form offers it separately, **Show Picture in Picture** for supported phones. There is no `USE_FULL_SCREEN_INTENT` permission.

Copy-ready functionality description:

> OTT-play Native uses a Media3 media-playback foreground service to play media selected by the user. On phones it supports continued playback during app switching and picture-in-picture on supported devices. Android media controls let the user pause, resume or stop playback. The service does not perform advertising, tracking or background catalogue synchronization. On Android TV, leaving the app pauses playback.

Copy-ready impact of deferral:

> If service start is deferred after the user selects Play, the requested video or audio cannot begin promptly. Immediate playback is the app's core purpose; a deferred background job cannot provide that experience.

Copy-ready impact of interruption:

> Interrupting the service unexpectedly stops the user's current media, including ongoing phone picture-in-picture or background playback. The user loses continuous playback and must resume it. The app provides explicit playback and system media controls so the user can pause or stop it intentionally.

Video field: **https://astral-oasis-sbqd.here.now/review/phone-media-playback.mp4**. The public [review page](https://astral-oasis-sbqd.here.now/review/) embeds the recording. Captured on 2026-09-13 from the optimized 0.2.1 release build with a local test certificate on an Android 15 phone emulator. The 18.28-second recording shows app launch, bundled demo selection, playback, Home/PiP and a user media-pause action with Android media controls. The demo's audio is silent. The H.264 frames are unchanged; the file was remuxed for web playback. See `store/capture-provenance.json` for artifact hashes and the exact validation boundary. Production signing and Console submission are still separate steps.

Google requires the feature description, impact of deferral/interruption and a video demonstrating the user trigger for each declared FGS type. Media playback/PiP are recognized use cases. [Foreground-service declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

EPG refresh uses ordinary WorkManager scheduling. Do not declare it as a mediaPlayback or dataSync foreground-service use case; the app does not promote that worker to an FGS.

## Remaining Console actions

- Create the first app record using the now-enabled **Create app** action and inspect its actual production-access eligibility/testing requirements. Completed contact-phone verification and permission to create an app do not establish those later requirements.
- Enter the prepared contact, privacy, listing and access fields; supply any additional authorized provider reviewer access required.
- Resolve and enter the Data safety draft. Complete content rating, target audience, ads and other app-content questionnaires in the live Console; no rating or age-target decision is asserted here.
- Upload the intended signed release AAB and verify package/version/signing in Console. Register the correct upload certificate and confirm Play App Signing; keep Full/preview/debug artifacts separate.
- Attach current phone/TV screenshots, the FGS demonstration-video URL and real validation evidence. Inspect upload/pre-launch reports and Android TV eligibility results.
- Record the actual review/submission outcome only after Console confirms it. This document and a published privacy page do not establish an approved or submitted release.
