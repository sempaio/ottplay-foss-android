# AGENTS — coordenacao com o projeto M81

Este repositorio `sempaio/ottplay-foss-android` tem ramo oficial `main`.
Papel: variantes STARLINK Android Para Voce, codigo majoritariamente em branches candidatas, nao na main.

## Regras obrigatorias para ChatGPT, Codex, Work e demais agentes

- Quando a tarefa envolver STARLINK/M81, **o projeto principal e o painel M81** (`sempaio/painel-m81-iptv-cloud`). Este repositorio e um componente com codigo e releases proprios. Nao criar painel novo nem projetos paralelos.
- Ler as regras canônicas antes de analisar ou editar: [AGENTS M81](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/AGENTS.md), [protocolo multiagente](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/docs/agentes/AI_COLLABORATION.md), [melhorias](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/docs/MELHORIAS_PAINEL.md), [tarefas](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/TASKS.md), [decisoes](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/DECISIONS.md), [controle de versoes](https://github.com/sempaio/painel-m81-iptv-cloud/blob/main/docs/operacao/CONTROLE_VERSOES_M81.md). Ler tambem README e contratos locais deste repositorio.
- Toda tarefa M81 deve ter UM ID-Pai no quadro unico M81, o mesmo ID em branch/PR/handoff deste repositorio e criterios de aceite aprovados. Registrar aqui PR/commit/SHA, arquivos, dependencias com outros repositorios e testes; atualizar a tarefa M81 em vez de criar backlog paralelo.
- Antes de propor ou editar: listar branch default real, HEAD SHA atual, arquivos envolvidos, diffs, PRs empilhados/concorrentes e variantes ja testadas. Comparar proposta nova com codigo e proposta anterior. Proposta conflitante nao prevalece pela data; registrar alternativas no MESMO ID e marcar AGUARDANDO DECISAO ate aprovacao do operador.
- Nao usar automaticamente branch mais nova nem tratar `main`/`master` como versao do app instalada; registrar package/app ID, versionCode/build, assinatura/certificado, SHA-256 do artefato, aparelho e teste fisico. Se nao houver evidencia, informar DESCONHECIDO.
- Um executor altera cada escopo/arquivo por vez em branch isolada; outros assistentes investigam ou revisam. **A reserva e cooperativa, nao bloqueio tecnico.** Nao sobrepor alteracoes, fazer force push, reset destrutivo, apagar dados ou substituir APKs/firmwares funcionais. Work e Codex podem trocar papeis, sempre retomando o PR e o ID anteriores.
- Exigir testes aplicaveis, integracao M81 M3U/Xtream/EPG/autenticacao, seguranca, compatibilidade e rollback. Nao alegar teste real so por CI verde. Nenhum merge, deploy, migracao, publicacao, distribuicao ou instalacao em clientes sem autorizacao expressa.
- Entregar estado: ID, baseline SHA, o que mudou e onde, PR, testes reais e falhas, versoes preservadas, divergencias, bloqueios e proximo passo exato. Atualizar descricao do PR e registro M81; nao criar arquivo STATUS/PLAN/AGENTS alternativo.
- Tarefas upstream que nao envolvam M81 respeitam os contratos e politicas do proprio repositorio; nao assumir que sao release M81.

## Cautelas locais

- A main deste repositorio tem apenas README na auditoria de 2026-11-02; o Android STARLINK 0.3.2 esta em varios PRs/branches. Nunca tratar `PR #22` como APK oficialmente funcional sem testes.
- Referencia historica de APK aprovado em teste fisico pelo operador no PR #19: `STARLINK-032-PARA-VOCE-INTEGRADO.apk`, SHA-256 `f13bed2be48669908eca9f1648a79f214b6337da37d92047bd9d26b58195ea16`. **Preservar**; confirmar se e o instalado agora.
- PR #23 relatou falha real de filme `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` com `00:00/00:00`; causa desconhecida. Diferentes builds 0.3.2 nao sao intercambiaveis. Nao substituir APK estavel nem gerar assinatura nova automaticamente.

Este documento integra a governanca do M81; nao representa implementacao, homologacao ou instalacao.
