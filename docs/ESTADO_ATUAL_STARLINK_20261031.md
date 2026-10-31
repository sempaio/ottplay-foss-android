# Estado atual STARLINK IPTV — 31/10/2026

> Retomada autorizada em31/10/2026: consulte a [entrega do APK de teste e os resultados finais](ENTREGA_STARLINK_PARA_VOCE_20261031.md). O checkpoint/estado abaixo é histórico. Não equivale a instalação oficial ou aprovação de todos os provedores.

**Desenvolvimento e testes interrompidos por solicitação do proprietário. Esta atualização é somente documental. Candidata não totalmente homologada. Não substituir o aplicativo oficial.**

## Código salvo

Repositório: sempaio/ottplay-foss-android.
Branch: `starlink/032-consolidacao-local-20261030`.
Código Android normal validado: `ca1e59e475bccf4867bba5fe18c817d133bf4384`.
HEAD remoto conferido em31/10: `388209fc9cb5f31a2c912920df61398e2d50d231`, após integrações dos PRs11,12 e14. A diferença desde ca1e59e4 contém documentação, configuração QA/CI e Biome; não altera `native/app/src/main` nem `native/core/src/main`. Preservado sem merge sobre o checkout local.
Esta atualização documental usa branch própria `docs/estado-starlink-pausado-20261031`, derivada desse HEAD remoto. O PR13 para main permanece separado e não foi integrado.
Base oficial conferida: `starlink/celular-catalogo-032`, `6fbc0b626f8b335103dbbfc1175e5171c5cf346b`.
PR9: `0f476cd4be845d35a45afdb231c7084358b39712`.
Não houve merge na main, substituição do aplicativo oficial ou implantação M81.
[Comparação das branches](evidencias/BRANCHES_CONSOLIDACAO_20261030.json).

## Implementado na candidata

- Para Você local: assistente, interesses/subtemas, Pular/Voltar, consentimento inicialmente desligado, perfis, recomendações e votos. Nenhuma IA externa obrigatória ou envio de preferências ao M81.
- Player do PR9: tela cheia/horizontal no telefone, parada ao sair, segundo plano optativo e PiP condicionado. Regras específicas de TV preservadas.
- Favoritos, busca e Guia TV mantidos na Home; filtros/recomendações fora da thread principal.
- Histórico criptografado independente para Perfil 2. Banco SQLite **do aplicativo** passa de versão2 para3; testes de atualização preservam o histórico original. Não confundir com PostgreSQL M81.
- Placeholder neutro, removido quando a imagem carrega, sem marca STARLINK sobre arte de cartões. Sem inventar pôster/backdrop quando a API só fornece logo.
- NFL Channel LIVE em Futebol recebe apresentação Esportes. Não altera tipo, URLs, IDs ou publicação.
- GETs de carregamento Xtream repetidos uma vez após timeout, priorizando próximo endereço DNS do mesmo host. Não fixa IP nem relaxa TLS. Falhas persistentes podem demorar dois timeouts mais 500ms. Esta recuperação não sana uma indisponibilidade geral do Funnel.

## Evidências reais, com seus limites

No emulador, o APK intermediário com correção de rota carregou **1997 canais, 26583 filmes e 5152 séries**. Chaves exibiu temporadas/episódios; S01E01 apresentou quadro de vídeo e PLAYING com posição19264ms. Patagonia apresentou PLAYING com posição7033ms. Não houve validação de áudio audível, pois o emulador foi iniciado sem áudio.

O APK intermediário instalado em `br.starlink.iptv.paravoce` tinha SHA `c76afa79225db906450047536524dbb9e7bbb12246c4d95e9a7bc0b9b0236260`. **Não é prova de reprodução do build consolidado ca1e59e4**, que acrescenta históricos/cartões. O APK oficial `br.starlink.iptv.layout` foi mantido separadamente; não foi substituído. A versão antiga também foi capturada aguardando handshake TLS, mas a comparação autenticada completa não terminou.

Sonda HTTPS real no Android: um IP público expirou nos dois protocolos TLS testados; outro respondeu200 com TLS1.2 eTLS1.3. IPv6 não conectou nessa rede. Isso comprova diferença de rotas naquela medição, não responsabilidade definitiva do Tailscale nem estabilidade permanente da rota alternativa.
[Evidências sanitizadas](evidencias/EMULADOR_TLS_20261030.json).

The Highest Stakes apresentou erro genérico em uma tentativa. Não se concluiu a causa e não foi removida qualquer proteção editorial para fazê-lo tocar.

## Testes e CI

- Local: **75 core +145 app**, zero falhas/erros/ignorados.
- Lint: **0 erros, 20 avisos e1 hint**.
- Compilação do APK Para Você e dos APKs QA/instrumentação concluída.
- CI nativa do código ca1e59e4: **SUCCESS**.
- [Execução validada](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645448).
- [Download do artefato APK da CI](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645448/artifacts/13011152511) — arquivo compactado; pode exigir login GitHub; expiração de30dias. Não é release definitiva.

