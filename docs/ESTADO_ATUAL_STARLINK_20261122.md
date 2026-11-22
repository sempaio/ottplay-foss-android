# Estado Atual STARLINK Android — 22/11/2026

> Atualização técnica verificada em **22/11/2026** na branch `feature/starlink-everycine-fluxo-visual-20261117`. Nenhuma modificação foi feita na branch `main` nem na produção do painel M81.

## 1. Resumo do Build e Validação Local

| Verificação | Resultado | Detalhes |
|---|---|---|
| `:core:test` | ✅ PASS | **86 testes** em 19 suítes (0 falhas, 0 erros, 0 ignorados) |
| `:app:testLayoutUnitTest` | ✅ PASS | **226 testes** em 41 suítes (0 falhas, 0 erros, 0 ignorados) |
| **Total de testes unitários** | ✅ **312/312** | Inclui novos testes de persistência criptografada, recuperação de sessão e troca de conta M81 |
| `:app:lintLayout` | ✅ PASS | **0 erros** (corrigidos 14 erros `MissingTranslation` em strings específicas STARLINK) |
| `:app:assembleEveryCineQa` | ✅ PASS | Pacote `br.starlink.iptv.everycineqa` (`0.3.2-everycine-visual-qa`), `apksigner verify` OK |
| `:app:assembleOfficial` | ✅ PASS | Pacote `br.starlink.iptv` (`0.3.2`), minificação **R8** + `lintVitalOfficial` OK |

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

## 3. Pendências Conhecidas para Próxima Fase (Homologação Final)

- Executar testes instrumentados em dispositivo físico / TV Box e emulador API compatível para validar `EveryCineSeriesDetail`, swipe entre abas, PiP e navegação D-Pad ponta a ponta.
- Internacionalizar rótulos restantes em `EveryCineVisual.kt` (atualmente em PT-BR) caso o cliente final opere fora do idioma português.
- Decidir o momento de promoção do fluxo visual Every Cine para assinatura oficial (`official`) e merge na branch principal após aprovação manual do APK `STARLINK-EVERYCINE-VISUAL-TESTE`.
