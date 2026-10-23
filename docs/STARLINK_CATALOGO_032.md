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

## Artefato candidato

Commit de código e APK: f25fafdcc39ab91390c428dd21dbdb5dda8d90f5. `dist/STARLINK-IPTV-layout.apk`: 23.054.198 bytes; SHA256 `856c576fb36a002c69082907077355455017ab6ff541cce361a51afbfb147bd4`. Versão 0.3.2-layout, código 10, pacote br.starlink.iptv.layout, chave estável mantida. Inspeção aapt/apksigner/DEX aprovada.

Execução: https://github.com/sempaio/ottplay-foss-android/actions/runs/40008804419 — em acompanhamento, não declarar teste de gesto aprovado antes do resultado.

Encaminhamento posterior: o usuário transferiu a resolução do painel ao Codex do computador. A candidata PR #1 e as observações acima servem como referência para esse trabalho; esta entrega continua restrita ao APK celular.

Preferência posterior do usuário: o botão de atualizar deve futuramente permanecer fixo no topo. Por instrução expressa de concluir a versão em andamento, esta candidata mantém o ícone compacto dentro do cabeçalho rolável. Não implementar o botão fixo nesta entrega.

Entrega por solicitação do usuário, sem aguardar testes adicionais do emulador. Build CI aprovado; 187 testes unitários locais aprovados; arquivo público baixado e SHA256 idêntico ao APK validado. Resultado de interface/gesto desta versão permanece pendente no momento da entrega.

Download: https://raw.githubusercontent.com/sempaio/ottplay-foss-android/f25fafdcc39ab91390c428dd21dbdb5dda8d90f5/dist/STARLINK-IPTV-layout.apk . Instalar sobre STARLINK IPTV Prévia, pacote e assinatura preservados. Esta atualização ajusta a interface; não implanta a correção de séries do painel.

Retomada das verificações: primeira execução de celular terminou com 16/19 testes aprovados; três falharam por ausência de foco da janela durante simulação de teclas. Login, rolagem, temporadas e reprodução passaram. Reexecução dos jobs falhos solicitada sem mudar o APK; resultado em acompanhamento. Referências exatas em STARLINK_REFERENCIA_CODEX.md.
