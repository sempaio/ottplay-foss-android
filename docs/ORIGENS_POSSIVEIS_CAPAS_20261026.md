# Rastreamento das possíveis origens das capas

Data: 26/10/2026. Relato: capas de Séries rápidas, capas de Filmes e TV ao vivo
lentas. O objetivo é identificar a origem do comportamento antes de replicá-lo.
Não atribuir automaticamente a rapidez ao TMDB, ao painel ou a uma biblioteca.

## Resultado da investigação estática

Não existe uma lista fechada de sites possíveis: listas e APIs podem fornecer
qualquer endereço de imagem aceito pelo cliente, e redirecionamentos podem mudar
o servidor final. O mapeamento abaixo cobre os caminhos encontrados no projeto;
não demonstra qual deles foi usado pelo telefone do operador.

| Caminho possível | Evidência no projeto | Aplicação ao caso |
| --- | --- | --- |
| Bitmap já em memória | Coil compartilhado em OttplayApplication; resultado MEMORY_CACHE | Pode mostrar a capa instantaneamente sem consultar servidor. Não confirmado no aparelho. |
| Arquivo no cache de disco | HttpUriFetcher do Coil 2.7.0; resultado DISK quando elegível | Pode funcionar após fechar/reabrir o processo, sem baixar a imagem de novo. Não confundir com o SQLite do catálogo. |
| Cache com revalidação HTTP | Coil respeita Cache-Control e combina 304 com os bytes locais | NETWORK não prova download integral. O relatório atual não distingue 200 e 304/bytes. |
| Capa solicitada antes na tela inicial | NeonHome/PosterCard/FeaturedContent usam o mesmo carregador da grade | Uma imagem já exibida no início pode estar quente ao abrir a aba. É hipótese verificável, não efeito exclusivo de Séries. |
| Mesma URL reutilizada em vários itens | A chave de disco padrão Coil é a URL; o backend usa a capa do primeiro registro de uma série | Repetição de imagens pode produzir muitos hits; comparar quantidade de URLs únicas por tipo. Não confundir muitos itens rápidos com muitos downloads. |
| URL persistida de um catálogo anterior | CatalogDatabase serializa MediaEntry.logo; cached() lê esse catálogo sem refresh obrigatório | O app pode continuar usando um endereço antigo/diferente do que o painel gera hoje. SQLite guarda links e metadados, não o bitmap. |
| Imagem indicada pela M3U | M3uParser recebe entry.logo do parser compartilhado | tvg-logo pode apontar para painel, CDN, GitHub/raw ou outro host. Nome da categoria não identifica o servidor da imagem. |
| Imagem indicada pelo Xtream | XtreamProvider converte XtreamItem.logo; painel retorna cover para séries e stream_icon para canais/filmes | Os campos diferentes convergem no mesmo MediaEntry.logo. No backend lido ambos vêm de row.logo_url; não há prova de tratamento de imagem exclusivo de Séries. |
| Outra capa retornada no detalhe de filme | XtreamProvider.vod usa info.movie_image quando disponível | A capa do diálogo pode ser diferente da capa recebida na listagem. Não presumir que grade e detalhe baixam o mesmo arquivo. |
| Imagem indicada por portal Stalker/RPC | StalkerProvider converte value.logo | Caminho existente para fontes desse tipo. O login gerenciado M81 é Xtream; presença do código não comprova uso. |
| Arquivo já cacheado no M81 | /channel-logo/{id}.png retorna data/channel_logos/{id}.img | O telefone pode receber imagem do painel cuja origem anterior foi outro site. O URL público não revela essa origem upstream. |
| Override, fonte original ou recuperação de logo no painel | iptv_logos.resolve_exact_tvg_logo e recover_original_logo_sources; iptv_content._effective_entry | A imagem pode vir de edição manual, tvg-logo original, backup de mapeamento ou vínculo exato por stream/tvg-id. A publicação pode congelar uma URL anterior. |
| Fallback do painel | Branch recente app/main.py: PNG genérico 192×108 quando falta arquivo | Pode aparecer depressa sem ser a capa real da obra. Os PNGs amostrados não eram esse fallback; não generalizar para todo o catálogo. |
| Recurso incluído no APK ou placeholder | NeonEmblem, starlink_logo, iniciais do título; fetchers de recurso/arquivo/conteúdo suportados pelo Coil | A marca/placeholder pode aparecer antes da imagem remota. Não encontrei um catálogo de posters de séries embutido nos recursos raster identificados dos APKs examinados. |
| Redirecionamento para outro servidor/CDN | Cliente OkHttp de imagens e redirects HTTP | O domínio inicial pode diferir do domínio que entrega os bytes. O diagnóstico atual mostra o domínio solicitado, não todos os saltos finais. |
| Busca independente no TMDB no player web/FOSS | src/index.ts: TMDb.get e poster_path/backdrop_path; /tmdb/s/* e src-rs/core/src/tmdb.rs | Caminho real do player web/Tauri. Não está conectado automaticamente ao aplicativo Kotlin nativo. |
| Origem de outros providers/plugins web | src/provider/catalog-drivers.ts e media-catalog.ts recebem logo do provider; plugins têm adaptadores próprios | Presença no repositório não demonstra que o plugin faça parte da tela/APK instalado. Exige rastrear a variante. |
| Carregador do clone legado IPTV Pro | Painel: CategoryLogoLoader.java e scripts/build_starlink_iptv.ps1 | Arquitetura diferente: cache de 48 bitmaps, três workers; bind() consulta category-logos.json e tenta URLs da categoria; bindLogo() recebe a URL do item. Não copiar esse patch para o Kotlin por semelhança visual. |

## Verificação dos APKs disponíveis

Examinados os DEX e o inventário ZIP de:

- dist/STARLINK-IPTV-SERIES-TESTE.apk;
- dist/STARLINK-IPTV-CAPAS-TESTE.apk.

Ambos contêm classes Coil, sem classes Glide/Picasso identificadas pelos namespaces
pesquisados e sem uma interface web empacotada. No APK Séries não aparecem as
strings image.tmdb.org, api.themoviedb.org, thetvdb.com, fanart.tv ou
category-logos.json. No APK Capas, image.tmdb.org aparece também na classificação
de diagnóstico; isso não é prova de consulta à API TMDB.

A ausência de uma string não exclui URL recebida dinamicamente, redirecionamento,
cache local ou infraestrutura CDN por trás do mesmo domínio. Esses artefatos não
provam qual versão/pacote/assinatura está instalada no telefone.

O fluxo Kotlin usa MainActivity/setContent/Compose. O TMDB do player web é outro
fluxo. A rotina antiga de recuperação por SQLite em build_starlink_iptv.ps1 está
em bloco histórico comentado; não tratá-la como rotina atual em execução.

## Observação do endpoint público

category-logos.json respondeu 200, com 12.143.748 bytes e aproximadamente 20,5 s
nesta consulta. Ele agrega logos por categoria da playlist legada, incluindo
canais contínuos com nomes de séries. Não é uma exportação autenticada dos
posters efetivamente salvos no Kotlin. O clone antigo consulta esse endpoint;
o APK Séries nativo examinado não contém essa rota.

Portanto essa medida identifica uma possível espera no caminho legado, mas não
explica sozinha o relato do aplicativo mais recente.

## Como distinguir as hipóteses sem destruir o estado útil

1. Identificar pacote, versão e revisão do APK efetivamente instalado.
2. Ler o catálogo local da mesma fonte e comparar as URLs de capa realmente
   recebidas por LIVE, MOVIE e SERIES; contar ausentes e URLs únicas por tipo.
3. Registrar o resultado Coil por tipo: memória, disco, rede, erro ou cancelamento.
4. Para pedidos de rede, observar domínio inicial e final, redirects, MIME,
   dimensões, status 200/304, bytes e tempos até cabeçalhos e imagem pronta.
5. Comparar itens nunca exibidos, já exibidos e após reabrir o processo. Não
   limpar cache, conta ou dados; isso apagaria a evidência do caminho rápido.
6. Identificar o mecanismo comprovado e aplicá-lo aos outros tipos, mantendo a
   identidade da imagem, atualização do cache e isolamento de conta/fonte.

O diagnóstico disponível já cobre fonte selecionada e memória/disco/rede por
tipo. Ainda não fornece todo o percurso de redirects nem status/bytes HTTP.
Não é possível inferir os arquivos privados do telefone a partir do GitHub.

## Estado das alterações candidatas

A redução de miniaturas de filmes e o suporte SVG do PR #6 são melhorias
separadas. Não foram integrados à main nem implantados e não são apresentados
como explicação comprovada para Séries rápidas. A CI Android específica do
commit bcf06cbbe18622cf874f70d3b2c5bebd59a9b8ef passou (testes core/app,
lintArtworkTest, assembleArtworkTest e verificação de pacote/assinatura), na
execução 40277992914. Isso não comprova desempenho no aparelho nem aprovação
integral dos demais workflows do repositório.

## Arquivos que sustentam este mapa

Android: native/app/src/main/java/play/ott/nativeapp/OttplayApplication.kt,
ui/CatalogArtwork.kt, ui/NeonHome.kt, data/CatalogDatabase.kt,
data/NativeRepository.kt; native/core/src/main/kotlin/play/ott/nativeapp/core/
{M3uParser,XtreamProvider,StalkerProvider}.kt.

Painel: app/routers/xtream_api.py, app/services/xtream_series.py,
app/services/iptv_logos.py, app/services/iptv_content.py, app/services/channel_central.py,
app/routers/app_auth.py e app/main.py na branch starlink/painel-media-compat-20261025.

Coil 2.7.0: coil-base/src/main/java/coil/RealImageLoader.kt e
coil-base/src/main/java/coil/fetch/HttpUriFetcher.kt no repositório oficial coil-kt/coil.
