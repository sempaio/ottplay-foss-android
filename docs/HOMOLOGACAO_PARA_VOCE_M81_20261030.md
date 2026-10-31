> **Atualização documental de 30/10/2026:** consultar [ESTADO_GITHUB_STARLINK_032_20261030.md](ESTADO_GITHUB_STARLINK_032_20261030.md) para o snapshot do commit `ca1e59e4`, CI, limitações e pendências. Este documento mantém as evidências e decisões do momento em que foi escrito.

> Retomada autorizada em31/10/2026: consulte a [entrega do APK de teste e os resultados finais](ENTREGA_STARLINK_PARA_VOCE_20261031.md). O checkpoint/estado abaixo é histórico. Não equivale a instalação oficial ou aprovação de todos os provedores.

> Atualização documental de31/10/2026: [estado consolidado local/GitHub](ESTADO_ATUAL_STARLINK_20261031.md). CI nativa e quatro casos remotos API35 aprovados; execução local API37 incompleta; Quality Gate ainda bloqueado. Trabalho local pausado. Evidências históricas abaixo preservadas.

# STARLINK Para Você + M81 — homologação local 30/10/2026

Candidata confirmada pelo proprietário: PR #9, branch
`starlink/032-para-voce-player-fixes-20261030`, commit
`0f476cd4be845d35a45afdb231c7084358b39712`. Trabalho isolado em
`review/starlink-para-voce-20261030`, E:\IPTV\STARLINK-para-voce-review-20261030.
Não houve merge na main, implantação no M81 ou substituição do app dos clientes.

## M81 preservado e checagens reais

CoreAPI mantém 144dd6a, imagem 4594259e, checkpoint
`m81-admin-rapido-20261030`. Publicação versão 290, ID interno 292,
170016 registros; SHA
`d0b0d616a91576eda948f0a6bb2712e73c5a2550d6bd385653d5deb2c84f5fe1`.
291 fontes/5 provedores e serviços protegidos conferidos, sem sincronização ou
reativação. HTTP local/public: health/status 200; player_api.php sem parâmetros
422 esperado. Isso NÃO comprova autenticação Xtream nem reprodução.
Nenhuma mudança de código/configuração, restart ou escrita SQL foi feita no M81.

EPG: cache existente com 179 canais/3785 programas; estado de atualização erro.
Leitura integral do XML em 30/10 confirmou zero programas atuais/futuros;
o último término é 18/10/2026 09:00 UTC. O código corrigido do EPG permanece
intacto, mas a grade atual NÃO está validada/atualizada. Não atualizar guia,
fontes ou publicação para mascarar esse achado. Causa da atualização falhar
precisa de investigação própria; não se conclui que seja falha do aplicativo.

## Melhorias herdadas do PR #9

Perfis/interesses e recomendações locais Para Você; favoritos, busca rápida,
Guia TV visível; indicador EPG independente do carregamento do catálogo;
parada ao sair do player, segundo plano optativo, tela cheia horizontal no
celular. Categorias inteligentes são apenas apresentação e preservam grupos
significativos, IDs, tipos, URLs e publicação. Sem nova classificação por 24H.

A base é evolução do 0.3.2 oficial f25fafd, não outro painel ou produto.
O dist/STARLINK-IPTV-layout.apk continua sendo o APK antigo, SHA
856c576fb36a002c69082907077355455017ab6ff541cce361a51afbfb147bd4.
Não distribuir esse arquivo como se contivesse as novidades do PR.
A correção local não commitada de retry de timeout, em outro worktree,
foi preservada e não foi incorporada silenciosamente.

## Correções locais comprovadas

1. Workflow copiava APK com nome INTEGRADO, mas validava outro nome TESTE,
   causando FileNotFoundException depois de compilar. Corrigidos apenas os
   caminhos de apksigner, aapt e SHA256 para o arquivo realmente produzido.
2. Teste de migração EPG falhava no Windows em edições/exclusões concorrentes.
   Comparação com NativeRepository da base f25fafd também reproduziu a falha.
   ShadowLog comprovou falhas de rename do AtomicFile sobre arquivo existente.
   Shadow de teste fornece rename POSIX atômico via NIO; mantém AtomicFile,
   AES, SQLite, repositório e todas as 18 comparações originais. Falhas de I/O
   propagam; não há skip nem relaxamento dos contratos. Não muda código de
   armazenamento, credenciais ou EPG do aplicativo instalado.

