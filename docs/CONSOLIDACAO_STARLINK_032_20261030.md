> **Atualização documental de 30/10/2026:** consultar [ESTADO_GITHUB_STARLINK_032_20261030.md](ESTADO_GITHUB_STARLINK_032_20261030.md) para o snapshot do commit `ca1e59e4`, CI, limitações e pendências. Este documento mantém as evidências e decisões do momento em que foi escrito.

> Atualização documental de31/10/2026: [estado consolidado local/GitHub](ESTADO_ATUAL_STARLINK_20261031.md). CI nativa e quatro casos remotos API35 aprovados; execução local API37 incompleta; Quality Gate ainda bloqueado. Trabalho local pausado. Evidências históricas abaixo preservadas.

# STARLINK 0.3.2 — consolidação local

Branch: starlink/032-consolidacao-local-20261030. Sem merge ou modificação M81.
Status deste commit: homologação em andamento; resultados finais serão anexados.

## Bases verificadas

A candidata descende da base oficial celular-catalogo-032 (6fbc0b62), Para Você (c80f93df) e PR9 (0f476cd4). Os SHAs completos estão em evidencias/BRANCHES_CONSOLIDACAO_20261030.json.
As branches artwork-diagnostics e origens-capas-layout não são ancestrais: contêm painéis optativos de diagnóstico de imagem, não exigidos na distribuição oficial 0.3.2. Foram comparadas sem copiar suas árvores inteiras. O cache experimental de episódios daquela branch não tem limite de memória; não foi importado para mascarar problemas de origem.

## Alterações

- Recuperação Xtream: um retry de GET após timeout, priorizando outro endereço retornado pelo mesmo DNS. Não fixa IP, não substitui domínio/URL, não desativa TLS/certificados. HTTP recusado, detalhes e reprodução não são repetidos. Falha persistente pode consumir dois timeouts mais 500 ms; não promete sanar indisponibilidade completa.
- Diagnóstico Android opt-in de health confirmou uma rota pública expirando enquanto outra respondeu com TLS1.2 e TLS1.3. São resultados reais de rede, não testes de autenticação.
- Histórico criptografado separado para Perfil 2, com atualização aditiva do SQLite LOCAL do aplicativo, versão 2 para 3. Histórico original mantido; banco operacional M81 intocado. Testes de atualização e remoção de fontes verificam preservação/isolamento. Não fazer downgrade desse banco local usando APK antigo sem cópia local compatível; aplicativo oficial anterior tem outro pacote e não é modificado.
- Cartões utilizam uma imagem principal e placeholder neutro; placeholder sai quando imagem carrega, inclusive logo transparente. A Home não usa a marca STARLINK como capa ausente.
- Correção conservadora de NFL Channel LIVE em grupo Futebol para Esportes; categorias confiáveis de outros conteúdos, tipos, IDs, URLs, temporadas e episódios permanecem intactos.
- Regex de normalização das recomendações compiladas uma vez, mantendo processamento fora da thread principal, debounce de busca e top-N limitado.
- Testes legados com interesse já configurado usam perfil onboardingDone=true, preservando seu objetivo original. Novo teste cobre o assistente, múltipla escolha, subtemas, Voltar, Pular e consentimento desligado. Expectativa de Back atualizada de áudio persistente para StopPlayback conforme requisito; não desativa testes.

## Resultados reais já observados antes da consolidação final

Catálogo: 1997 canais, 26583 filmes, 5152 séries na conta de teste.
Chaves: temporadas e episódios reais carregados, S01E01 com quadro de vídeo e MediaSession PLAYING, posição 19264 ms.
Patagonia: MediaSession PLAYING, posição 7033 ms. Áudio audível não validado (emulador sem áudio).
The Highest Stakes apresentou mensagem genérica em uma tentativa; causa específica ainda não confirmada. Não foi removida proteção editorial ou alterado conteúdo para fazê-lo reproduzir.
Esses resultados não são uma aprovação de todos os títulos nem dos builds posteriores sem rechecagem.

## EPG e servidor

Código usa cache XMLTV e get_short_epg quando não há programação local correspondente. Consulta anterior ao M81 encontrou 3785 programas antigos, nenhum atual/futuro, último término 18/10. Isso não permite inventar grade nem atribuir vazio ao cliente. Confirmar dados atualizados na homologação final; nenhuma atualização de servidor autorizada nesta tarefa.

M81 preservado: versão290, ID292, 170016 registros, SHA d0b0d616a91576eda948f0a6bb2712e73c5a2550d6bd385653d5deb2c84f5fe1. Imagem 4594259e. Sem reinício, escrita operacional, sincronização, republicação, bot ou clientes modificados.

## Reprodução e comandos

Os 75 testes core e 145 testes app passaram no ciclo local anterior; lint sem erro. O ciclo final e os testes instrumentados ainda estão em andamento neste registro.

Pacote de teste: br.starlink.iptv.paravoce, assinatura Android Debug; não substitui br.starlink.iptv.layout. APK de dist continua antigo e não deve ser distribuído como nova candidata.

Com JDK17 e SDK apropriados, usar wrapper em native:

```text
./gradlew --no-daemon --max-workers=1 :core:test :app:testLayoutUnitTest :app:lintLayout :app:assembleParaVoceTest
./gradlew --no-daemon --max-workers=1 -I consolidation-qa.init.gradle :app:assembleParaVoceQa :app:assembleParaVoceQaAndroidTest
```

QA usa br.starlink.iptv.paravoce.qa, sem dados da conta real. O teste público M81NetworkDiagnosticTest só faz rede com argumento explícito m81NetworkProbe=true; conclusão do teste de diagnóstico não significa sucesso de todas as rotas.

A CI nativa do commit anterior 7d60cbe4 passou (run41102179241). Quality Gate global do PR9 tinha erros de formatação legados e dependency-review indisponível por configuração do repositório; isso não foi desativado. Validação do commit final deve ser conferida separadamente.
