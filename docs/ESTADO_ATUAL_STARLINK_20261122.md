# Estado Atual STARLINK Android — 22/11/2026

> Atualização técnica verificada em **22/11/2026** na branch `feature/starlink-everycine-fluxo-visual-20261117`. Nenhuma modificação foi feita na branch `main` nem na produção do painel M81.

## 1. Resumo do Build e Validação Local

| Verificação | Resultado | Detalhes |
|---|---|---|
| `:core:test` | ✅ PASS | **86 testes** em 19 suítes (0 falhas, 0 erros, 0 ignorados) |
| `:app:testLayoutUnitTest` | ✅ PASS | **230 testes** em 42 suítes (0 falhas, 0 erros, 0 ignorados) |
| **Total de testes unitários** | ✅ **316/316** | `86 core + 230 app` (inclui `VisitedXtreamCacheTest`, categoria padrão em `EveryCineVisualPlanTest`, persistência criptografada e sessão M81) |
| `:app:lintLayout` | ✅ PASS | **0 erros** (corrigidos 14 erros `MissingTranslation` em strings específicas STARLINK) |
| `:app:assembleEveryCineQa` | ✅ PASS | Pacote `br.starlink.iptv.everycineqa` (`0.3.2-everycine-visual-qa`, `24.102.600` bytes, SHA-256 `f3cb8883b132fbbcf12912022c8400397c5586828ef418aff621b67ad9749d87`), `apksigner verify` OK |
| `:app:assembleOfficial` | ✅ PASS | Pacote `br.starlink.iptv` (`0.3.2`, `versionCode 10`), minificação **R8** + `lintVitalOfficial` OK; assinado com `starlink_iptv_release.keystore` (`STARLINK-EveryCine-Official-20261122.apk`, `6.489.065` bytes, SHA-256 `13d628229ce3d7b8b5b9e092fcafbca05e50c5b03894915645855580c636ded1`, cert `21:61:B6:BE:...`, `apksigner verify` v2/v3 OK) |

## 2. Entregas Aplicadas nesta Rodada

1. **Commits atômicos do trabalho pendente:**
   - Contrato de sessão de 3 dispositivos do painel M81 (`/app/auth/login`, `/app/auth/validate`, `/app/auth/logout`) em `M81DeviceSessionClient.kt`, `NativeRepository.kt`, `PlayerViewModel.kt`, `ProviderErrorMessages.kt` e `strings_customer.xml`.
   - Evolução visual do fluxo Every Cine (`EveryCineVisual.kt` e `OttNativeApp.kt`): tela dedicada `EveryCineSeriesDetail`, integração com **Para Você**, PiP no player ao vivo, seletor de qualidades, Guia/EPG e foco TV/D-Pad.
2. **Recuperação automática e segurança na troca de conta M81:**
   - `syncM81DeviceSession` em `M81DeviceSessionClient.kt` recupera automaticamente o registro de sessão na próxima abertura caso um login anterior tenha ocorrido em *fail-open* (`sessionToken == null`), reutilizando o mesmo `deviceId` criptografado para não ocupar slot extra no limite de 3 dispositivos.
   - `loginM81DeviceSession` encerra/limpa a sessão anterior ao trocar de usuário em *Minha conta*, impedindo vazamento de token ou `accountName` da conta antiga em cenário de *fail-open*.
   - Nova suíte `M81DeviceSessionStoreTest` (6 casos Robolectric + `SourceVault`) e ampliação de `ProviderErrorMessagesTest` para erros `429`/`401`/`403` no modo cliente.
3. **Correções de UX e performance em `EveryCineVisual.kt`:**
   - Sincronização blindada entre `pageName` e `HorizontalPager` (`programmaticPageTarget` + `snapshotFlow { pagePager.settledPage }`), evitando que `browse(kind, category)` perca a categoria selecionada.
   - Reaproveitamento de `XtreamDisplayGroups.merge(state.xtreamGroups)` calculado uma única vez no shell principal.
   - Filtros, busca (com debounce de 250 ms), montagem de vitrines (`everyCineSections`), agrupamento de qualidades e `ForYouEngine.recommend` movidos para `Dispatchers.Default`, com flag `ready` para evitar *flicker* da mensagem de catálogo vazio.
