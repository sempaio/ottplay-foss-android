# Recuperação de detalhes e Quality Gate — 31/10/2026

## Escopo e causa reproduzida

Branch `fix/paravoce-replay-quality-20261031`, a partir de `6a86388566caa24abe11b05000228c0c77ca30ab`. CoreAPI e publicação 290 fora do escopo; nenhum deploy, sincronização, alteração de banco ou catálogo.

O catálogo Xtream já repetia uma vez um GET com timeout de transporte. Os caminhos de `get_vod_info`, `get_series_info`, `get_short_epg` e autenticação isolada não usavam essa recuperação. Um timeout transitório interrompia a abertura antes do player, embora a resposta seguinte fosse válida.

Reprodução anterior à correção: 6 casos direcionados, 4 falhas. Após a correção e inclusão do caso de resolução para o player: 7 casos aprovados; core completo 82 casos aprovados. São testes sintéticos com respostas controladas, não comprovação da causa de cada falha de provedor real.

## Correção mínima

`native/core/src/main/kotlin/play/ott/nativeapp/core/XtreamProvider.kt`: recuperação única compartilhada para metadados Xtream, com 500 ms de espera cancelável e reordenação DNS já existente. Não há IP fixo, desativação de TLS, nova URL, reclassificação ou repetição de transferências de vídeo. Máximo de duas tentativas; falhas persistentes continuam sendo comunicadas. A espera de rede em indisponibilidade pode crescer por uma tentativa adicional; cancelar a operação interrompe o fluxo.

`native/core/src/test/kotlin/play/ott/nativeapp/core/XtreamDetailRecoveryTest.kt`: filme/detalhe, resolução para reprodução, série/episódios, EPG/autenticação, limite de tentativas, erros HTTP/TLS e cancelamento. IDs, URL e cabeçalhos sintéticos conferidos. Recusas 401/403/429/500 e falhas TLS não são repetidas nem contornadas.

## Quality Gate

A execução 41216302317 confirmou Lint e Typecheck aprovados. A revisão de dependências falhou explicitamente porque o dependency graph estava desligado. Habilitado via endpoint oficial `PUT /repos/{owner}/{repo}/vulnerability-alerts` (204), que ativa o grafo e alertas de dependência; consulta de alertas retornou 204 e comparação de dependências voltou a retornar JSON válido. Não foram ativadas atualizações automáticas, alteradas versões de dependências, removidos jobs ou afrouxadas regras.

Referência: https://docs.github.com/en/rest/repos/repos#enable-vulnerability-alerts . Uma nova execução real do workflow deve confirmar o desbloqueio; configuração habilitada não equivale a Gate aprovado. Os demais jobs continuam obrigatórios.

## Validação em andamento

Compilação, lint Android, testes app e CI da nova branch: registrar conclusão e links antes da entrega. Reprodução real da nova compilação: pendente. Ocorrência anterior de Patagonia e buffering de Chaves não são declarados resolvidos apenas pelos testes sintéticos.

## Retorno

Manter o APK/tag anterior `starlink-032-paravoce-96fb0810` e aplicativo oficial. Não executar downgrade do banco local nem desinstalação para contornar assinatura diferente. Esta correção não exige rollback do servidor. Nenhuma chave, conta do emulador, URL autenticada ou log privado deve ser incluído no commit ou nos assets.
