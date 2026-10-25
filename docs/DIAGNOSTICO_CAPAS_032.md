# Comparação de capas — STARLINK IPTV 0.3.2-series-test

Data: 2026-10-25. Base Android: `2b4297174ac5c6308bbcb58e03c688790609b049`,
branch de origem `starlink/fix-series-formats-20261024`.

O proprietário confirmou que TODAS as capas de séries aparecem muito rápido;
as de canais e filmes demoram. Captura identifica `0.3.2-series-test` no Android.
Objetivo: achar a diferença e reutilizar o mecanismo rápido das séries.

## Confirmado no código correspondente à versão

- `native/app/build.gradle.kts`: Coil Compose 2.7.0; variante seriesTest,
  versionNameSuffix `-series-test`; version.properties: 0.3.2/código 10.
- `OttplayApplication.newImageLoader`: um único Coil ImageLoaderFactory e cliente
  OkHttp com AppTransportPolicy. Nenhuma seleção de carregador por MediaKind.
- `ui/OttNativeApp.kt`: as abas filtram a MESMA lista `state.entries`; todas
  passam por `MediaCard`. A mesma chamada `AsyncImage(entry.logo, ...)` atende
  séries/filmes/canais; séries e filmes têm inclusive a mesma proporção do card.
- `ui/NeonHome.kt`: `PosterCard` também usa `AsyncImage(entry.logo, ...)` para
  todos os tipos. Só canais têm modo paisagem/ContentScale diferente.
- `core/XtreamProvider.kt`: o adapter entrega `entry.logo` ao mesmo MediaEntry.
- Em relação à branch celular-catalogo-032, a variante testada não altera
  OttplayApplication, NeonHome ou OttNativeApp. A alteração específica de séries
  `SeriesEpisodeCache` guarda listas de episódios por fonte/série, apenas no
  OpenSeries. Não guarda ou carrega bitmaps/posters da grade.
- SHA256 do APK rastreado e arquivo .sha256 conferem:
  `9439283324ff750220c77370def11e5ce64c1bbffaefbdee7ecef6a15f236fbe`.
  Isso confere o artefato do repositório; não lê o APK instalado no telefone.

Portanto não há uma implementação exclusiva de imagem de séries para copiar
no código disponível dessa versão. Isso NÃO invalida o relato nem prova que a
rede/cache/URLs recebidos são iguais entre tipos.

## Pontos para comparação real, ainda sem conclusão

No painel, as capas Xtream de séries vêm de `row.logo_url` no campo `cover`;
canais/filmes vêm de `row.logo_url` no campo `stream_icon`. Publicação/importação
podem resultar em links originais externos OU em `/channel-logo/<id>.png`.
Este último endpoint retorna SVG fallback se o arquivo não existir; o loader
Android não instala um decoder SVG específico. É uma diferença de dados a
verificar por item, não causa confirmada do relato.

A exportação pode selecionar o link local pela existência de logo_id, sem
verificar cache_exists naquele momento. É necessário medir o conteúdo publicado,
não inferir a rota efetiva por esse trecho ou alterar uma publicação congelada.

Inspeção somente leitura da fonte BR04 no GitHub: TMDB é frequente tanto em
séries quanto filmes, portanto NÃO é correto atribuir rapidez somente a usar
GitHub/TMDB. A amostra de 30 MiB é parcial, da fonte upstream de hoje, sem
correspondência comprovada à publicação no telefone. BR05 upstream retornou 404.
Não trocar fontes operacionais baseado nisso.

## Bloqueio atual

GET /health e /app/starlink-iptv/category-logos.json retornaram HTTP 503 daqui.
O proprietário informou que reiniciou o computador; não usar esse estado como
prova da lentidão anterior. Sem sessão/app real e sem painel acessível não foi
possível comparar os links efetivamente recebidos pelo telefone nem os hits de
cache versus downloads, redirecionamentos, bytes ou tempos de decodificação.
Nenhum vídeo foi sondado nesta comparação; nenhum dado operacional alterado.

## Continuação

