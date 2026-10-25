# STARLINK IPTV — mapa de entrada do projeto

Atualizado em 2026-10-25 a partir do GitHub e da captura do proprietário.
Este documento identifica componentes e referências; não atesta sozinho o que
está executando no Windows ou instalado byte a byte no celular.

## Começar aqui

O produto do proprietário é **STARLINK IPTV**. Ele usa componentes distribuídos
em vários repositórios. Nomes de repositórios OTT-play não significam que sejam
outro aplicativo sem relação com o STARLINK. Não escolher código apenas pelo
nome do repositório ou assumir que main contém o aplicativo em uso.

1. Confirmar versão/nome/pacote mostrado no aplicativo.
2. Resolver a versão para branch, commit, artefato e checksum abaixo.
3. Conferir fonte selecionada e catálogo local no app.
4. Conferir separadamente imagem/revisão do CoreAPI e publicação/fontes no painel.
5. Investigar o mesmo fluxo e os mesmos dados antes de modificar código.

## Componentes e referências verificadas

| Componente | Local/código | Referência e limite da confirmação |
|---|---|---|
| Android da captura | `sempaio/ottplay-foss-android`, `native/app` | Captura do proprietário mostra **0.3.2-series-test**. Branch correspondente `starlink/fix-series-formats-20261024`, artefato no commit `2b4297174ac5c6308bbcb58e03c688790609b049`. Não lemos o APK instalado para comparar checksum. |
| Android layout anterior | Mesmo repo, branch `starlink/celular-catalogo-032` | `f25fafdcc39ab91390c428dd21dbdb5dda8d90f5`, 0.3.2-layout, pacote `br.starlink.iptv.layout`; NÃO confundir com seriesTest da captura. |
| Android de diagnóstico | Este repo, branch `starlink/artwork-diagnostics-20261025` | Variante `artworkTest`, pacote `br.starlink.iptv.artwork`, app separado. O relatório identifica a revisão que gerou o APK. Não é o APK já instalado. |
| Adaptação de provedores/modelos | `native/core` | XtreamProvider, ProviderRepository, M3uParser, Models e políticas de transporte. |
| Núcleo compartilhado | `native/core/vendor/ottplay-core.jar` e `.manifest.json`; fonte canônica [open-ott-play/ottplay-core/shared-core](https://github.com/open-ott-play/ottplay-core/tree/main/shared-core) | O README nativo identifica o compartilhamento com FOSS2. Binário fixado por receipt; `verifySharedCore` valida integridade. Não substituir pelo projeto web/Rust. |
| Painel M81 / CoreAPI | `sempaio/painel-m81-iptv-cloud` | Backend/painel, publicação, fontes e compatibilidade Xtream. A main contém também o pipeline Android LEGADO 100.0.x. Esse pipeline não gera o app da captura. |
| Referência de investigação M81 | Branch `codex/rootcause-runtime-20261024` | Handoff registra base de código `9ef159835012973fe9a8a9c0bc2810ab8d77fe11`; a própria documentação registra diferenças na imagem. É evidência datada, não inspeção do runtime de hoje. |
| Outras linhas de código | Repositórios `ottplay-android`, `ottplay-netflix`, `ottplay-foss` e variantes TV | Não tomar automaticamente como fonte do Android da captura. Só seguir dependência demonstrada por manifest/receipt/importação. |

APK seriesTest rastreado em `dist/STARLINK-IPTV-SERIES-TESTE.apk`:
SHA256 `9439283324ff750220c77370def11e5ce64c1bbffaefbdee7ecef6a15f236fbe`.
Checksum conferido no clone em 25/10; não prova identidade do APK no aparelho.

## Integrações do aplicativo

```text
STARLINK IPTV Android (native/app: Kotlin + Compose)
  ├─ native/core + núcleo fixado em vendor/
  ├─ Conta M81 (XTREAM) → panel.taila5c5c2.ts.net/player_api.php
  │    ├─ categorias e listas de canais / filmes / séries
  │    ├─ get_vod_info e get_series_info sob demanda
  │    └─ autorização / redirect para a mídia da fonte
  ├─ Lista STARLINK IPTV (M3U) → mesmo painel /app/starlink-iptv/playlist.m3u
  ├─ imagens → Coil 2.7.0 → memória / disco / URL indicada no catálogo
  └─ vídeo → Android Media3 → URL autorizada da fonte

M81 / CoreAPI
  ├─ PostgreSQL + publicação editorial vigente
  ├─ sincronizador/importadores → fontes aprovadas
  ├─ entrega Xtream e M3U da publicação
  ├─ /channel-logo/<id>.png → cache de imagem do painel / fallback
  └─ entrada pública via Tailscale Funnel → serviços no computador do operador
```

`StarlinkConfig.kt` é a referência de integração do Android. Após login Xtream
aceito, `PlayerViewModel.SaveSource` adiciona a M3U quando ausente. Isso NÃO
significa que as duas sejam mescladas ou que saibamos qual está selecionada:
`selectedSourceId` e o catálogo salvo no aparelho decidem o fluxo efetivo.

## Repositórios externos que integram o catálogo

Estas ligações são rastreáveis pelo código; não é necessário abrir o painel
em execução para identificá-las. Provar uma integração de código é diferente de
confirmar o resultado da última sincronização/publicação.

| Repositório | Papel e ligação demonstrada |
|---|---|
| [Ramys/Iptv-Brasil-2026](https://github.com/Ramys/Iptv-Brasil-2026) | **Repositório de listas**. `app/services/fast_playlist_catalog.py` do M81 contém os presets `ramys-br:04` e `ramys-br:05`, com arquivos `CanaisBR04.m3u8` e `CanaisBR05.m3u8`, branch master. Não é o aplicativo Android nem o painel. |
| [BuddyChewChew/app-m3u-generator](https://github.com/BuddyChewChew/app-m3u-generator) | **Gerador/listas FAST e metadados Pluto**. `fast_playlist_catalog.py` aponta para `playlists/`; `pluto_catalog.py` usa `playlists/plutotv_br.m3u` como catálogo de metadados. O painel cria a entrega/sessão Pluto própria; não confundir essa dependência com reprodução direta da mesma M3U no cliente. |
| [sempaio/app-m3u-generator](https://github.com/sempaio/app-m3u-generator) | **Fork do proprietário**, encontrado no GitHub. A documentação M81 registra o fork; os módulos inspecionados ainda referenciam BuddyChewChew como upstream. Registrar os dois, sem afirmar que a existência do fork trocou automaticamente a URL usada pelo código. |
| [open-ott-play/ottplay-core](https://github.com/open-ott-play/ottplay-core) | Núcleo de interpretação/contratos compartilhados com a família FOSS. O Android consome a cópia compilada fixada pelo receipt em native/core/vendor; isso é integração de biblioteca, não mais um servidor de listas. |

Referências no painel: `app/services/fast_playlist_catalog.py`,
`app/services/pluto_catalog.py`, `app/services/provider_importer.py` e
`app/services/iptv_content.py`. Referência Android: `native/README.md`, seção
núcleo compartilhado, além dos adapters em `native/core`.

## Listas/provedores conhecidos

| Fonte | Evidência | O que ainda precisa do runtime |
|---|---|---|
| BR04 / Canais Brasil 04 | Preset `ramys-br:04` no M81 → `Ramys/Iptv-Brasil-2026`, `CanaisBR04.m3u8` | Aprovação, versão aceita, estado da sincronização e presença na publicação selecionada. |
| BR05 / Canais Brasil 05 | Preset `ramys-br:05`, mesmo repo, `CanaisBR05.m3u8` | Mesmas verificações. Uma resposta upstream de hoje não prova estado da publicação preservada. |
| Pluto TV Brasil | Importação/metadados e sessões nativas documentadas no M81; gerador `app-m3u-generator` faz parte dessa cadeia | Fonte ativa, publicação vigente e configuração atual de entrega. Não trocar por lista externa por suposição. |
| Filmes e Series GitHub / filmes vod | Documentação M81 registra provedores/fontes adicionais, incluindo Gist | Repositório/Gist cadastrado, Sources aprovadas e contribuição por item. Não inventar URL ausente na cópia sanitizada. |

GitHub hospeda código/listas, mas não é necessariamente o servidor das capas.
Séries e filmes podem usar imagens TMDB ou outros hosts, e o painel pode entregar
links locais. No Android da captura, todos os tipos usam o mesmo Coil/AsyncImage;
`SeriesEpisodeCache` guarda episódios e não bitmaps. Ver diagnóstico específico.

## O que falta para saber exatamente o estado atual

O GitHub não inclui o banco operacional, catálogo criptografado do telefone,
fonte selecionada, imagem Docker atual, alterações locais não publicadas nem
credenciais. Não pedir senhas ou dumps no chat para preencher esse mapa.

A captura confirma a versão exibida. Para ligar todas as peças sem adivinhação,
usamos um relatório de estado com:
- APK: versão/pacote/revisão de build/checksum;
- cliente: protocolo/fonte selecionada, host e contagens do catálogo local;
- servidor: revisão/imagem em execução, saúde e hora da coleta;
- catálogo: publicação/revisão/hash e nomes/IDs operacionais das fontes aprovadas;
- imagem: origem Coil, domínio/rota genérica e tempo, sem URL completa.

A candidata artworkTest já fornece versão/revisão de build, identificação da
fonte selecionada, contagens e diagnóstico de imagens. Ela NÃO afirma a versão
do servidor nem lista provedores do banco sem consultar o painel. Completar a
parte do servidor exige um relatório autenticado somente leitura ou acesso ao
ambiente operacional autorizado. Em 25/10 o painel retornou 503 deste ambiente;
o operador informou reboot. Isso não comprova o estado da rede no celular.

## Como manter a informação útil

Atualizar este mapa ou apontar um novo manifesto sempre que publicar outro APK,
branch de cliente, imagem de servidor ou snapshot de catálogo. Guardar histórico
com data e evidência; não sobrepor teste, candidata e produção. O manifesto da
build deve ser gerado pelo pipeline, e o estado operacional pelo runtime, para
não depender só de documentos escritos à mão.

Referências: `docs/STARLINK_REFERENCIA_CODEX.md`,
`docs/DIAGNOSTICO_CAPAS_032.md`, `native/app/.../StarlinkConfig.kt`,
`native/core/vendor/ottplay-core.manifest.json` e handoff M81
`docs/handoffs/INSTRUCOES_GEMINI_SERIES_20261024.md` na branch citada.
