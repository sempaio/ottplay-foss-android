# STARLINK IPTV 0.3.2 — evidências de Quality Gate e preservação (31/10/2026)

## Escopo e referência funcional

Este documento registra somente auditoria e preparação de release Android. Não autoriza merge, publicação oficial, mudança no M81 ou instalação em clientes.

**APK de referência aprovado pelo proprietário em teste físico:** `STARLINK-032-PARA-VOCE-INTEGRADO.apk`, SHA-256 completo `f13bed2be48669908eca9f1648a79f214b6337da37d92047bd9d26b58195ea16`. O valor bate com `SHA256SUMS` recebido junto ao APK e com o artefato CI [13011152511](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645448/artifacts/13011152511). O usuário relatou capas rápidas, logotipos sem sobreposição e reprodução fluida nesse APK. Esse relato não homologa outros dispositivos/streams.

Não trocar automaticamente por [pre-release 0.3.2 de 31/10](https://github.com/sempaio/ottplay-foss-android/releases/tag/starlink-032-paravoce-96fb0810), que tem SHA-256 **diferente** (`f0521a067c7d67b98787b69c505e273686d6eebe7434888835f960ca7c86e8a9`). Tampouco supor compatibilidade entre assinaturas debug de runners ou entre os pacotes `br.starlink.iptv.paravoce` (QA) e `br.starlink.iptv.layout` (candidata oficial).

## PRs e integração

- [PR #13](https://github.com/sempaio/ottplay-foss-android/pull/13): consolidação da `main`, ainda draft; **sem merge**. Não misturar a importação upstream original ou PRs históricos empilhados inadvertidamente.
- [PR #17](https://github.com/sempaio/ottplay-foss-android/pull/17): entrega de teste, ainda draft; contém a compilação da pre-release e testes anteriores Android 35 (28/28 no commit `96fb0810`).
- [PR #18](https://github.com/sempaio/ottplay-foss-android/pull/18): correção isolada de timeout transitório de GETs Xtream para detalhes, sem retentar mídia/HTTP recusado/TLS e sem alterar M81; ainda draft. Head auditado `e31835075082fb5a479c921fc0c1053708df3f3a`. O compare com `96fb0810` indica três commits à frente, sem commits atrás, com a mudança funcional principal em `native/core/src/main/kotlin/play/ott/nativeapp/core/XtreamProvider.kt` e os testes correspondentes.

## Quality Gate — resultado real

- [Quality Gate PR #17 / run 41216302317](https://github.com/sempaio/ottplay-foss-android/actions/runs/41216302317): **failure**, job `check-3 / dependency-review`. O log reportou: `Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled`. Outros jobs relevantes, inclusive lint, concluíram com sucesso.
- [Quality Gate PR #18 / run 41217303017](https://github.com/sempaio/ottplay-foss-android/actions/runs/41217303017): **success** na branch do PR #18; 27 jobs concluíram com sucesso e três foram pulados conforme regras do workflow; nenhum job falhou. O dependency graph foi habilitado conforme registro no PR #18. Isso não substitui o Gate no commit final da `main` nem a homologação de release.
- [CI Android integrada do PR #18 / run 41217270617](https://github.com/sempaio/ottplay-foss-android/actions/runs/41217270617): **success** (unitários, lint Android, build de teste, verificação de pacote e hash).
- [Instrumentação isolada do PR #18 / run 41217273455](https://github.com/sempaio/ottplay-foss-android/actions/runs/41217273455), tentativa 1: **failure**, com relatórios XML preservados no artefato `STARLINK-032-INSTRUMENTED-QA-REPORT` (ID 13049260333).

### Diagnóstico da instrumentação (tentativa 1)

Leitura direta de todos os oito relatórios XML no artefato: **28 testes executados, 12 falhas, zero erros e zero ignorados**, com 16 testes aprovados. Suítes:
- `for_you`: 3/3 falharam
- `stop_background`: 4/4 falharam
- `catalog_ui`: 5/5 falharam
- `customer_ui`: 3/3 passaram
- `storage`: 10/10 passaram
- `native_player`, `hls_player`, `drm_player`: 1/1 cada passou

As **12 falhas** partilham a mesma asserção de preparação da janela em `UiWindowReadiness.kt:67`: `Activity window did not become ready for a hardware key`. Os relatórios registram `windowFocus=false` e janela do **sistema Android** com `Quickstep isn't responding` (pacote `android`, launcher `com.android.launcher3`). Portanto o primeiro bloqueio identificado é um **ANR do launcher/emulador**, que impede que os testes interativos prossigam até o comportamento do aplicativo. Não atribuir esses 12 casos a defeitos demonstrados no player, no onboarding ou na arte; também **não declará-los aprovados**. Verificar reproduzibilidade e estabilidade do runner antes de concluir causa definitiva.

Foi solicitada uma **nova tentativa somente dos jobs falhados** do run 41217273455, sem alterar testes nem desativar controles. Consultar o resultado final do *run attempt 2*; no momento do registro, ainda não se deve afirmar que passou. Se o ANR reproduzir, isolar a causa do Quickstep/AVD (memória, renderização, parâmetros e carregamento do runner), preservar relatórios XML e testar uma mudança exclusivamente de infraestrutura em branch separada, sem remover asserções nem mascarar falhas.

## Requisitos antes do APK oficial

1. Confirmar **todas** as verificações da versão final combinada, incluindo Gate completo na branch alvo e testes instrumentados Android 35 com XML: zero falhas, erros e ignorados.
2. Manter regressões explícitas de capas, logotipos, player, tela cheia, saída/Voltar, background, favoritos, Guia TV, perfis e personalização.
3. Validar reprodução real em aparelho físico e pelo menos TV Box representativa, com resultados e limites declarados (o M81 fica intocado).
4. Confirmar o pacote destinado a clientes (atualmente `br.starlink.iptv.layout` na candidata oficial), `versionCode` incremental, assinatura **estável existente** e coincidência do fingerprint do certificado com o APK oficial instalado; verificar `apksigner verify`. Nunca criar uma chave nova para atualizar o pacote existente sem uma estratégia explícita de migração.
5. A assinatura do [PR #15](https://github.com/sempaio/ottplay-foss-android/pull/15) estava bloqueada pela ausência de signing secrets na execução [41134373700](https://github.com/sempaio/ottplay-foss-android/actions/runs/41134373700). Configurar de forma privada os segredos previstos e não publicar nenhum segredo, keystore, senha ou token.
6. Preparar atualização e **rollback seguros** para banco local SQLite (a variante de teste já migrou para versão 3), preservando dados do cliente e sem forçar downgrade. Não alterar o PostgreSQL do M81.
7. Somente após homologação e revisão explícita, integrar na `main` e publicar a release com hashes, certificado, notas, plano de retorno e provenance. Não substituir o APK em uso automaticamente.

## Limites da auditoria

Não há atestado de release oficial; não houve alteração do aplicativo, player ou servidor, nem merge/instalação. Este relatório não equivale a aprovação automática do PR #18 ou à finalização de todas as contas e provedores.