**Instrumentação LOCAL Android37 incompleta:** o log parcial contém15 entradas de erro e não contém resumo final. Foi observada `NoSuchMethodException: android.hardware.input.InputManager.getInstance` na infraestrutura Espresso/Android37. A execução foi interrompida; não chamar esses casos de aprovados ou contabilizar a interrupção como sucesso. Não foi feita correção dessa incompatibilidade após a ordem de parada. Restam reexecução em ambiente compatível e análise de cada resultado.

**Resultado remoto posterior, preservado:** [CI AOSP Android35, run41132084266](https://github.com/sempaio/ottplay-foss-android/actions/runs/41132084266), commit `a77a366ceaaaf0fa9c4bb3319d68d74998668c16`, concluiu SUCCESS. O relatório remoto registra quatro casos aprovados: múltiplos interesses/subtemas/Voltar/consentimento desligado, Pular, logo transparente e Home interrompendo reprodução com segundo plano desativado. A correção foi exclusiva da fixture QA (`CUSTOMER_MODE=false`), sem mudar o APK normal/player. Isso NÃO apaga a falha local API37 nem comprova toda a suíte, áudio físico ou todas as fontes. Não foram repetidos testes nesta atualização documental.

O documento remoto também registra confirmação funcional do proprietário depois de atualizar a lista, inclusive recuperação VOD. Essa confirmação veio do registro do GitHub; não foi repetida/observada por esta sessão. Preservar essa evidência sem chamá-la de homologação de todos os títulos.

O pacote QA é `br.starlink.iptv.paravoce.qa`, separado das sessões reais. Os processos QA foram encerrados para respeitar a ordem de parada; arquivos/logs privados permanecem locais. A instrumentação usa dados sintéticos e não substitui validação real de conta, player ou TV Box.

O Quality Gate do HEAD remoto388209fc, [run41134633334](https://github.com/sempaio/ottplay-foss-android/actions/runs/41134633334), continua FAILURE: lint e dependency-review falharam. Os demais resultados não anulam esses bloqueios. Não foi desativado para obter aprovação. A aprovação da CI nativa não equivale à aprovação de todos os workflows.

## EPG e M81 preservados

Última conferência operacional em30/10: CoreAPI saudável, código144dd6a, imagem4594259e; publicação290, ID292, **170016 registros**, SHA `d0b0d616a91576eda948f0a6bb2712e73c5a2550d6bd385653d5deb2c84f5fe1`, igual no arquivo bruto. Não houve modificação/reinício do M81 nesta consolidação ou nesta atualização documental. Backups e rollback existentes preservados.

A consulta anterior do EPG encontrou3785 programas antigos, zero atuais/futuros, último término18/10. O aplicativo tem consulta XMLTV e fallback get_short_epg; ausência de grade no servidor não pode ser corrigida inventando programas no cliente. Não houve refresh/sincronização operacional para esconder o problema. Guia real atualizado permanece pendente.

## Artefatos e preservação

- Código consolidado e documentação estão nesta branch; arquivos privados, vaults, logs brutos e capturas de reprodução não foram enviados.
- `dist/STARLINK-IPTV-layout.apk` continua sendo o APK oficial antigo; não apresentá-lo como Para Você novo.
- APKs locais e anteriores mantidos, assim como os checkouts sujos anteriores.
- O APK da CI usa assinatura debug própria do runner. Não assumir que sua chave coincide com a compilação local; não desinstalar automaticamente uma instalação com outra chave.
- A instalação oficial tem outro pacote e permanece disponível. Downgrade do pacote de teste com banco local versão3 exige estratégia compatível; não abrir esse banco em APK anterior à alteração sem preservar os dados primeiro.

## Próxima retomada, não executada

1. Partir do HEAD remoto e dos commits de código/QA identificados acima, conferir status e preservar os checkouts locais. Não sobrepor o checkout local divergente ao remoto.
2. Resolver/reproduzir incompatibilidade Espresso/Android37 em pacote QA isolado; não mascarar testes.
3. Repetir primeiro acesso, atualização, concorrência moderada, player/Voltar/Home/PiP/orientação, retomada, favoritos e perfis no build final.
4. Testar canais e EPG reais, distinguir falha de origem/grade vazia e testar telefone/TV Box disponíveis.
5. Conferir assinatura/SHA do APK efetivamente entregue e concluir homologação antes de qualquer substituição. Não alterar M81 para aprovar Android.

Este documento substitui afirmações de estado corrente dos relatórios anteriores, mantendo-os como histórico. Não autoriza merge, retomada de testes, implantação, atualização de fontes ou instalação no cliente.