4. **Hardening da CI (`.github/workflows/starlink-m81-qa-installable.yml`):**
   - Passa a executar `:app:lintLayout` e `:app:assembleOfficial` (R8) além de `:core:test`, `:app:testLayoutUnitTest` e `:app:assembleEveryCineQa`, verificando o APK oficial não assinado e publicando os relatórios de Lint como artefatos.
5. **Limpeza do repositório e consolidação de variantes:**
   - Removidos 8 APKs antigos de QA (`QA` a `QA8`, ~177 MB) de `dist/`, preservando `dist/STARLINK-IPTV-layout.apk` (referenciado por `starlink-android-apk.yml`).
   - Removido o `buildType` obsoleto `qaM81Test` em `native/app/build.gradle.kts`, preservando `paraVoceTest` (usado por `starlink-para-voce.yml`, `starlink-032-para-voce-integrated.yml` e `consolidation-qa.init.gradle`) e `everyCineQa`.
6. **Otimização de performance, cache e carregamento paralelo Xtream (Rodadas 1 e 2):**
   - `VisitedXtreamCache` (`VisitedXtreamCache.kt` + `VisitedXtreamCacheTest.kt`): cache LRU em memória (`MAX_GROUPS=16`, `MAX_ENTRIES=35.000`) preservando ao menos uma categoria por `MediaKind`.
   - Auto-carregamento da primeira categoria válida (`everyCineDefaultRemoteGroup`, pulando `"Filmes"` deslocado em `SERIES`) em `HOME`, `CATALOG` e `LIVE`, com indicador `CircularProgressIndicator` e fallback de monograma nos pôsteres (`EveryCineVisual.kt`, `EveryCineVisualPlan.kt`, `OttNativeApp.kt`).
   - `NativeRepository.kt`: cache em memória de `sources()` (evita leitura de `sources.enc` e descriptografia no `AndroidKeyStore` a cada toque) e escrita de cache SQLite (`saveXtreamCategories`/`saveXtreamCategory`) fora de `sourceMutex`.
   - `PlayerViewModel.kt`, `XtreamProvider.kt`, `ProviderRepository.kt`: parâmetro `skipAuthenticate` para evitar segunda autenticação no login (`SaveSource`); busca concorrente de categorias mescladas em `openXtreamGroups` (`Semaphore(4)`) com `1` único `updateState` consolidado e persistência SQLite assíncrona em coroutine separada.
   - `OttplayApplication.kt` + `EveryCineVisual.kt`: Coil configurado com `MemoryCache` (25%), `DiskCache` (64 MB em `cacheDir/image_cache`), timeouts curtos (`5s`/`8s`/`12s`), `respectCacheHeaders(false)`, `crossfade(150)` e downsampling `ImageRequest.size(268, 372)` nos pôsteres; botão `▶ Reproduzir` em `EveryCineMovieDetail` liberado imediatamente sem esperar `get_vod_info`.

## 3. Pendências Conhecidas para Próxima Fase (Homologação Final)

- Executar testes instrumentados em dispositivo físico / TV Box e emulador API compatível para validar `EveryCineSeriesDetail`, swipe entre abas, PiP e navegação D-Pad ponta a ponta.
- Internacionalizar rótulos restantes em `EveryCineVisual.kt` (atualmente em PT-BR) caso o cliente final opere fora do idioma português.
- Fluxo visual Every Cine **promovido e publicado** como APK oficial assinado (`br.starlink.iptv`, `0.3.2` / `versionCode 10`, `STARLINK-EveryCine-Official-20261122.apk`) na entrega padrão `starlink-iptv` do painel M81 em 22/11/2026; resta apenas o merge planejado na branch principal quando autorizado.
