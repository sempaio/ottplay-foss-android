# STARLINK IPTV Android 0.3.2 — estado verificado no GitHub (30/10/2026)

> **Snapshot documental, não aprovação de versão.** Registra o que está publicado no GitHub nesta data, distinguindo CI, testes locais relatados, testes no aparelho e observações históricas. Este documento não altera código do aplicativo ou serviços. Para estado de CI posterior à consulta, abrir os links dos workflows.

## 1. Versão e referências de verdade

- Repositório: https://github.com/sempaio/ottplay-foss-android
- **Candidata consolidada**: `starlink/032-consolidacao-local-20261030`, commit exato [`ca1e59e475bccf4867bba5fe18c817d133bf4384`](https://github.com/sempaio/ottplay-foss-android/commit/ca1e59e475bccf4867bba5fe18c817d133bf4384). Esta é a referência congelada para o presente snapshot; a branch pode avançar depois.
- Ancestralidade: base `starlink/celular-catalogo-032`, Para Você `starlink/para-voce-20261030`, correções `starlink/032-para-voce-player-fixes-20261030`, revisão `review/starlink-para-voce-20261030` e experimento `3b4b021`. Evidência em [BRANCHES_CONSOLIDACAO_20261030.json](evidencias/BRANCHES_CONSOLIDACAO_20261030.json) e histórico do Git.
- PRs [#8](https://github.com/sempaio/ottplay-foss-android/pull/8), [#9](https://github.com/sempaio/ottplay-foss-android/pull/9), [#10](https://github.com/sempaio/ottplay-foss-android/pull/10) observados **abertos/em rascunho** em 30/10; nenhum é, por si só, uma aprovação de distribuição.
- A `main` do repositório Android continha apenas `README.md` na consulta precedente; **não houve merge na main nesta continuação**. Não confundir a candidata com a branch oficial.
- A referência anterior `0.3.2-layout`, commit `f25fafdcc39ab91390c428dd21dbdb5dda8d90f5`, dispõe de APK distinto `dist/STARLINK-IPTV-layout.apk`; sua suíte de celular **19/19** passou no [run 40011895751](https://github.com/sempaio/ottplay-foss-android/actions/runs/40011895751). Isso NÃO valida o commit `ca1e59e4`.

## 2. O que está implementado ou relatado na candidata (sem afirmar homologação real)

As notas de [CONSOLIDACAO_STARLINK_032_20261030.md](CONSOLIDACAO_STARLINK_032_20261030.md), do PR #9 e do commit registram onboarding e interesses do **Para Você**, sugestões por perfil, histórico local separado, favoritos, busca e Guia TV, player em tela cheia com interrupção de reprodução ao sair, reprodução em segundo plano optativa, proteção de logos/capas, classificação visual conservadora de conteúdos, tratamento do catálogo e recuperação limitada de timeouts Xtream.

- A atualização do SQLite local de versão 2 para 3, o histórico por perfil e a recuperação de GET após timeout são **mudanças técnicas** que precisam de teste no dispositivo e nas migrações, não promessas de correção de todos os problemas.
- O reagrupamento conservador preserva tipo de mídia, IDs, URLs e metadados de origem; não é uma recategorização completa do catálogo do painel.
- Pacotes de teste `br.starlink.iptv.paravoce` e `br.starlink.iptv.paravoce.qa` não substituem automaticamente `br.starlink.iptv.layout`. APK de depuração não é uma atualização oficial assinada.

## 3. Evidências de teste: distinguir estados

| Evidência | Resultado efetivamente documentado | Limite |
|---|---|---|
| QA local anterior à consolidação | 75 testes de core e 145 de app aprovados; lint sem erro, conforme relatório de consolidação; captura do Codex informa build QA aprovado | Relato local, não repetido por esta auditoria. Mudanças do commit final exigem validação própria. |
| [CI de revisão — run 41102179241](https://github.com/sempaio/ottplay-foss-android/actions/runs/41102179241), commit `7d60cbe4` | **success** | Refere-se ao commit anterior, não à candidata `ca1e59e4`. |
| [Quality Gate da revisão — run 41102314086](https://github.com/sempaio/ottplay-foss-android/actions/runs/41102314086) | **failure** | Não considerar a revisão integralmente aprovada. |
| [CI integrada da consolidação — run 41126645448](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645448), commit `ca1e59e4` | **in_progress na consulta de 30/10/2026**; etapa de testes/build em execução; validação e upload do APK ainda pendentes | Resultado pode mudar depois. Verificar a própria página antes de qualquer anúncio. |
| [Workflow Android APK — run 41126645472](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645472) | **skipped** | Não houve aprovação dessa esteira para o commit. |
| Testes instrumentados adicionais de personalização, armazenamento e playback | **planejados pelo Codex na captura fornecida**, mas interrompidos por pedido do proprietário antes de prova de conclusão | Não registrar como executados/aprovados. |
| Autenticação e reprodução reais na nova candidata no telefone do proprietário | **não comprovadas nesta continuação** | Necessário testar no aparelho correto e com a própria conta, sem compartilhar credenciais. |

Nenhum workflow foi disparado deliberadamente por esta atualização documental. A CI integrada tem filtro de caminhos `native/**` e workflow; commits apenas de `docs/**` não satisfazem esse filtro na branch de documentação.

## 4. Limitações, falhas e pendências que não podem ser omitidas

1. **Primeira abertura/ANR**: revisão anterior relatou ANR envolvendo `EpgRefreshWorker`; tentativas posteriores abriram, mas a causa não foi determinada de forma conclusiva. Não chamar de resolvido.
2. **Conectividade Xtream**: experimento do emulador observou timeout/variação de rotas públicas; retry é limitado e não comprova conectividade sustentada nem playback. Veja [TIMEOUT_EMULADOR_20261030.md](TIMEOUT_EMULADOR_20261030.md).
3. **EPG no M81**: leitura operacional anterior documentou cache expirado (última programação em 18/10), sem programas atuais na ocasião. **Não foi feita nova consulta ao M81 aqui**; não afirmar situação presente do guia nem atribuir culpa ao cliente.
4. **Comparação justa de versões**: a imagem antiga do emulador inicialmente era 0.2.2, inadequada como baseline da 0.3.2. Comparar a candidata e `0.3.2-layout` no mesmo dispositivo e rede.
5. **Entrega**: conferir artefato, SHA-256, package, assinatura e teste de instalação após CI concluída. Não divulgar o APK `dist/` anterior como se fosse a versão nova.
6. **Qualidade**: falhas do Quality Gate original não foram anuladas apenas pelo sucesso parcial de outro workflow.
7. **Dados**: manter dados locais ao atualizar; não fazer downgrade da base local versionada sem estratégia testada de compatibilidade.

## 5. Painel M81 e demais serviços — fronteira operacional

O relatório anterior [HOMOLOGACAO_PARA_VOCE_M81_20261030.md](HOMOLOGACAO_PARA_VOCE_M81_20261030.md) registra publicação 290 (ID 292), 170.016 registros, checkpoint e imagem da época. **Estes dados são um snapshot daquele relatório, não uma leitura operacional nova.**

Nesta continuação **não** foram feitos deploy, restart, migração SQL, republicação de catálogo, mudança de contas, alteração de URLs, alteração no bot, edição de sincronizador ou mudança nos repositórios do painel e Samsung/LG. O trabalho esteve restrito a **leitura no GitHub e atualização dos documentos**.

## 6. Próximas verificações, sem execução automática

1. Conferir o resultado final do run `41126645448`, jobs, artefatos e SHA do APK, sem converter `in_progress` em aprovação presumida.
2. Revisar separadamente o Quality Gate, incluindo falhas não relacionadas à compilação específica.
3. Testar no aparelho real: login, contas/fonte, catálogo, categorias, onboarding/consentimento, dois perfis e histórico isolado, favoritos, busca, Guia TV, filmes, séries/episódios, canais, fullscreen, Voltar, encerramento da sessão e modo segundo plano.
4. Investigar ANR e timeouts com evidências sanitizadas; tratar EPG do painel em atividade independente.
5. Somente após homologação, planejar APK oficial com pacote e assinatura compatíveis, backup/rollback e integração via PR à branch padrão. Não fazer merge nem instalação automática.

## 7. Escopo e fonte deste registro

Documento gerado após **consulta ao GitHub**, usando PRs, commit e workflows indicados acima, somado às evidências e aos limites já escritos no próprio repositório e à captura do usuário. **Não houve acesso ao diretório local do Codex**, aos dados de autenticação ou ao aparelho. A ausência de evidência remota não autoriza afirmar que uma tarefa local tenha sido concluída.

Documentos históricos mantidos para rastreabilidade: `STARLINK_RELATORIO_FINAL.md`, `STARLINK_CATALOGO_032.md`, `STARLINK_REFERENCIA_CODEX.md`, `TIMEOUT_EMULADOR_20261030.md`, `HOMOLOGACAO_PARA_VOCE_M81_20261030.md` e `STARLINK-CONSOLIDACAO.md`. O status mais recente consultado deve ser interpretado por **commit/branch**, não apenas pelo nome da versão.
