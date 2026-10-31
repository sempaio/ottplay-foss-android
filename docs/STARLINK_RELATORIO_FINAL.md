> **Atualização documental de 30/10/2026:** consultar [ESTADO_GITHUB_STARLINK_032_20261030.md](ESTADO_GITHUB_STARLINK_032_20261030.md) para o snapshot do commit `ca1e59e4`, CI, limitações e pendências. Este documento mantém as evidências e decisões do momento em que foi escrito.

> Atualização documental de31/10/2026: [estado consolidado local/GitHub](ESTADO_ATUAL_STARLINK_20261031.md). CI nativa e quatro casos remotos API35 aprovados; execução local API37 incompleta; Quality Gate ainda bloqueado. Trabalho local pausado. Evidências históricas abaixo preservadas.

> Atualização 0.3.1 para celular: veja [STARLINK_CLIENTE_031.md](STARLINK_CLIENTE_031.md). O conteúdo abaixo registra a entrega anterior. TV, DLNA e espelhamento estão adiados nesta atualização.

> Nova prévia neon 0.3.0-layout: [STARLINK_LAYOUT_RELATORIO_FINAL.md](STARLINK_LAYOUT_RELATORIO_FINAL.md). Este documento preserva a entrega anterior.

# Entrega STARLINK IPTV — 2026-10-22

## Aplicativo e código

- Repositório: https://github.com/sempaio/ottplay-foss-android
- Branch publicada: `starlink/entrega-android-20261022`.
- Commit final do código Android e CI: `f02ca3854ff86f2e369e494b51643b67d8c5b5f4`. O commit posterior de entrega contém apenas documentação/evidências.
- Fonte Android em `native/`, importada de `sempaio/ottplay-android` no commit `4fc46551af1cdce6eabd879719eb6e6e82d73a35`.
- Arquitetura preservada: Kotlin, Compose, Media3, módulos app/core. O código web/Capacitor/Rust da consolidação não é compilado nesse APK.
- Nenhuma main foi modificada. O painel M81 foi usado somente para leitura de contratos/documentação e consultas HTTP sem credenciais; não houve deploy nem alteração de serviços, dados, configurações ou publicações.
- Licenças MIT, NOTICE, licenças transitivas e recibo do core compartilhado preservados em `native/`. Não foram copiados CSS sem licença explícita, logos Netflix ou imagens de terceiros para a identidade do aplicativo.

## APK entregue

Arquivo: `/workspace/ottplay-foss-android/dist/STARLINK-IPTV-debug.apk`

- Tamanho: 20.941.207 bytes.
- SHA256: `e0204b19c801824511909bb1c5e43b17026ab04bbe39e80e850c8159d9cfe25f`.
- Nome: STARLINK IPTV.
- Package: `br.starlink.iptv`.
- Versão: `0.2.1`, versionCode `7`.
- Android mínimo: 8.0 / API 26; targetSdk 36, compileSdk 37.
- Assinatura debug verificada com apksigner; manifesto, internet, inicializadores de celular/TV, integridade ZIP, DEX e ambos os endereços pré-configurados verificados no APK real.
- Evidência: `docs/evidencias-starlink/apk-validacao.txt`; checksum também em `dist/SHA256SUMS`.

O APK final em dist é uma cópia do artefato aprovado no CI, baixado e revalidado neste workspace. É o mesmo arquivo instalado e testado nos dois emuladores. O build local anterior foi preservado em `/workspace/scratch/STARLINK-IPTV-local-debug.apk` para diagnóstico e usa outra chave debug. A entrega final usa o arquivo do GitHub acima, sem misturar assinaturas. Não foi removida nenhuma instalação existente nem forçada atualização com assinatura incompatível.

Download pelo celular: https://github.com/sempaio/ottplay-foss-android/actions/runs/39747628016/artifacts/12413090123 . Expira em 2026-11-21; é necessário entrar no GitHub para baixar. O ZIP contém `STARLINK-IPTV-debug.apk`, metadados e checksum.

## Endereços e funcionamento

**Xtream:** `https://panel.taila5c5c2.ts.net`

Login: `/player_api.php?username=USER&password=PASS`, sem credenciais embutidas. O aplicativo solicita apenas usuário e senha na tela padrão, valida a conta antes de persistir a sessão e mantém fontes avançadas do original.

**Playlist adicional:** `https://panel.taila5c5c2.ts.net/app/starlink-iptv/playlist.m3u`

Conforme confirmação do usuário, Xtream permanece como catálogo inicial. Após login M81 aceito, a fonte “Lista STARLINK IPTV” é cadastrada uma vez, sem sobrescrever uma fonte já existente. Ela pode ser escolhida no seletor de fonte. Nenhuma das duas modalidades foi desativada; a decisão de desativar uma ficou para depois.