## Compilação e testes locais

JDK 17.0.20, SDK 37.0/Build Tools 36.0.0, wrapper do projeto, um worker,
parallel=false, heap Gradle 1 GiB, compilação sequencial sem emulador concorrente.

Comando (ANDROID_HOME/JAVA_HOME/GRADLE_USER_HOME configurados localmente):

```text
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testLayoutUnitTest :app:lintLayout :app:assembleParaVoceTest
```

Resultado final: BUILD SUCCESSFUL; 69 testes core + 140 app, zero falhas,
zero erros, zero ignorados. Lint: 0 erros, 20 warnings, 1 hint. Compilação
anterior falhou no teste de AtomicFile; não foi apresentada como aprovada.
Todos esses testes usam fixtures, não credenciais/catálogo real do M81.
[Resumo verificável](evidencias/HOMOLOGACAO_PARA_VOCE_20261030.json).

CI global original do PR #9 ainda contém 20 erros de formatação e dependency
review indisponível para o repositório (configuração do Dependency graph).
Não desativamos gates nem mudamos privacidade/permissões para aprovar CI.
A correção do workflow precisa da execução na branch de revisão; sucesso local
não equivale a sucesso da CI ou aprovação de distribuição.

## APK novo para teste, sem substituir clientes

E:\IPTV\ENTREGA-PARA-VOCE-20261030\STARLINK-032-PARA-VOCE-INTEGRADO.apk
SHA256 ca629612ea1225a5b04d1c371f067acb4945ea75d57b0940b24d689c5fcc5300.
Pacote br.starlink.iptv.paravoce, versionCode 10,
0.3.2-para-voce-test, Android mínimo 26, target 36.
Assinatura debug conferida, certificado SHA256
f21afa39be5634c30200438c33b5ce2ae5bae8e170612e66e26001a53ef601cf.
O APK oficial layout possui certificado diferente (41fc681a...). Não tentar
sobrescrevê-lo com este APK; variante de teste deliberadamente separada.
A assinatura oficial precisa ser localizada no cofre autorizado antes de
preparar uma atualização definitiva do pacote layout; nenhum segredo foi lido
ou enviado. O bot e os downloads operacionais permanecem inalterados.

## Emulador e limites reais

Starlink_Local_37, emulator-5580 iniciado em modo read-only/no-snapshot-save.
A imagem disponível contém br.starlink.iptv 0.2.2, NÃO o 0.3.2-layout em uso
pelo proprietário. Não foi usada como prova do comportamento do app atual.
Nova candidata instalou com sucesso ao lado dela, sem desinstalar/substituir.
Primeira abertura apresentou ANR de onStartJob/EpgRefreshWorker. Após encerrar
os processos de teste e reabrir, chegou a Welcome. Inspeção JDWP posterior
mostrou main ociosa em MessageQueue, sem deadlock capturado naquele momento;
não prova a causa do primeiro ANR. Três novas aberturas retornaram Status ok;
as duas primeiras mostraram login na primeira coleta e a terceira somente
na coleta posterior. Esse evento não foi apagado nem chamado de resolvido.
[Evidência de abertura](evidencias/PARA_VOCE_STARTUP_20261030.json).

Login do proprietário no app de teste está pendente. Não extraímos senhas de
clientes nem reaproveitamos credenciais de outro pacote. Catálogo real,
temporadas, episódios e mídia ao vivo/VOD ainda NÃO foram homologados no
aplicativo nesta tarefa. Não anunciar atualização dos clientes como concluída.

## Próximos testes necessários antes da troca definitiva

Entrar diretamente no app de teste, sem compartilhar senha; atualizar catálogo;
conferir canais e categorias, filme, série com temporadas/episódios, favoritos,
Guia TV, início/fim de playback e três entradas/saídas. Separar guia expirado,
HTTP da origem e erro do cliente. Reproduzir/explicar ANR em ambiente estabilizado
e comparar com 0.3.2 correto nas mesmas condições. Só então preparar pacote
oficial com assinatura compatível e planejar a entrega pelo mecanismo existente.

Rollback de teste: abrir o aplicativo original. Nenhum retorno de servidor,
banco ou publicação é necessário. Sessão de emulador é descartável; instalação
na imagem original não foi alterada. O checkpoint do M81 e seus backups continuam
sendo a referência operacional. Não fazer merge/deploy automático deste relatório.
