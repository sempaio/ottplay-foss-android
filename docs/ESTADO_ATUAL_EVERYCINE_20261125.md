# Estado Atual — EveryCine IPTV Android (`br.starlink.iptv`) — 2026-11-27

- **Worktree ativa:** `E:\painelcliente_test\_worktrees\everycine-artwork-20261123`
- **Branch:** `fix/everycine-current-source-20261123` (remote GitHub `starlink`: `https://github.com/sempaio/ottplay-foss-android.git`)
- **Versão publicada oficial:** **`0.3.18`** (`versionCode = 26` em `native/version.properties`)
- **APK oficial publicado:** `E:\painelcliente_test\projeto\data\app_releases\STARLINK-EveryCine-Official-0.3.18-20261127.apk`
  - **Tamanho:** `6.595.561` bytes (`6.3 MB`)
  - **SHA-256:** `0040319c0ca9fe0132d837ad45c71e9dd1455e99d6589a7d392897559503d897`
  - **Certificado oficial (v2/v3):** `21:61:B6:BE:77:0A:73:30:0D:E7:81:89:54:A1:FA:A2:A4:C4:BA:F5:C8:04:55:5A:45:88:D0:60:C9:8B:C7:C0`
  - **PostgreSQL `app_releases`:** ID `#28`, `force_upgrade = False`, `is_published = True` (`#26` `0.3.16` e `#27` `0.3.17` despublicadas e arquivadas em `_backups/retired_20261127/`)
  - **Entrega WhatsApp Bot e Painel:** `starlink-client-delivery.json` (`slug = starlink-iptv`) -> `https://panel.taila5c5c2.ts.net/client-apps/download/starlink-iptv`
  - **Atualização automática in-app:** `https://panel.taila5c5c2.ts.net/app/update/latest?package_name=br.starlink.iptv` -> `/app/update/download/28`

---

## Histórico de Commits Consolidados (2026-11-24 a 2026-11-27)

1. **`f51b9f68`** — Preservação de todos os canais numerados sem colapso silencioso.
2. **`1c5ad11e`** (`0.3.9` / build 17): busca global em todo o catálogo via CoreAPI (`action=search_catalog`), insets seguros (`statusBarsPadding`, `navigationBarsPadding`, `displayCutoutPadding`), player tela cheia em overlay com auto-ocultação, botão `Ajustar` conectado ao ExoPlayer, `Cast` seguro (`ACTION_CAST_SETTINGS` + Toast) e fallback de iniciais nos logos.
3. **`9108702b`** (`0.3.10` / build 18): `ALLOW_INSECURE_HTTP=true` e `usesCleartextTraffic=true` na variante `official` + `followRedirects(true)` / `followSslRedirects(true)` + timeouts `25s`/`45s` no player para reproduzir filmes e canais redirecionados em `http://` (mantendo login, CoreAPI e updates 100% em HTTPS).
4. **`48e92b8f`** (`0.3.11` / build 19): removidos todos os 5 classificadores artificiais locais (`EveryCineFamilyIndex`, `SmartLiveCatalog`, `XtreamDisplayGroups`, `CatalogDisplayGroup`, `LiveNetworkDiscovery`, `-1.484` linhas); o app agora exibe exclusivamente as categorias originais (`group-title` / `category_name`) das listas **Pluto TV Brasil** e **IPTV Brasil 2026**.
5. **`b78d4fa2`** (`0.3.12` / build 20): correção do auto-update no Android 11+ (staging com extensão `.apk` antes de `getPackageArchiveInfo`), checagem de atualização em `MainActivity.onResume()`, erro legível direto no dialog + botão reserva **"Baixar no navegador"**, botão **"Atualizar catálogo"** nas Configurações e revalidação de categorias em background após `60s`.
6. **`3aa82275`** (`0.3.13` / build 21): categorias Xtream carregadas em paralelo (`async`/`awaitAll` em `XtreamProvider.kt`), `DefaultLoadControl` otimizado para IPTV no `PlaybackService.kt` (`bufferForPlaybackMs=800ms`, `afterRebuffer=1800ms`, `minBuffer=15s`, `maxBuffer=30s`, `initialBitrate=2.5Mbps`) e Coil `ImageLoader` com `MemoryCache` 25% + `DiskCache` `150 MB` + `respectCacheHeaders(false)`.
7. **`232b613c`** (`0.3.14` / build 22): forçado `HTTP/1.1` + buffer de `64 KB` no `UpdateClient.kt` (eliminando estrangulamento HTTP/2 no Tailscale Funnel) e adicionada barra de progresso real (`LinearProgressIndicator` + `Baixando X,X MB / 6,2 MB (XX%)` e status de fase) na janela de atualização.
8. **`d8e00a04`** (`0.3.15` / build 23): restaurado `initialBitrateEstimate = 550 kbps` no `DefaultBandwidthMeter`, cliente HTTP dedicado para TV ao vivo (`HTTP/1.1`, `connect=8s`, `read=12s`, mantendo VOD em `20s/40s`), `HlsMediaSource.Factory().setAllowChunklessPreparation(true)`, buffer IPTV reequilibrado e `LivePlaybackRecoveryPolicy` acelerada (`BUFFERING 7s`, `READY 9s`, backoff máximo `15s`).
9. **`c5eec0af`** (`0.3.16` / build 24): renovação automática de manifesto HLS no `ItemMediaSourceFactory.kt` (renova tokens curtos `/auth/*.m3u8?token=...` direto na origem `.m3u8` usando o IP do próprio aparelho, sem proxy de vídeo e sem vazar credenciais), `DefaultTrackSelector` com `AdaptiveTrackSelection.Factory` suave (`minDurationForQualityIncrease=15s`, `bandwidthFraction=0.65f`) para Pluto TV sem engasgos, buffer `30s-60s`, `readTimeout=15s` ao vivo, `DefaultLoadErrorHandlingPolicy(4)` e revalidação rápida (`60s`) de categorias ao vivo em cache antigo `.ts`.
10. **`a8f417c..5da0974`** (`0.3.17` / build 25):
    - **Novo Player Cinema estilo DeepCine** (`CinemaPlayerState.kt`, `CinemaPlayerOverlay.kt`): gestos táteis de brilho, volume e avanço/retrocesso, toque duplo ±10s, toque longo `2.0x`, trava de tela (`🔒`/`🔓`), barra superior/central/inferior cinema, gaveta lateral direita de canais ao vivo, episódios de séries e velocidade, botão EPG ao vivo com subtítulo do programa atual, Áudio/Legendas, Proporção, Girar tela e PiP.
    - **Transmissão Universal para TVs sem bloquear o celular** (`DlnaProtocol.kt`, `LanStreamProxyServer.kt`, `GoogleCastProtocol.kt`, `GoogleCastClient.kt`, `CastRelayService.kt`, `UniversalCastController.kt`, `UniversalCastUi.kt` — 100% Kotlin puro, zero SDK externo/anúncios):
      - **Google Chromecast (Cast v2 TLS porta 8009 + Default Media Receiver `CC1AD845`):** envio direto em HTTPS/CDN via CORS de mídia do M81 para Pluto TV e VOD (reprodução independente do celular), reuso de sessão `CC1AD845` para troca rápida de canais e proxy HLS CORS de reserva com cache LRU.
      - **Smart TVs Samsung e LG (DLNA/UPnP SSDP + SOAP `AVTransport:1` / `RenderingControl:1`):** proxy LAN local com remux HLS AES-128-CBC -> MPEG-TS 188B (`video/mp2t`), pré-carregamento do 1º segmento (`< 5 ms` até o byte 0) e pacing suave ao vivo, costura VOD em fatias `Range` de 2 MB (contra corte de ~90s do Cloudflare Worker) com início imediato (`< 80 ms`) e `readTimeout = 0`.
      - **Segundo plano estável:** `CastRelayService` em primeiro plano (`mediaPlayback|dataSync` + `WakeLock` + `WifiLock` + controles na notificação) e atalho 1-toque `⚡ Liberar 2º plano` (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) + barra flutuante de controle remoto no catálogo.