A URL M3U não é concatenada a `/player_api.php`. Os contratos são separados em `native/app/src/main/java/play/ott/nativeapp/StarlinkConfig.kt`.

## Funcionalidades

- Login Xtream e persistência de fontes e URLs em cofre AES-GCM AndroidKeyStore, fora do backup Android.
- Catálogos ao vivo, filmes e séries; categorias, busca, filtros e favoritos.
- Temporadas/episódios por get_series_info; get_vod_info sob demanda e uso da extensão anunciada, sem presumir transcodificação.
- get_live_categories, get_live_streams, get_vod_categories, get_vod_streams, get_series_categories e get_series preservados e testados.
- get_short_epg como fallback quando não existe programação XMLTV em cache; resposta vazia aceita.
- Player Media3, controles de reprodução/faixas, retomada e comportamento de sessão do original.
- HLS/M3U8 e formatos suportados pelo Media3/dispositivo; redirecionamentos HTTP tratados. Teste HLS usa HTTP 307 e decodificação real de vídeo sintético.
- Interface escura/vermelha, marca STARLINK original em vetor, capas em retrato, banner de destaque e cartões.
- Layout de celular e Android TV, foco visível e navegação por D-pad; permissões/manifestos próprios para os dois inicializadores.
- Mensagens distintas para HTTP 401, 403 e 5xx, sem renderizar URLs, corpos de erro ou credenciais. HTTP 403 não é apresentado como prova de URL incorreta.

## Validação

- Local: 66 testes core e 104 testes Android de unidade, zero falhas/erros/ignorados.
- Lint: zero erros e 18 avisos, sem desativar verificações. Compilação dos APKs principal e de instrumentação aprovada.
- Execução 39746432251: 12 testes em celular API 35 e 12 em Android TV API 36, cinco classes por dispositivo, zero falhas ou testes ignorados. Inicialização real, formulários, playlist, navegação, foco remoto, sessão/MP4 e HLS com 307.
- CI final do commit de código: https://github.com/sempaio/ottplay-foss-android/actions/runs/39747628016 . Build, celular e Android TV aprovados. Cada dispositivo executou os 12 testes da suíte, sem falhas. Os relatórios finais estão em `docs/evidencias-starlink/celular-final.json` e `docs/evidencias-starlink/android-tv-final.json`.
- Contrato público registrado no OpenAPI para player_api.php; consulta sem parâmetros respondeu 422, como esperado para parâmetros obrigatórios. HEAD da playlist completa respondeu 200, application/x-mpegurl.

**Limite essencial:** autenticação com conta real e reprodução de streams reais do M81 não foram executadas. As credenciais da imagem enviada pelo usuário não foram utilizadas nem registradas no projeto. HTTP 403 foi observado nessa captura, não reproduzido com conta real por este agente. O contrato permite 403 para recusa de acesso; a causa concreta daquela conta não foi confirmada. Não foi declarado que essa conta está funcional ou que o servidor está offline.

Detalhes, falhas corrigidas e escopo dos testes: `docs/STARLINK_TESTES.md`.

## Pendências

- Homologar uma conta de teste autorizada e streams reais M81 em celular/TV físicos.
- Chromecast/Google Cast não implementado; exige integração própria, distinta de Android TV e de espelhamento.
- Tela consolidada de histórico não implementada; posições de retomada estão preservadas e testadas.
- PiP do celular preservado do upstream, mas não homologado em aparelho físico nesta missão.
- Não há suporte declarado a Samsung Tizen ou LG webOS.
- Para distribuição continuada/produção, configurar uma chave estável própria fora do Git. Não foi fornecida chave ou instalação existente para validar continuidade de assinatura; não há release assinada para Play Store.

## Instalação e uso

1. Baixar `STARLINK-IPTV-debug.apk`. Pelo GitHub, abrir a execução acima, baixar o artefato `STARLINK-IPTV-debug-APK` e extrair o ZIP.
2. No Android 8.0 ou superior, abrir o APK e autorizar a instalação pelo aplicativo usado para baixá-lo, quando solicitado pelo sistema.
3. Abrir STARLINK IPTV, escolher “Entrar com usuário e senha” e usar uma conta M81 ativa. Não digitar endereço na tela padrão.
4. Após login aceito, selecionar Canais, Filmes, Séries ou Favoritos. No seletor de fonte, escolher “Lista STARLINK IPTV” para comparar a playlist adicional.
5. Na TV, instalar o APK por um método de sideload disponível no aparelho; o inicializador Leanback está declarado e a navegação usa D-pad.

Se a instalação existente tiver outra assinatura, preservá-la e seus dados. Não desinstalar automaticamente para contornar o conflito. Rollback e recuperação: `docs/STARLINK_ROLLBACK.md`.
