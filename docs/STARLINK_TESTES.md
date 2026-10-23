> Nova prévia neon 0.3.0-layout: [STARLINK_LAYOUT_TESTES.md](STARLINK_LAYOUT_TESTES.md). Este documento preserva a entrega anterior.

# Testes STARLINK IPTV — 2026-10-22

## Ambiente e comandos

JDK Temurin 17, SDK platforms;android-37.0, Build Tools 36.0.0, Gradle Wrapper 9.8.1, AGP 9.4.1, Kotlin 2.4.21. Versões originais mantidas. A JVM local utiliza o proxy e o truststore do ambiente; TLS permanece validado. Nenhuma credencial IPTV real foi usada.

Em `native/`:

```
./gradlew --no-daemon --max-workers=2 :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

## Resultados de unidade e análise

- Core: 66 testes, zero falhas/erros/ignorados.
- Android/Robolectric: 104 testes, zero falhas/erros/ignorados.
- Lint debug: zero erros; 18 avisos, incluindo versões novas de dependências disponíveis e targetSdk 36 abaixo do compileSdk 37. Não foram reduzidas versões ou desativadas verificações para ocultar incompatibilidades.
- Compilação do APK de testes instrumentados: passou.
- Recibo do JAR compartilhado: verificado pela tarefa `:core:verifySharedCore`.
- Resumo legível por máquina: `docs/evidencias-starlink/testes-unitarios.json`.

Os testes cobrem os seis catálogos Xtream, categorias, séries/episódios, rejeição de autenticação, contas inativas, caracteres especiais em credenciais, formato do filme via get_vod_info, get_short_epg vazio e com Base64, redirecionamento 307, política de transporte, headers, importação, favoritos, seleção de faixas e retomada. Testes novos específicos: `StarlinkXtreamTest`, quatro casos aprovados.

## Conectividade M81

GET HTTPS de `/player_api.php` sem parâmetros retornou HTTP 422. Isso demonstra resposta do endpoint a partir deste ambiente, não autenticação nem reprodução. Nenhuma conta real foi utilizada. O servidor não foi declarado offline. O contrato consultado devolve get_short_epg vazio e reprodução por HTTP 307, sem promessa de transcodificação.

## Falhas encontradas e correções

1. Execução anterior 39736040191: setup-android tentava instalar `tools`, pacote obsoleto. Corrigido `packages: platform-tools`; SDK alinhado ao nativo 37.0.
2. Download Java do Gradle local sem proxy: conexão recusada; configurados proxy e truststore somente nos comandos locais, sem alteração de rede ou de TLS.
3. Primeiras compilações locais se sobrepuseram a alterações e intermediários; houve banner ausente e depois APK sem DEX. Ambos rejeitados pela validação. Recompilação isolada, sem cache, usada para diagnóstico.
4. Dois testes de idioma falharam porque o padrão inglês havia sido substituído por português. Restaurado fallback inglês, mantendo tradução pt e identidade STARLINK. Os 104 testes passaram depois da correção.

## Limites de validação

Não há `/dev/kvm` nem dispositivo Android conectado neste executor. Os testes de dispositivo foram configurados no GitHub Actions para celular API 35 e Android TV API 36: login simplificado, credenciais vazias, inicialização real, navegação herdada, sessão e HLS com decodificação e HTTP 307. O resultado final desses jobs e a inspeção do APK constam no relatório final; a configuração de um teste não conta como execução aprovada.

PiP preservado do upstream e retomada coberta por testes de unidade. PiP não foi validado em aparelho físico. Chromecast e tela consolidada de histórico não estão implementados. Não há alegação de suporte a Tizen/webOS, espelhamento ou Cast.

## Primeira execução de dispositivo
Execução 39744477680: celular executou dois testes de login e passou. TV executou esses mesmos dois testes e falhou na ativação por toque do botão TV (callback não acionado). A submissão do teste foi corrigida para a ação semântica do botão, preservando os testes de D-pad da interface nativa.

A inspeção dos XML revelou que a lista de classes separada por vírgulas executou somente a primeira classe. Substituída por `StarlinkAcceptanceSuite`, uma suíte JUnit única com todas as classes pretendidas. Não contabilizamos vídeo/inicialização como aprovados nessa primeira execução, pois eles não foram executados.

## Correções e validações adicionais
- HTTP 401, 403 e 5xx recebem mensagens próprias; mensagens brutas do servidor não são exibidas. Dois novos testes aprovados verificam a distinção e o descarte de texto não confiável.
- O lint detectou inicialmente falta de tradução de três novas mensagens. Foram traduzidas para todos os 20 idiomas suportados, sem suprimir a verificação; lint voltou a passar com zero erros.
- A playlist pré-configurada é `https://panel.taila5c5c2.ts.net/app/starlink-iptv/playlist.m3u`. Ela é adicionada como segunda fonte após login M81 aceito. Xtream permanece como catálogo inicial; nenhuma das fontes foi desativada.
- A compilação isolada sem cache confirmou o diagnóstico dos intermediários iniciais: APK completo com DEX, bibliotecas e assinatura, com o mesmo SHA256 da reexecução correta daquele commit. Esse teste não foi usado para declarar válido o APK anterior vazio.
- Execução 39746432251: 12 testes em cada emulador (celular API 35 e Android TV API 36), cinco classes, zero falhas ou testes ignorados. Inclui inicialização real, formulário, escolha da playlist, busca/filtros e navegação nativa, foco remoto, MP4/sessão e HLS com 307 e decodificação de vídeo.
- A rodada final usa os APKs gerados no job build, baixados e instalados nos dois emuladores por adb. Assim, o APK para download é o mesmo artefato testado. A saída de instrumentação precisa confirmar as cinco classes e pelo menos 12 testes aprovados.
- O verificador de cobertura foi exercitado com uma saída completa e uma incompleta: aceitou a primeira e rejeitou a segunda.

## Resultado final do commit f02ca385

Execução https://github.com/sempaio/ottplay-foss-android/actions/runs/39747628016 : **todos os jobs aprovados**.

- 66 testes core e 104 testes Android de unidade, zero falhas.
- Celular API 35: 12 testes instrumentados aprovados, cinco classes.
- Android TV API 36: 12 testes instrumentados aprovados, cinco classes.
- Os APKs foram gerados uma vez no build, baixados pelos jobs de dispositivo e instalados com adb. A saída completa da instrumentação e a verificação de cobertura passaram.
- O artefato de distribuição foi baixado novamente e validado com aapt, apksigner, integridade ZIP, DEX, URL Xtream e caminho M3U completo. O arquivo em dist é esse APK aprovado, não o build local anterior com outra assinatura debug.
- Arquivo final: 20.941.207 bytes, SHA256 `e0204b19c801824511909bb1c5e43b17026ab04bbe39e80e850c8159d9cfe25f`.
- HEAD da playlist confirmou 200, application/x-mpegurl e X-Playlist-Entries 168844. Apenas metadados foram consultados; isso não comprova disponibilidade individual dos vídeos.
- Autenticação e playback de conteúdo real M81 permanecem não testados. Todos os vídeos de instrumentação são fixtures sintéticas locais.
