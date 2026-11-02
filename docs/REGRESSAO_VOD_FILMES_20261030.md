# STARLINK IPTV 0.3.2 — regressão relatada na reprodução de filmes (30/10/2026)

> Atualização documental de31/10/2026: [estado consolidado local/GitHub](ESTADO_ATUAL_STARLINK_20261031.md). CI nativa e quatro casos remotos API35 aprovados; execução local API37 incompleta; Quality Gate ainda bloqueado. Trabalho local pausado. Evidências históricas abaixo preservadas.

> **Atualização do proprietário — 30/10/2026:** depois de tocar um filme com sucesso e **atualizar a lista/catálogo no aplicativo**, o proprietário informou que os demais filmes **aparentemente voltaram a reproduzir** e que as outras funções continuam funcionando. **Situação: falha não reproduzida após atualização da lista, sob observação; nenhuma correção de código aplicada.** O problema não deve permanecer descrito como regressão generalizada confirmada nem ser atribuído ao player. Não é evidência suficiente para afirmar que 100% dos títulos e formatos foram homologados. Se reaparecer, comparar o estado do catálogo antes e depois de atualizar, registrar só o erro sanitizado e evitar mudanças no serviço.

## Relato do proprietário

Em 30/10/2026, durante testes do Android STARLINK com `Para Você`, foi informado: **"Os filmes agora eles não estão reproduzindo. Mas todo o outro resto tá ok."** Captura enviada na conversa. **Não há ainda comprovação técnica suficiente para atribuir a falha ao painel, ao stream, ao contêiner ou ao player**. Não assumir código HTTP ou causa específica a partir da descrição.

## Status e gravidade

**Atenção: ocorrência aparentemente normalizada após atualização da lista.** Não há bloqueio confirmado e persistente por reprodução VOD no relato mais recente; a distribuição oficial ainda exige os testes de homologação gerais e uma amostra de reprodução de filmes no aparelho com conta autorizada. A CI [41126645448](https://github.com/sempaio/ottplay-foss-android/actions/runs/41126645448) aprovou testes de unidade, lint e build, **não a reprodução VOD com fonte real**. O app antigo 0.3.2-layout deve permanecer disponível para comparação; nenhum rollback ou limpeza de dados deve ser executado automaticamente.

## Caminho identificado no código-fonte do commit ca1e59e4

1. `native/app/src/main/java/play/ott/nativeapp/ui/MovieDialog.kt`: o botão assistir despacha `AppAction.Play(entry)`.
2. `native/app/src/main/java/play/ott/nativeapp/PlayerViewModel.kt`: `OpenMovie` busca detalhes com `repository.providers.loadMovieInfo`, e `play` solicita `repository.stream`.
3. `native/core/src/main/kotlin/play/ott/nativeapp/core/ProviderRepository.kt`: `loadMovieInfo` chama `xtream.vod`, enquanto `resolve` volta a chamar `xtream.vod` ao reproduzir uma entrada de filme Xtream.
4. `native/core/src/main/kotlin/play/ott/nativeapp/core/XtreamProvider.kt`: `vod` faz GET `get_vod_info` e pode ajustar a extensão do stream conforme `movie_data.container_extension`.
5. `PlaybackErrors.kt` distingue resposta HTTP, rede, contêiner e decoder, sem expor credenciais.

**Hipótese a verificar:** a consulta adicional `get_vod_info` pode falhar/atrasar e impedir o início da mídia; o comportamento de consulta duplicada também existia na base `0.3.2-layout`, portanto **não é prova de regressão nova**. Outra possibilidade é que o link de mídia retornado pelo fornecedor esteja inválido ou com extensão inadequada. Não reduzir o erro a um 403 sem evidência.

## Matriz de diagnóstico mínima sem expor contas

- Selecionar dois filmes de fontes/formats distintos (quando disponíveis) e verificar separadamente: abertura do detalhe, botão Assistir, tentativa de carregar player, erro exibido.
- Usar a **mesma conta e rede** em 0.3.2-layout e na candidata Para Você; não apagar dados, credenciais ou remover APK atual.
- Registrar apenas a etapa e classe do erro (detalhe, URL, HTTP do stream, formato/decoder, timeout), e se o problema se repete em outros aplicativos; nunca incluir URLs completas, nomes de usuário, senhas, cookies, headers, tokens ou arquivos pessoais.
- Usar um filme de fixture que reproduz no Media3 para isolar player de conteúdo remoto; manter canal e episódio como controles, pois o proprietário relata que os demais itens funcionam.
- Conferir se `get_vod_info` é exigido mais de uma vez e se o resultado de `movie_data.container_extension` produz o endpoint correto, mantendo parâmetros e codificação.
- Não alterar o painel M81 em produção. Caso seja backend, preparar PR separado e plano de reversão.

## Critérios para correção segura

- Filmes reais autorizados reproduzem; séries e canais não sofrem regressões.
- Erro real HTTP 401/403/404 não é escondido com mensagens de sucesso.
- Nenhuma transcodificação, mudança de URLs/identificadores ou bypass de segurança presumido.
- Testes automatizados cobrem falha na consulta de detalhes sem perda de acesso ao stream quando o URL do catálogo é válido, evitando consulta redundante, e formatos VOD existentes.
- Validar assinaturas/pacotes antes de recomendar atualização e manter a branch `main` e a instalação dos clientes inalteradas até homologação.

**Sem correção de código nesta nota**; aguarda evidência específica do VOD para não arriscar funcionalidades que continuam funcionando.
