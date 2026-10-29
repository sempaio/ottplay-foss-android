# Capas: rastreamento na base funcional 0.3.2-layout

## Base e objetivo

O proprietário confirmou em 29/10 que a versão atual funciona perfeitamente e
autorizou continuar a melhoria nº 1: identificar por que as capas de Séries
aparecem rapidamente e aplicar o mecanismo comprovado em Filmes e TV ao vivo.

Base: starlink/celular-catalogo-032, commit
6fbc0b626f8b335103dbbfc1175e5171c5cf346b. A comparação com f25fafdcc39ab91390c428dd21dbdb5dda8d90f5,
origem do APK layout escolhido, encontrou somente documentação, evidências e
ajustes do harness instrumentado; o código do aplicativo é o mesmo nessa base.

Esta investigação não reaproveita a candidata VOD, a redução de posters ou o
decoder SVG do PR #6. Não altera URLs, miniaturas, política de cache, HTTPS,
credenciais, reprodução ou catálogo do painel. Todas as abas já usavam o mesmo
Coil; igualar bibliotecas não identifica a origem da diferença relatada.

## O que foi acrescentado

Somente a variante originsTest habilita o diagnóstico:

- resultado Coil por tipo e tela: memória, disco, rede, erro ou cancelamento;
- dimensão do bitmap decodificado e tempo até a imagem ficar pronta;
- destinos reais de cada resposta HTTP, incluindo os redirecionamentos;
- status HTTP, bytes reportados pelo OkHttp e tempo até os cabeçalhos;
- identificação explícita de HTTP 304, sem tratá-lo como download integral;
- número local R1/R2 etc. para relacionar os saltos à mesma solicitação;
- inventário das URLs de imagem recebidas no catálogo local da fonte selecionada,
  agrupadas por tipo e origem, com contagem de URLs distintas/repetidas;
- classificação também de recursos do APK, arquivos, content URIs e dados
  embutidos caso apareçam nos metadados. Inventário não comprova suporte ou
  sucesso da decodificação de todos esses formatos.

O observador de rede é um EventListener; não intercepta nem transforma corpos,
respostas, headers ou pedidos. A identificação do tipo usa uma tag local do
OkHttp, nunca um cabeçalho enviado ao servidor. O mesmo ImageLoader e os caches
Coil continuam responsáveis pelas imagens.

No máximo 300 resultados de imagem e 300 respostas HTTP são mantidos em memória.
Não são retidos títulos, IDs do catálogo, nomes de conta, caminhos, queries,
senhas ou mensagens brutas de exceções. O relatório contém domínios e tipos de
origem. Não há upload automático nem leitura de dados de outro aplicativo.

## Como usar

APK: STARLINK-IPTV-ORIGENS-LAYOUT-TESTE.apk.
Pacote: br.starlink.iptv.origins. Nome: STARLINK IPTV Origens.

Instala ao lado do br.starlink.iptv.layout confirmado; não substitui esse pacote
nem precisa desinstalar ou apagar seus dados. Use a mesma conta/fonte para a
comparação. O diagnóstico tem cache e sessão próprios, inicialmente vazios:
ele NÃO lê retroativamente o cache privado da versão funcional.

1. Abrir Séries, Filmes e TV ao vivo, rolando quantidade semelhante em cada aba.
2. Repetir a visita às mesmas capas, para comparar primeira carga e cache quente.
3. Em Mais → Origem das capas → Atualizar relatório → Copiar relatório.
4. Para comparar persistência em disco, encerrar/reabrir o processo, sem limpar
   dados/cache, e repetir o mesmo percurso.

“Zerar medições” limpa apenas os eventos desta sessão. Não apaga imagens,
catálogo ou conta. Pedidos iniciados antes do reset não contaminam a nova geração.

## Como interpretar

- SERIES com MEMORY_CACHE/DISK e poucos pedidos HTTP: reutilização local.
- SERIES com 200 rápido em domínio diferente: investigar essa origem/CDN.
- Vários itens com uma única URL: possível reutilização da mesma imagem.
- MOVIE/LIVE com 301/302/307: conferir os destinos seguintes da mesma solicitação.
- HTTP 304 com imagem concluída: revalidação de bytes já salvos.
- HTTP 403/404 ou ERROR: não tratar como imagem apenas lenta.
- Endereço antigo no inventário: o catálogo salvo pode divergir da publicação atual.

Não concluir que NETWORK sempre significa download integral. Os bytes são os
observados pelo OkHttp e não incluem toda a sobrecarga de transporte/TLS. Tempos
até cabeçalhos incluem conexão e saltos anteriores. Imagens de servidores com
cache interno/CDN sob o mesmo domínio não revelam a origem upstream original.

## Verificação

Testes cobrem origens arbitrárias/locais, sigilo, limitação/reset, inventário por
fonte, URLs repetidas, redirecionamento entre hosts, resposta 304, decode de PNG
e o percurso real rede → memória → disco com um único download. Outro teste
verifica revalidação real do cache Coil com ETag/304 e reaproveitamento do PNG.

Workflow: .github/workflows/starlink-artwork-origins-layout.yml.
Executa core:test, app:testOriginsTestUnitTest, lintOriginsTest e assembleOriginsTest,
confere assinatura/identidade e publica APK/checksum e relatórios separados.
Consultar a execução correspondente antes de declarar validação aprovada.

Compilar/testar o observador não comprova a causa da rapidez no telefone.
O diagnóstico precisa do percurso do aparelho para escolher a correção. Até
essa evidência, nenhuma alteração de desempenho foi implantada no aplicativo
funcional ou no painel, e o APK de download operacional continua preservado.
