# Estado Atual — EveryCine IPTV Android (`br.starlink.iptv`) — 2026-11-25

- **Worktree ativa:** `E:\painelcliente_test\_worktrees\everycine-artwork-20261123`
- **Branch:** `fix/everycine-current-source-20261123`
- **Versão publicada oficial:** **`0.3.10`** (`versionCode = 18` em `native/version.properties`)
- **APK oficial publicado:** `E:\painelcliente_test\projeto\data\app_releases\STARLINK-EveryCine-Official-20261125.apk`
  - **Tamanho:** `6.468.585` bytes
  - **SHA-256:** `816370c23f2ca3011546ee6c19f5cbaf1c10bd0d7a55ea54cedbb893741aceeb`
  - **Certificado oficial (v2/v3):** `21:61:B6:BE:77:0A:73:30:0D:E7:81:89:54:A1:FA:A2:A4:C4:BA:F5:C8:04:55:5A:45:88:D0:60:C9:8B:C7:C0`
  - **PostgreSQL `app_releases`:** ID `#20`, `force_upgrade = True`, `is_published = True`

---

## Histórico de Commits da Entrega Atual

1. **`f51b9f68`** — Restauração de subcategorias em pílulas e exibição de todos os canais numerados sem colapso.
2. **`1c5ad11e`** (`0.3.9` / build 17):
   - Busca global em todo o catálogo via CoreAPI (`XtreamProvider.search` -> `action=search_catalog`, `ProviderRepository.searchXtreamCatalog`, `NativeRepository.searchXtreamCatalog`, `PlayerViewModel.searchCatalog`, `EveryCineGlobalSearchResults`).
   - Aumento do cache de grupos visitados (`visitedGroups > 32`) para não evictar categorias multi-grupos.
   - Insets de tela seguros (`statusBarsPadding`, `navigationBarsPadding`, `displayCutoutPadding`) no shell principal, barra inferior, detalhes de filme, snackbars e overlay de tela cheia.
   - `NativePlayerPane.kt`: player tela cheia com overlay translúcido + auto-ocultação, `externalResizeMode` acoplado ao botão `Ajustar`, contraste branco legível no card de erro e remoção do cabeçalho duplicado no modo inline.
   - `EveryCineVisual.kt`: lista vertical `EveryCineChannelRows` com iniciais de fallback (exibidas quando logo é vazio ou 404), barra inline horizontal rolável sem quebra vertical e `openCastOrMirroring` (`ACTION_CAST_SETTINGS` + verificação `PackageManager` + Toast seguro).
3. **`9108702b`** (`0.3.10` / build 18):
   - `native/app/build.gradle.kts`: adicionados `buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")` e `manifestPlaceholders["allowCleartextTraffic"] = "true"` na variante `official` para permitir reprodução de filmes e canais redirecionados via `HTTP 307 -> http://` (mantendo login, CoreAPI e updates 100% em HTTPS).
   - `native/app/src/main/java/play/ott/nativeapp/playback/PlaybackService.kt`: adicionados `.followRedirects(true)`, `.followSslRedirects(true)` e timeouts ampliados (`25s` conexão / `45s` leitura) para CDNs VOD mais lentas no primeiro byte.

---

## Validações Concluídas

- `:core:test`, `:app:testLayoutUnitTest`, `:app:lintVitalOfficial`, `:app:assembleOfficial`: **BUILD SUCCESSFUL**.
- `aapt2 dump xmltree`: confirmados `versionCode=18`, `versionName="0.3.10"` e `usesCleartextTraffic=true`.
- `apksigner verify --verbose --print-certs`: verificado v2/v3 com o certificado oficial Starlink IPTV.
- Validação visual no emulador `Pixel_10_Pro` (`0.3.9`): busca global (`chaves` retornando canais ao vivo + filmes + séries com capas), barra inferior acima da barra de gestos, player ao vivo com controles alinhados e player tela cheia em paisagem com overlay seguro.
- Validação HTTPS ao vivo (`2026-11-25`): `/client-apps/download/starlink-iptv`, `/app/update/latest?package_name=br.starlink.iptv` e `/app/update/download/20` todos HTTP 200 com `6.468.585` bytes.

---

## Pendência para Retomada

- Confirmar reprodução real de filmes VOD e canais HTTP/HTTPS na **`0.3.10`** diretamente no aparelho físico do proprietário.
