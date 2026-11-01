# STARLINK 0.3.2 — correção isolada de consulta VOD duplicada (31/10/2026)

## Evidência do usuário
Na variante Para Você testada no aparelho, episódios/canais reproduzem normalmente; filmes, incluindo `Patagonia [L]`, ficam em tela preta com spinner e posição `00:00/00:00` mesmo após atualização da lista. As capas dos filmes carregam. A causa exata ainda não foi confirmada: tela `00:00` pode decorrer de resposta HTTP de mídia, origem, decoder, timeout ou URL incorreta, além de problemas no fluxo de metadados. **A correção abaixo não é declarada solução comprovada para o buffering real.**

## Causa técnica verificável de uma segunda consulta desnecessária

Antes: `OpenMovie` carregava os detalhes via `get_vod_info` (`movieEntry` já com descrição, imagem e contêiner resolvido). `MovieDialog` disparava `Play(movieEntry)` e `CloseMovie`. Então `ProviderRepository.resolve`, no caminho `MediaKind.MOVIE` + Xtream, **repetia** `get_vod_info` e potencialmente mudava de novo a URL antes de abrir o player. Esse GET duplicado adiciona falha e latência potenciais somente nos filmes.

Agora: `PlayerViewModel.play` identifica a mesma ficha de filme já carregada, captura esse estado antes de `CloseMovie` e passa `movieDetailsLoaded=true` à resolução. `ProviderRepository.resolve` **não repete o GET quando recebeu a ficha já verificada**, mas mantém validação da fonte, URL e headers. Caminhos de filme aberto diretamente continuam consultando `get_vod_info` para obter contêiner correto. Nenhuma exceção de HTTP 401/403/TLS é contornada; não há alteração do backend, links, catálogo, player, streaming de live/episódios ou políticas de segurança.

## Testes e bloqueios

- Casos sintéticos adicionados ao `StarlinkXtreamTest`: fluxo após detalhe não faz segunda chamada e preserva URL; fluxo direto continua requisitando e aplicando extensão do provedor.
- Workflows existentes de compilação Android integrada e instrumentação Android 35 foram habilitados **somente nesta branch de testes** (sem reduzir asserções).
- Executar testes CI, XML da instrumentação e Quality Gate na revisão final. Sem sucesso, não gerar nova candidata de instalação.
- Para homologar a correção de reprodução, testar o novo APK físico contra `Patagonia [L]`, pelo menos mais dois filmes, um canal e um episódio; confirmar primeiro frame e áudio, posição, seek, pausa/retomada e saída. Observar erro sanitizado e MIME/URL sem credenciais se o travamento persistir. **Não apresentar como resolvido sem esse teste físico.**
- Preservar APK de referência aprovado com SHA-256 `f13bed2be48669908eca9f1648a79f214b6337da37d92047bd9d26b58195ea16`.
- PR é empilhado sobre `release/starlink-032-official-clean-install-20261031` (PR #20), sem merge ou alteração da `main`; assinatura oficial continua exigindo segredo privado estável. Este teste não autoriza release oficial.