---

## Validações Concluídas (`0.3.18`)

- `:core:test`, `:app:testLayoutUnitTest` (**234 testes / 41 suítes / 0 falhas**, incluindo `PlaybackTelemetryClientTest` e `TvDpadAndEpgGridTest`), `:app:lintVitalOfficial` e `:app:assembleOfficial` (R8): **BUILD SUCCESSFUL**.
- `apksigner verify --verbose --print-certs`: certificado oficial v2/v3 confirmado em `STARLINK-EveryCine-Official-0.3.18-20261127.apk` (`br.starlink.iptv`, `versionCode=26`, `versionName="0.3.18"`, `STARLINK IPTV`).
- Validação HTTPS pública ao vivo (`2026-11-27`): `/app/update/latest?package_name=br.starlink.iptv` (`version: 26`, `0.3.18`), `/app/update/download/28` e `/client-apps/download/starlink-iptv` (bot WhatsApp) entregando `6.595.561` bytes exatos sem `Content-Encoding: gzip` e com SHA-256 idêntico; `starlink_ops.py verify-apps` **100% ok**.

---

## Novidades Consolidadas na `0.3.18` (Publicada)

- **Telemetria Passiva de Falhas de Playback (#3):** `PlaybackTelemetryClient.kt` + hook em `PlayerViewModel.kt` (reporte assíncrono anônimo via HTTPS para `/app/telemetry/playback-error` com cooldown local de 5 min e sem URLs/PII).
- **Navegação TV D-Pad + Nova Grade EPG (#5):** `TvDpadAndEpgGrid.kt` (`TvDpadPolicy` no player fullscreen para zapping/seek/EPG/gaveta por controle remoto, `tvFocusRing` em foco D-Pad e nova `EpgGridGuideDialog` com trilho lateral de canais, barra de progresso ao vivo, minutos restantes e filtros de janela).
- **CI Automatizado sem Segredos (#6):** `.github/workflows/starlink-everycine-ci.yml` executando `:core:test`, `:app:testLayoutUnitTest`, `:app:compileOfficialKotlin` e `:app:lintVitalOfficial` a cada push/PR, mantendo a keystore oficial 100% isolada fora do repositório.
