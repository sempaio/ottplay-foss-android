# Provider configuration

The app accepts M3U playlists, Xtream accounts and supported Stalker portal
contracts. Obtain the complete playlist URL or server/login details supplied
for your subscription, then choose the matching source type.

- **M3U:** supply the complete playlist URL or select a local playlist through
  Android's file picker. Add the XMLTV URL and required HTTP headers when they
  are supplied separately. Relative stream links require an accessible HTTP(S)
  base playlist URL. Catch-up needs supported metadata or an archive duration
  configured in the source editor.
- **Xtream:** supply the server URL, username and password. Live TV, movies and
  series are loaded through their respective API sections; availability of one
  section does not imply the others are available.
- **Stalker / MAG:** supply a `/c/` portal or an explicit `server/load.php` or
  `portal.php` endpoint and the registered MAC address. A `/c/` URL is normalized
  to `server/load.php` in the same portal directory.
- **Stalker JSON-RPC:** supply the full endpoint ending in `/api/`. It uses
  `handshake` and `get_channels`; select it only for services providing that
  protocol.

A provider name or playable HLS URL alone does not specify its account,
catalog, EPG or catch-up contract. Provider-specific activation, device
registration and undocumented APIs require separate support. Ask the provider
for a supported playlist or portal when its account interface cannot be used
directly.

The Play and preview builds require HTTPS for remote resources, including
redirects. The separate Full build also accepts HTTP. See
[MIGRATION.md](MIGRATION.md) for transferring settings and sources.

For protected content, the playlist must provide one of the supported
[native DRM contracts](DRM.md). Provider-specific license challenge
transformations are not inferred or executed. Validate playback, EPG and
catch-up using your authorized account on the intended device.
