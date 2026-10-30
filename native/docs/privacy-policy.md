# Privacy Policy — OTT-play Native for Android

**Last updated:** 2026-09-13

Published by **alvit**, as part of the open-ott-play project. For privacy, support or data-deletion inquiries, contact [alvit.work@gmail.com](mailto:alvit.work@gmail.com).

This policy covers **OTT-play Native for Android** (`play.ott.foss.nativeapp`), its signed preview (`play.ott.foss.nativeapp.preview`), and the separate HTTP-compatible sideload distribution (`play.ott.foss.nativeapp.full`). The app includes an offline copy under **Privacy policy / Политика конфиденциальности** on the welcome screen and in settings. The offline copy explains the network behavior of the installed distribution.

Before sending materials to support, remove credentials, private URLs, exported settings and other confidential information.

## Purpose and demo

OTT-play Native is a player for your own playlists and configured IPTV sources. It does not sell subscriptions, create publisher-operated user accounts, or supply a commercial channel catalog. You must have permission to use the accounts and content that you configure.

The optional demo is a synthetic video bundled inside the app. It plays locally without an account or network request. Unlike a hosted sample, playing this bundled demo does not send requests to a demo hosting service.

## Information you provide

You may enter a source name and URL, provider username and password, a MAC-style portal identifier registered with your provider, an XMLTV programme-guide URL, and HTTP headers. The MAC-style identifier is entered by you; the app does not read your device's hardware MAC address.

When you import a playlist or settings file, Android's system document picker grants access to the file you select. The app does not require access to your entire media library for this feature.

## Network destinations and purposes

The app connects directly to your configured provider and to addresses supplied in your playlist or its catalog to authenticate, load catalogs, download programme guides, display artwork and play media. Requests may contain the required username, password, cookie, token, configured MAC-style identifier or HTTP headers, and identify the content or programme being requested. Receiving servers also see ordinary connection information such as the requesting IP address and User-Agent.

Logos and cover images are loaded from their catalog addresses. Those addresses may be hosted by a different operator from your provider. The app does not attach provider authentication headers to ordinary artwork requests, but an artwork URL supplied by a provider may itself contain access information.

These destinations may be operated by you or by third parties. Their account information, request logs and retention practices are governed by their own policies and your arrangements with them. The publisher does not operate an intermediary server for your configured provider playback.

## Programme guides and background activity

The app loads XMLTV guides from the source or playlist's guide addresses. Android also schedules guide updates approximately every six hours on an unmetered network for configured sources. These requests may occur when the app screen is closed, and Android may defer them. Removing a source stops its subsequent scheduled guide updates.

On phones, enabled background playback continues requesting the current media stream until playback is paused or stopped. Android TV pauses playback when the activity leaves the foreground. The phone background-playback setting is not offered on TV.

## DRM-protected playback

For DRM-protected media, Android and the player may contact the license server configured by the source and an Android DRM provisioning server. Those services receive the requests needed to authorize playback and prepare the device's DRM implementation. Provider license headers are separate from media-stream headers and are not attached to DRM provisioning requests. The app does not grant content rights or extract DRM keys.

## Network security

The Play/release and signed preview distributions require HTTPS for remote sources, media, programme guides, artwork and license servers. HTTP endpoints and HTTPS-to-HTTP redirects are rejected. TLS protects information in transit; the receiving server can still process the information addressed to it.

The separate Full sideload distribution supports user-configured HTTP sources for compatibility. HTTP does not encrypt requests, credentials or returned media. Prefer HTTPS and do not send credentials over networks or to services you do not trust. Local storage encryption does not protect HTTP traffic. Debug test builds also permit HTTP for local playback fixtures and are not Play release artifacts.

## Local storage and backups

Source settings and saved catalog records, including stream and guide addresses, are encrypted using AES-GCM and an Android Keystore key. Programme-guide data, favorites and playback resume positions are stored locally in the app's private storage. The app excludes its data from Android automatic cloud backup and device-to-device transfer.

Settings export occurs only when you choose it and select a destination in Android's document picker. The export is **unencrypted JSON** and can contain source URLs, passwords, tokens or headers, favorites and resume positions. Keep it private. If you choose a cloud storage provider, that provider handles the file under its own terms and privacy practices. App-local encryption does not encrypt the exported file. Importing a settings file updates the app's local source configuration and preferences.

## Retention and deletion

In **Sources**, you can remove a source together with its saved credentials, catalog and guide cache. Local favorite identifiers and resume positions may remain until you clear the app's data. To remove all app-private data, use Android Settings → Apps → OTT-play Native → Storage → Clear storage, or uninstall the app.

Exported files and copies you shared must be deleted separately. Clearing local app data does not delete provider accounts or information already received by a provider, license server, storage provider or support service. Contact the relevant operator about its retention and deletion practices. This app does not create a publisher-operated account requiring an account-deletion flow.

If you contact support, the information you choose to send is separate from app-local storage. For inquiries about deleting support correspondence, contact [alvit.work@gmail.com](mailto:alvit.work@gmail.com). Data held by a third-party service is also subject to that service's available deletion controls and policies.

## Advertising, analytics and diagnostics

The app has no advertising or analytics SDK and does not send separate telemetry, viewing history or crash reports to the publisher. This does not mean that no data leaves your device: provider, artwork, guide and DRM requests are described above.

App error messages are designed not to disclose passwords or tokens. Android and Google Play system diagnostics depend on your device settings and the policies of those services. Remove credentials and private URLs before voluntarily sending screenshots, diagnostics or settings to support.

## Android features and permissions

Playback uses network access, a media-playback foreground service and a wake lock as needed. Source titles and artwork can appear in Android media controls or on the lock screen according to your system settings. Stop ends playback. Core playback does not require location, camera, microphone, contacts or whole-media-library permission.

Downloading or updating the app through Google Play is subject to Google's privacy practices. Contacting support uses your selected mail app and its service's policies. Reading the policy inside the app itself works offline and does not open those services.

## Changes and contact

The public copy of this page is hosted by here.now. Opening the website sends ordinary connection information, including an IP address and the page request, to the hosting service under its privacy practices. Reading the built-in policy instead does not contact this website.

This policy may be updated as app behavior changes. Material updates change the date above.

- Publisher / privacy / support: [alvit.work@gmail.com](mailto:alvit.work@gmail.com)
