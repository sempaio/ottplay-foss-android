# Google Play listing assets

This directory contains prepared listing content for **OTT-play Native**, package `play.ott.foss.nativeapp`. It is a submission kit, not evidence that Play Console has been completed, an app has been submitted, or device review has passed.

## Prepared fields

- Publisher: **alvit**.
- Support email: **alvit.work@gmail.com**.
- Public privacy policy: [Privacy Policy — OTT-play Native](https://astral-oasis-sbqd.here.now/).
- Contact fields are also recorded in `contact.json`.
- English listing: `en-US/title.txt`, `en-US/short-description.txt`, `en-US/full-description.txt`.
- Russian listing: `ru-RU/title.txt`, `ru-RU/short-description.txt`, `ru-RU/full-description.txt`.
- Suggested app type/category: **App / Video Players & Editors**. The listing describes a player for user-supplied content, with no bundled subscription or commercial catalog.
- Contains ads: **No** for the application's own UI and integrations. Media supplied by a user's provider may itself contain broadcast advertising.

The interface follows Android language preferences, with English as the fallback. All 20 OTT-play FOSS languages are included offline. Use **More / Settings → Language** to choose a language or **System default**. The English and Russian store listings are separate from the 20 in-app language choices; this kit does not contain 20 localized store listings. The Play distribution requires HTTPS. The HTTP-compatible Full distribution has a different package and is not a Play upload candidate. Do not upload a preview, debug APK or Full artifact under this listing.

## Prepared graphics

- `assets/icon-512.png`: 512 × 512 store icon.
- `assets/feature-graphic-1024x500.png`: 1024 × 500 feature graphic.
- `assets/tv-banner-1280x720.png`: 1280 × 720 TV listing banner.

Existing captures: five 1080 × 1920 phone screenshots in `screenshots/phone/` and three 1920 × 1080 Android TV screenshots in `screenshots/tv/`. They show the optimized 0.2.1 release build with a local test certificate. `capture-provenance.json` records device details and hashes. These captures predate the in-app language picker. Keep their original provenance; capture the intended listing language on the final submission candidate before replacing them. Only opaque alpha was removed for 24-bit RGB PNG encoding; decoded RGB pixels were verified identical to the raw ADB captures. Use only the original bundled demo or other content authorized for public redistribution. Historical screenshots under `docs/screenshots/` are not automatically evidence for the current candidate. The actual [FGS demonstration video](https://astral-oasis-sbqd.here.now/review/) is published separately. `python3 scripts/validate-store-assets.py --require-screenshots` validates listing fields and screenshot dimensions in CI.

## Console entry guide

Use [PLAY-CONSOLE-SUBMISSION.md](../docs/PLAY-CONSOLE-SUBMISSION.md) for copy-ready access instructions, the Data safety draft, the media-playback foreground-service declaration, and remaining Console decisions. The public privacy policy has been published; its URL still needs to be entered in the app's Console record.

Before sending a release for review, record the uploaded AAB's package, version code, SHA-256, upload certificate and Play App Signing state; attach the actual test results and reviewer-video URL. Account eligibility, content rating, target audience, countries, and review status must come from the actual Console record. Nothing in this directory establishes those values.
