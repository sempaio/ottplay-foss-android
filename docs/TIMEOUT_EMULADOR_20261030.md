> **Atualização documental de 30/10/2026:** consultar [ESTADO_GITHUB_STARLINK_032_20261030.md](ESTADO_GITHUB_STARLINK_032_20261030.md) para o snapshot do commit `ca1e59e4`, CI, limitações e pendências. Este documento mantém as evidências e decisões do momento em que foi escrito.

> Atualização documental de31/10/2026: [estado consolidado local/GitHub](ESTADO_ATUAL_STARLINK_20261031.md). CI nativa e quatro casos remotos API35 aprovados; execução local API37 incompleta; Quality Gate ainda bloqueado. Trabalho local pausado. Evidências históricas abaixo preservadas.

# Timeout no emulador — 30/10/2026

Status: experimento Android isolado, NÃO validado como solução real.
Base: 7d60cbe4d563b15377e57db019721b67dc032253.
Branch: fix/paravoce-timeout-investigation-20261030.

## Evidências

- Para Você 0.3.2 autenticado/sessão cadastrada, mas carregamento termina em timeout; canais, filmes e séries ficam vazios.
- Debugger capturou Conscrypt.startHandshake / ConnectPlan.connectTls aguardando leitura durante uma tentativa. Isso não demonstra que todas as falhas tenham a mesma causa.
- Windows resolve o domínio M81 para IP privado Tailscale 100.64.168.125; emulator ping resolve IP público 209.177.145.97. Health 200 no Windows sem forçar rota não comprova acesso público do emulador.
- Teste HTTPS sem proxy forçando IP público .97 expirou na conexão em 8,010 s (HTTP 000). IP .192 respondeu 200 em 1,369 s.
- Repetição: .97 respondeu 200 em 0,944 s; .192 em 0,944 s. Falha transitória de conectividade pública observada; não determinada a responsabilidade final entre rede e serviço.
- ProviderHttp.kt e XtreamProvider.kt eram idênticos à base oficial f25fafd antes deste experimento.
- App disponível previamente no AVD era 0.2.2 sem login, portanto não é baseline autenticada válida. APK oficial 0.3.2-layout foi instalado separadamente, aguardando login do proprietário para comparação equivalente.

## Experimento

Uma repetição de cada GET da sessão Xtream após TIMEOUT, incluindo leitura de admissão da conta. Espera cancelável de 500 ms; não repete estágios já concluídos, HTTP de recusa, erro TLS, episódios ou reprodução. Mantém limites HTTP originais, autenticação e validação TLS. Pode dobrar o tempo de falha de uma etapa persistente (até dois callTimeout de 90 s mais intervalo); não aprovado para distribuição.

11 testes focados passaram (5 StarlinkXtream, 1 XtreamMigration, 5 XtreamTimeoutRecovery), zero falhas/erros/skips. AssembleParaVoceTest aprovado. Assinatura debug verificada. Atualização instalada mantendo dados da sessão; teste real ainda terminou em timeout. Não declarar correção concluída nem reprodução validada.

## Próximo teste e retorno

Comparar 0.3.2-layout e Para Você com mesma conta/rede e catálogo frio. Se ambos falharem, não atribuir ao layout. Se somente Para Você falhar, registrar estágio, tempos e erro sanitizado antes de outra alteração. Canais, filmes, séries, reprodução e EPG reais dependem desse carregamento.

Para retornar ao APK de teste anterior, instalar com adb install -r o arquivo original em E:/IPTV/ENTREGA-PARA-VOCE-20261030/STARLINK-032-PARA-VOCE-INTEGRADO.apk (mesma assinatura/pacote), sem limpar dados. Não restaurar nem alterar banco. Original, checkout operacional, CoreAPI, publicação e rollback não foram modificados.
