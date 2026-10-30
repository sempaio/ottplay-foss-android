# Native DRM import

The Android application imports an explicit license URL and license request
headers from a bounded subset of Kodi M3U properties. Media3 sends the native
Android MediaDrm challenge to that URL and passes the response back to Android.
An unsupported configuration remains blocked, with a catalog notice; selecting
it does not replace the currently playing item with an unprotected request.

## Accepted playlist contracts

Use one DRM format per entry, after `#EXTINF` and before its stream URL:

```m3u
#EXTM3U
#EXTINF:-1,Example protected DASH
#KODIPROP:inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.example.test/acquire|Authorization=Bearer+EXAMPLE
#KODIPROP:inputstream.adaptive.stream_headers=Authorization=Bearer+SEPARATE_STREAM_TOKEN
#KODIPROP:mimetype=application/dash+xml
https://video.example.test/manifest.mpd
```

The recognized system identifiers are `com.widevine.alpha`,
`com.microsoft.playready` and `org.w3.clearkey`. The compact `drm_legacy` form
contains system, HTTP(S) license URL and optional URL-encoded header pairs. The
modern `inputstream.adaptive.drm` JSON form accepts exactly one system with
`license.server_url`, optional `license.req_headers` and optional
`force_single_session`. These are selected fields of the
[Kodi DRM integration contract](https://github.com/xbmc/inputstream.adaptive/wiki/Integration-DRM).

The older `license_type` plus `license_key` pair accepts a plain license URL or
`URL|headers|R{SSM}|R` (the final `R` may be empty). The explicit four-field form
must request a raw POST and raw response. The documented `license_url` /
`license_url_append` split is accepted only when the URL field in `license_key`
is empty. These fields follow
[Kodi's older integration format](https://github.com/xbmc/inputstream.adaptive/wiki/Integration-DRM-(old)).

Header pairs use `name=value&other=value` with percent encoding. License headers
are separate from source and stream headers. A current MIME hint can be supplied
with `mimetype`; the older `manifest_type=mpd|hls|ism` hint is also recognized, per
[Kodi's stream integration documentation](https://github.com/xbmc/inputstream.adaptive/wiki/Integration).

Compact/JSON configurations enable multiple DRM sessions by default, matching
the modern Kodi default; `force_single_session=true` disables this. Older pairs
use one session. Providers using key rotation should provide a modern
configuration that permits multiple sessions.

## Android boundaries

Media3 supports Widevine CENC/CBCS for DASH and fragmented-MP4 HLS; ClearKey CENC
for DASH; and PlayReady SL2000 CENC on compatible Android TV devices for DASH,
SmoothStreaming and fragmented-MP4 HLS. The app checks the container, TV-only
PlayReady boundary and the device's reported DRM scheme support before replacing
playback. The manifest and Android still determine codec, encryption and license
compatibility. See [Media3 DRM support](https://developer.android.com/media/media3/exoplayer/drm).

Provider authorization, provisioning, hardware security levels, HDCP and license
restrictions remain enforced by Android and the license server. Playback does
not import offline key sets or supply a software fallback that bypasses DRM.

## Explicitly unsupported configurations

Custom challenge templates or URL placeholders, response wrappers/unwrappers,
GET license requests, inline ClearKey key pairs/data URIs, server certificates,
pre-init data, multiple alternative DRM systems and unrecognized DRM properties
are rejected. Provider-specific authentication/bootstrap APIs must supply an
ordinary supported playlist and license contract first.

License URLs reject user-info, fragments, control characters and substitutions.
Header names/values are bounded and validated. License transport accepts native
POST responses up to 4 MiB. HTTP 307/308 redirects may remain within the original
scheme, host and port; credentials are never forwarded to another origin or a
scheme downgrade. Other redirect responses are errors. Device provisioning uses
a separate transport with neither license nor stream headers. This is needed
because [Media3's POST redirect helper](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/DrmUtil.java)
retains request headers while following redirects.

DRM URLs and headers stay in encrypted catalog storage and the private playback
request contract, not public MediaMetadata. Parser notices and application error
messages do not expose their values.

## Validation

Core tests cover import formats, serialization, per-entry resets, malformed
headers and unsupported configurations. Android unit tests exercise controller
serialization, native configuration, real local HTTP license/provisioning
requests, header separation and redirect restrictions.

`ClearKeyPlaybackInstrumentedTest` uses a project-owned encrypted video, a local
license server and real Android MediaDrm through the production MediaController
and service. Its fixture and regeneration details are in
[`playback/clearkey/GENERATION.md`](../app/src/androidTest/assets/playback/clearkey/GENERATION.md).
Passing this test verifies that specific CENC ClearKey path on the tested
Android image. It does not establish Widevine, PlayReady or physical-device
acceptance.
