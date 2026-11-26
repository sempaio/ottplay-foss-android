# Estado Atual — EveryCine IPTV Android (`br.starlink.iptv`) — 2026-11-26

- **Worktree ativa:** `E:\painelcliente_test\_worktrees\everycine-artwork-20261123`
- **Branch:** `fix/everycine-current-source-20261123` (remote GitHub `starlink`: `https://github.com/sempaio/ottplay-foss-android.git`)
- **Versão publicada oficial:** **`0.3.14`** (`versionCode = 22` em `native/version.properties`)
- **APK oficial publicado:** `E:\painelcliente_test\projeto\data\app_releases\STARLINK-EveryCine-Official-0.3.14-20261125.apk`
  - **Tamanho:** `6.468.585` bytes
  - **SHA-256:** `0b5942f3a50f6a4ade69c4a50e589a8266d65117aecadbaf2c9891b6bdd98988`
  - **Certificado oficial (v2/v3):** `21:61:B6:BE:77:0A:73:30:0D:E7:81:89:54:A1:FA:A2:A4:C4:BA:F5:C8:04:55:5A:45:88:D0:60:C9:8B:C7:C0`
  - **PostgreSQL `app_releases`:** ID `#24`, `force_upgrade = True`, `is_published = True`

---

## Histórico de Commits Consolidados (2026-11-24 a 2026-11-26)

1. **`f51b9f68`** — Preservação de todos os canais numerados sem colapso silencioso.
2. **`1c5ad11e`** (`0.3.9` / build 17): busca global em todo o catálogo via CoreAPI (`action=search_catalog`), insets seguros (`statusBarsPadding`, `navigationBarsPadding`, `displayCutoutPadding`), player tela cheia em overlay com auto-ocultação, botão `Ajustar` conectado ao ExoPlayer, `Cast` seguro (`ACTION_CAST_SETTINGS` + Toast) e fallback de iniciais nos logos.
3. **`9108702b`** (`0.3.10` / build 18): `ALLOW_INSECURE_HTTP=true` e `usesCleartextTraffic=true` na variante `official` + `followRedirects(true)` / `followSslRedirects(true)` + timeouts `25s`/`45s` no player para reproduzir filmes e canais redirecionados em `http://` (mantendo login, CoreAPI e updates 100% em HTTPS).
4. **`48e92b8f`** (`0.3.11` / build 19): removidos todos os 5 classificadores artificiais locais (`EveryCineFamilyIndex`, `SmartLiveCatalog`, `XtreamDisplayGroups`, `CatalogDisplayGroup`, `LiveNetworkDiscovery`, `-1.484` linhas); o app agora exibe exclusivamente as categorias originais (`group-title` / `category_name`) das listas **Pluto TV Brasil** e **IPTV Brasil 2026**.
5. **`b78d4fa2`** (`0.3.12` / build 20): correção do auto-update no Android 11+ (staging com extensão `.apk` antes de `getPackageArchiveInfo`), checagem de atualização em `MainActivity.onResume()`, erro legível direto no dialog + botão reserva **"Baixar no navegador"**, botão **"Atualizar catálogo"** nas Configurações e revalidação de categorias em background após `60s`.
6. **`3aa82275`** (`0.3.13` / build 21): categorias Xtream carregadas em paralelo (`async`/`awaitAll` em `XtreamProvider.kt`), `DefaultLoadControl` otimizado para IPTV no `PlaybackService.kt` (`bufferForPlaybackMs=800ms`, `afterRebuffer=1800ms`, `minBuffer=15s`, `maxBuffer=30s`, `initialBitrate=2.5Mbps`) e Coil `ImageLoader` com `MemoryCache` 25% + `DiskCache` `150 MB` + `respectCacheHeaders(false)`.
7. **`232b613c`** (`0.3.14` / build 22): forçado `HTTP/1.1` + buffer de `64 KB` no `UpdateClient.kt` (eliminando estrangulamento HTTP/2 no Tailscale Funnel) e adicionada barra de progresso real (`LinearProgressIndicator` + `Baixando X,X MB / 6,2 MB (XX%)` e status de fase) na janela de atualização.

---

## Validações Concluídas

- `:core:test`, `:app:testLayoutUnitTest`, `:app:lintVitalOfficial`, `:app:assembleOfficial`: **BUILD SUCCESSFUL**.
- `aapt2` e `apksigner verify`: confirmados `versionCode=22`, `versionName="0.3.14"`, `usesCleartextTraffic=true` e certificado oficial v2/v3.
- Emulador `Pixel_10_Pro`: validados fluxo real de atualização in-app (`0.3.12 -> 0.3.13` abrindo o `PackageInstaller` oficial) e inicialização limpa da `0.3.14`; emulador encerrado ao final para liberar memória.
- Validação HTTPS ao vivo (`2026-11-26`): `/client-apps/download/starlink-iptv`, `/app/update/latest?package_name=br.starlink.iptv` e `/app/update/download/24` todos HTTP 200 com `Content-Length: 6468585` exato (sem GZip em binários).
