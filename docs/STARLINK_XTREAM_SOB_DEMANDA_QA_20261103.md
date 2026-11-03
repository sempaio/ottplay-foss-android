# STARLINK IPTV 0.3.2 — teste Xtream sob demanda (M81-APP-02)

Data: 2026-11-03. **Candidato de teste — não é versão oficial nem homologação em aparelho real.**

## Mudança executada

Para a conta Xtream STARLINK (origem oficial `StarlinkConfig.XTREAM_BASE_URL`), o aplicativo deixa de obter todos os streams no carregamento inicial. A abertura faz autenticação e solicita apenas `get_live_categories`, `get_vod_categories`, `get_series_categories`. Os controles exibem os grupos retornados; abrir uma categoria pede somente `get_live_streams`, `get_vod_streams` ou `get_series` com `category_id`.

Acesso a VOD, episódios, transmissão e EPG continuam pelas rotas anteriores. São guardadas em memória até oito categorias consultadas; mudança de fonte cancela as consultas pendentes. Um novo login Xtream não cria automaticamente uma fonte M3U completa adicional. As fontes instaladas anteriormente não são removidas nesta alteração.

O comportamento de fontes M3U, Xtream genéricas e Stalker que não sejam a conta STARLINK gerenciada não é substituído por esse novo fluxo.

## Arquivos modificados

- `native/core/src/main/kotlin/play/ott/nativeapp/core/{Models,XtreamProvider,ProviderRepository}.kt`
- `native/app/src/main/java/play/ott/nativeapp/{PlayerViewModel, data/NativeRepository, ui/AppContract, ui/OttNativeApp, ui/NeonHome}.kt`
- `native/core/src/test/kotlin/play/ott/nativeapp/core/StarlinkOnDemandTest.kt`

## Evidências de teste e versão

- Núcleo Xtream: `native/gradlew.bat :core:test --offline` passou em 2026-11-03, incluindo novos testes MockWebServer para autenticação, categorias iniciais sem buscar streams, consulta filtrada e VOD/séries.
- Compilação: `:app:compileParaVoceTestKotlin` e `:app:assembleParaVoceTest` concluíram com sucesso após a implementação.
- APK: `dist/STARLINK-032-XTREAM-ONDEMAND-QA.apk`, aplicação `br.starlink.iptv.paravoce` (assinado em debug/teste; não substitui o pacote oficial `br.starlink.iptv`).
- O M81 PR #28 está em branch de desenvolvimento; seu servidor de homologação separado usou PostgreSQL temporário em `127.0.0.1:55436` e API em `127.0.0.1:18765`. Um script HTTP validou categorias e streams de live, vod e séries com **4 conteúdos fictícios**. Esse servidor não está disponível diretamente pela Internet/celular, e suas mídias fictícias não reproduzem.

## Ainda não homologado

1. Navegação no aparelho Android real, rede, carregamento de capas, reprodução ao vivo, filmes e séries com fontes efetivas, EPG e fluxo Para Você.
2. Migração/limpeza das antigas Sources e publicações M3U no M81, testes reais de desempenho em base com 20 mil canais, merge/deploy do PR #28.
3. Publicação do APK oficial, validação de assinatura de atualização e qualquer alteração nos contêineres ou arquivos operacionais do servidor.

**Não fazer merge, deploy ou lançamento oficial sem revisão e teste físico.** Repositórios relacionados: `sempaio/painel-m81-iptv-cloud` PR #28 (técnico) e #27 (diretrizes); `sempaio/ottplay-foss-android` PR #22 (candidata 0.3.2), com candidato on-demand em PR independente.
