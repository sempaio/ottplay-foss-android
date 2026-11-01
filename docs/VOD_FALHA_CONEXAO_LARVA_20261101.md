# STARLINK 0.3.2 — falha de reprodução de filme (01/11/2026)

## Evidência manual do proprietário

No aplicativo **STARLINK Para Você Teste**, o título **“Larva: Ilhados - O Filme [L]”** abriu o player e apresentou o diálogo *“Tente novamente / Não foi possível ligar à transmissão. Verifique a rede e tente novamente”*. Ao fundo apareceu o código Media3 **`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`** e posição **`00:00 / 00:00`**. Não houve primeiro quadro ou avanço do relógio na evidência. **Título registrado sem URL, conta, username, password, token, IP ou stream ID.**

Outros filmes já reproduziram em testes anteriores; canais e séries também foram relatados como funcionais. Portanto, a falha é **não resolvida nesse filme**, e não há evidência de indisponibilidade universal. Uma conexão falha pode ser de DNS, rota, porta, servidor de origem, redirecionamento ou protocolo; **o screenshot não identifica qual**.

## Por que o PR #21 não basta para afirmar reparo

O PR #21 elimina a segunda consulta desnecessária `get_vod_info` quando os detalhes Xtream do filme já foram carregados. Isso reduz chamadas e a chance de erro no **metadado**, mas **não repara** endereço de mídia indisponível, CDN, TLS, porta, DNS ou conexão de streaming. Um teste sintético aprovado não demonstra que o vídeo real esteja disponível.

## Alteração isolada de diagnóstico nesta branch

`PlaybackErrors.diagnosticCode` identifica exclusivamente erros de rede Media3 com rótulos seguros, sem imprimir `Throwable.message`, URL, cabeçalhos, credenciais ou endereço de servidor:

| Código de suporte | Interpretação (não conclusão de culpa) |
|---|---|
| `NET-DNS` | falha ao resolver nome |
| `NET-ROUTE` | rota inacessível |
| `NET-CONNECT` | falha ao estabelecer conexão |
| `NET-TIMEOUT` | tempo de conexão/leitura esgotado |
| `NET-TLS` | falha TLS/SSL numa exceção de conexão |
| `NET-CONNECTION` | causa de conexão não especificada |

O rótulo aparece entre colchetes após a mensagem já existente. O erro principal, a política de rede, retries, o player e os fluxos de canais/séries **não são alterados**. Erros HTTP 401/403/404/410 continuam usando as mensagens próprias existentes; **não contornar autenticação nem certificados**.

## Critérios reais de liberação

1. Compilar e testar o código atualizado, especialmente os testes de classificação e não divulgação de segredo, antes de qualquer merge.
2. Após instalar **somente um APK de teste compatível com assinatura/pacote** (sem apagar dados do app funcionando), tentar esse filme com a mesma conta/rede. Guardar apenas o erro Media3, o rótulo `NET-...` e se houve primeiro quadro. Repetir em outra rede quando possível, sem divulgar URLs privadas.
3. Comparar, no **mesmo momento e com acesso autorizado**, o mesmo filme no outro player que já conseguia abrir parte do catálogo; se falhar nos dois, investigar fonte/servidor; se só falhar no STARLINK, investigar requisição real e redirecionamentos (sem vazar senha).
4. Se o novo código continuar mostrando `NET-CONNECTION` sem causa, obter diagnóstico de rede sanitizado com ferramentas do operador; não presumir que o problema tenha sido corrigido.
5. Validar outros dois filmes, um canal, um episódio, avanço, pause/retomada e comportamento em segundo plano.

**Status:** bloqueador de homologação da reprodução VOD real; investigação isolada, sem alterar painel M81, publicação ou clientes. Não promover esta branch à main nem anunciar APK oficial até comprovar streaming real e assinatura legítima.
