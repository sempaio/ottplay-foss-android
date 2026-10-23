# STARLINK celular — catálogo 0.3.2

Base estável preservada: versão 0.3.1-layout, commit 028cde75bbc45eb1ed41f0adc7d5485e953e36c3, branch starlink/layout-neon. Desenvolvimento isolado em starlink/celular-catalogo-032.

## Interface

Cabeçalho, pesquisa, categorias, aviso de reprodução e contagem passam a fazer parte da mesma grade lazy dos conteúdos. Ao deslizar para cima, os controles saem da tela e liberam espaço para mais capas. Categorias continuam rolando horizontalmente. Atualizar fica como ícone compacto à direita do título, com descrição acessível. Navegação inferior preservada.

## Séries — investigação

O usuário confirmou que Chaves (título simples) abre temporadas e episódios e reproduz. Os títulos [24H] Chaves [S07] e [24H] Chapolin Colorado [S01] não oferecem episódios.

Consulta somente de leitura ao painel: app/services/xtream_series.py exige marcadores explícitos de temporada e episódio (S01E02, 1x03 ou temporada/episódio). Reexecução local da função pura: os dois títulos [24H] retornam None; Chaves S01E01 e Chaves S02E03 são reconhecidos corretamente. app/routers/xtream_api.py publica registros pending e ambiguous como séries visíveis, com episódios vazios. Isso explica um caminho possível para a tela vazia; não comprova a resposta real de cada item no servidor.

O aplicativo não deve inventar número de episódio, mesclar nomes parecidos ou substituir uma série sem episódios por URL de canal deduzida. Teste simulado verifica get_series_info com episode_metadata=pending e episodes={} sem fabricar mídia. Casos já agrupados permanecem intactos.

O usuário autorizou preferir correção da lógica do painel quando necessária. Nenhum arquivo ou serviço do painel foi alterado: ainda falta confirmar metadados/tipo e resposta de um registro afetado na publicação real para distinguir canal contínuo classificado como série, metadados incompletos e conflito entre episódios. Produção, banco e publicação permanecem preservados. Não declarar séries corrigidas nesta versão.

## Validação

Compilação aprovada, 69 testes do núcleo e 118 do aplicativo aprovados, lint sem erros. Teste de gesto no emulador em preparação. TV, reprodução em segundo plano, DLNA e espelhamento seguem fora desta entrega.

## Rollback

A versão estável 0.3.1 permanece disponível no commit da base acima; criar worktree separado para recompilar sem apagar alterações. Pacote e chave de assinatura da prévia preservados. Android pode impedir downgrade do código 10 para 9; não remover o aplicativo nem seus dados automaticamente.

Correção candidata do painel: https://github.com/sempaio/painel-m81-iptv-cloud/pull/1 (draft). Reconhece separadores explícitos [S01] [E02] e S01.E02; quatro testes puros aprovados. Não implantada e sem comprovação de que esse formato explica todos os registros reais.
