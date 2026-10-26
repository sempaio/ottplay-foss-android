# Capas de TV ao vivo e Filmes — candidata de 26/10/2026

## Relato e escopo

O usuário esclareceu: somente as capas de Séries aparecem rapidamente; as de
TV ao vivo e Filmes demoram. Não é relato de demora na lista, temporadas ou vídeo.
Base: `starlink/vod-performance-20261025`, commit
`0ca37a184ceb3a1456d1d45cb26b56dbde86a3ae`. A main contém apenas README.

Pesquisa: código do fork e parser do upstream open-ott-play/ottplay-android,
documentação e código Coil 2.7.0, documentação de imagens TMDB e branch do painel
`starlink/painel-media-compat-20261025`. Nenhum código de outro player foi copiado.

## Evidência

Todas as abas já usam o mesmo ImageLoader/Coil. SeriesEpisodeCache guarda listas
de episódios, não capas. Não existe um algoritmo exclusivo de capas das Séries
para copiar às outras abas.

Uma amostra parcial do endpoint público de logos do painel, consultado nesta
execução, contém capas TMDB `w600_and_h900_bestv2` e logos `/channel-logo/`.
Sondas paralelas independentes deram:

| Amostra | HTTP / tipo | Bytes | Primeiro byte | Total |
| --- | --- | ---: | ---: | ---: |
| Filme, 600×900 | 200 / JPEG | 200905 | 4,170 s | 4,247 s |
| Mesmo filme, w500 | 200 / JPEG | 146722 | 4,256 s | 4,331 s |
| Mesmo filme, w342 | 200 / JPEG | 79831 | 4,198 s | 4,256 s |
| Canal A | 200 / PNG | 6358 | 3,976 s | 4,017 s |
| Canal B | 200 / PNG | 10739 | 3,852 s | 3,978 s |
| Canal C | 200 / PNG | 6480 | 4,566 s | 4,607 s |

Medições deste ambiente, não do telefone. A latência anterior ao primeiro byte
domina estas amostras; não é possível atribuí-la integralmente ao servidor.
A versão w500 economiza 26,97% dos bytes, mas esta medição não demonstra redução
do tempo total. w500 foi escolhido para preservar definição dos cards em telas
densas; w342 economiza mais, mas pode perder nitidez.

Os três canais já têm Cache-Control público de 86400 s e ETag. A branch recente
do painel já retorna PNG no fallback, superando o diagnóstico antigo de SVG.
Os PNGs testados são válidos; não afirmar que SVG explica o relato atual.

## Correção implementada

- Somente miniaturas de Filmes na grade/início: imagens grandes do host exato
  image.tmdb.org passam a usar w500. Imagens já menores não são ampliadas.
- URL de capa e dados de Séries/Episódios permanecem intactos. Detalhes/destaques,
  URLs assinadas/com query/fragmento, hosts desconhecidos e SVGs não são reescritos.
- Registro explícito do decoder SVG 2.7.0 no mesmo ImageLoader. Corrige falha de
  compatibilidade com logos vetoriais de fontes ou servidores antigos, inclusive
  respostas SVG em endereços .png. Não é apresentado como causa das sondas PNG.
- Mantidos os caches existentes Coil, política de transporte/HTTPS e resolução
  automática do tamanho. Nenhum cache paralelo, prefetch de catálogo, mudança
  de credenciais, classificação M3U, vídeo, banco, publicação ou deploy.

## Validação e limites

Testes de regressão cobrem miniaturas, preservação de Séries/detalhes/assinaturas,
e decode real de SVG via HTTP seguido de hit em memória sem segundo GET.
O workflow isolado executa core:test, app:testLayoutUnitTest, lintArtworkTest e
assembleArtworkTest; valida pacote/assinatura e publica artefato com checksum.
Consultar o resultado da execução antes de anunciar aprovação.

O wrapper local não baixou Gradle por indisponibilidade da conexão Java; isso
não é um teste aprovado nem falha do patch. A CI é a validação Android completa.

Para canais PNG pequenos, falta a comparação no próprio aparelho (Coil: memória,
disco, rede, erro/cancelamento) antes de prometer velocidade igual às Séries.
Não desativar Cache-Control nem remover tokens para forçar reuso. A candidata
artworkTest inclui o diagnóstico já existente para essa comparação, sem envio
automático de relatório. Sua primeira abertura não reutiliza dados de outro pacote.

## Entrega e reversão

Branch `starlink/capas-live-filmes-20261026`, baseada na candidata VOD recente.
APK de teste `br.starlink.iptv.artwork`, nome de artefato
`STARLINK-IPTV-CAPAS-CORRIGIDAS-TESTE`. Não presume assinatura compatível com um
APK anterior do mesmo pacote; não desinstalar nem limpar o app do usuário.
Os apps/layout/Séries existentes e o painel não são substituídos.

Reversão: retirar os arquivos/política desta revisão e o decoder adicional,
retornando à base citada, sem qualquer mudança de dados ou publicações.

Referências primárias:
- https://developer.themoviedb.org/docs/image-basics
- https://github.com/coil-kt/coil/tree/2.7.0/coil-svg
- https://github.com/open-ott-play/ottplay-android