Quando o painel voltar, comparar amostras da MESMA publicação/fonte selecionada:
URLs efetivas de capa de série/filme/canal, origem direta versus painel, MIME,
redirecionamentos, bytes, headers de cache e duração; no Android, origem do
resultado Coil (memory/disk/network). Não registrar login, query, token ou URL de
mídia. Só então aplicar a diferença comprovada aos demais tipos.

Nenhuma mudança de produção, APK novo, publicação de branch ou código funcional
Android foi feita nesta investigação. Não aplicar o patch CategoryLogoLoader do
pipeline legado 100.0.x: ele não é usado pelo aplicativo da captura.

## Observação adicional do proprietário

Mesmo após o reboot informado, enquanto nossas consultas ao painel recebem
503, as capas das séries continuam aparecendo muito rápido no Android. Isso
reforça investigar independência do painel para as imagens exibidas: memória/
disco do Coil ou URLs diretas de CDN. Não distingue essas hipóteses sozinho,
porque o 503 foi observado deste ambiente e não no aparelho. Não pedir limpeza
de cache/dados nem reinstalação como primeiro teste: preservam-se a sessão e
o estado que evidencia a diferença. Para distinguir, observar a origem do
resultado Coil ou comparar URLs efetivamente persistidas no catálogo do app.

## Candidata de diagnóstico autorizada — 2026-10-25

Instrumentação do Coil por listeners em CatalogArtwork, sem alterar URL, headers,
ImageLoader, tamanho ou chave de cache. Grade, início, destaque e detalhes usam o
mesmo wrapper. Estados observados: MEMORY_CACHE/MEMORY, DISK, NETWORK, ERROR e
CANCELLED. O tempo é monotônico até o resultado final (não só transferência).

Variante `artworkTest`: `br.starlink.iptv.artwork`, 0.3.2-artwork-test,
**STARLINK IPTV Capas Teste**, assinada com chave de teste. Instala ao lado da
prévia e da versão seriesTest; não importa a sessão nem o cache do app original.
Logo, a primeira abertura mede o estado da candidata e não comprova a origem das
capas que já estavam cacheadas no APK original. Não desinstalar/limpar o original.
O flag ARTWORK_DIAGNOSTICS fica false em todas as variantes existentes.

Mais → Diagnóstico das capas: atualizar, copiar relatório ou zerar medições.
Zerar só descarta até 300 eventos em memória e ignora conclusão de pedidos que
começaram antes do reset; não limpa imagens, catálogo ou login. Relatório e
logcat (tag StarlinkArtwork) incluem tipo, domínio, rota genérica, origem e tempo;
nunca títulos, IDs, caminho completo, query, mensagem de exceção ou credenciais.
Não há upload automático ou serviço de telemetria.

Procedimento no aparelho:
1. Instalar a candidata como app separado e entrar com a mesma conta.
2. Abrir Séries, Filmes e Canais, rolando uma quantidade semelhante em cada aba.
3. Mais → Diagnóstico das capas → Atualizar relatório → Copiar relatório.
4. Voltar às abas e repetir para comparar a carga quente. Se quiser distinguir
   memória e disco, fechar o processo e reabrir a candidata, sem limpar dados.
5. O teste em modo avião é opcional: distinguir hit em disco de pedido de rede
   com falha pelo relatório. Não concluir que todo sucesso é download da sessão.

Testes novos: origens/tempo por tipo, remoção de credenciais/paths/queries/mensagens,
limite de histórico, reset durante pedido e cancelamento separado de sucesso.
Workflow isolado: starlink-artwork-diagnostics.yml, build+testes+lint+assinatura,
apenas artefato de Actions; não altera main, a branch anterior ou a produção.

A candidata inclui também identificação da versão/revisão de build, protocolo
e fonte selecionada (rótulo gerenciado), host configurado e contagens do catálogo
local por tipo. Não exporta nomes particulares da fonte, IDs ou credenciais.
Mapa de entrada do produto: `PROJETO_ATUAL.md`. Esses dados não identificam
a publicação do servidor sem uma consulta autenticada ao runtime.
