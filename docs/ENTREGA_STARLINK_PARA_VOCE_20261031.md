# STARLINK Para Você 0.3.2 — entrega de teste, 31/10/2026

## Artefato e código

- Código validado: `96fb081058f1fae693fdf9f3c86ed0e10c9a023b`.
- Branch: `starlink/032-finalizacao-20261031`; PR17, sem merge.
- APK: `STARLINK-IPTV-Para-Voce-0.3.2-teste.apk`, 23037188 bytes.
- SHA256: `f0521a067c7d67b98787b69c505e273686d6eebe7434888835f960ca7c86e8a9`.
- Package `br.starlink.iptv.paravoce`; versionCode10; versionName `0.3.2-para-voce-test`; Android mínimo26, target36, compile37.
- Assinatura Android Debug v2 válida, certificado SHA256 `6bd264716fef31b3945fe953439042ed6e8ed5f0faddd03e77945085c99f3ce6`.
- APK produzido pela CI do commit96fb0810, não o dist antigo. ZIP conferido: nenhum .env, keystore, banco, vault ou arquivo privado.

## Correções e preservação

Mantidas as funções do PR9 e da consolidação ca1e59e4: assistente/interesses, consentimento inicialmente desligado, perfis locais, favoritos, históricos criptografados por perfil, busca, tela cheia/Voltar, categorias conservadoras e arte sem logotipo sobreposto. Não foram recriadas nem anunciadas como mudanças novas desta retomada.

Nesta retomada: bateria QA ampliada; teste antigo de sessão em segundo plano passou a ativar EXPLICITAMENTE essa preferência antes de exigir continuidade, restaurando a preferência anterior no teardown. As asserções de sessão, decodificação, retomada e Stop foram mantidas. A configuração do aplicativo normal não foi alterada para satisfazer o teste.

Dois golden files que ainda geravam erro de formatação foram preservados, com exclusões específicas do formatador. O JSON documental foi formatado; não houve exclusão de testes funcionais ou desativação do Quality Gate.

## Verificações automatizadas

- Local anterior sobre o mesmo código: 75 core +145 app, zero falhas/erros/ignorados.
- CI nativa do código96fb0810: SUCCESS, testes unitários, lint, build e inspeção APK.
  https://github.com/sempaio/ottplay-foss-android/actions/runs/41214124848
- Lint Android: sem erros; 20 avisos e1 hint já registrados.
- CI Android35 ampliada: **28 casos, 0 falhas, 0 erros, 0 ignorados**, XML baixado e contado.
  https://github.com/sempaio/ottplay-foss-android/actions/runs/41214125068
- Suítes: ForYou3, Activity/player4, catálogo/controle5, cliente3, armazenamento10, sessão nativa1, HLS1, ClearKey1. São fontes/vídeos sintéticos locais, não a disponibilidade de todos os provedores.
- Tentativa anterior:27/28, com falha do contrato de background sem opt-in. Evidência preservada no run41212819009; não foi apagada nem apresentada como sucesso.
- Instrumentação local API37 continua limitada por InputManager/Espresso. API35 executou as asserções completas acima; não houve skip para esconder essa incompatibilidade nem promessa de homologação física de todos os Android/TV Boxes.

## Verificação real no emulador

Foi instalado o build local do mesmo código Android, SHA `51f6dbc928a9734154f00d26d3172913a92fc8f42f1bd1ce63b04126f879d1c6`, assinatura local diferente da CI. Não afirmar que os dois APKs são byte a byte iguais.

Login e histórico existentes sobreviveram à atualização do pacote de teste. Catálogo em cache e atualização real concluída sem timeout:1997 canais,26583 filmes,5152 séries. FIFA+ em Futebol e NFL Channel em Esportes observados; IDs/URLs/tipos não foram mudados.

A URL da amostra live FIFA+ exibiu vídeo/preroll; Back encerrou a sessão, que atingiu NONE. Não foi auditada toda a grade do canal.

Patagonia exibiu vídeo em tela cheia com PLAYING e posição8829ms; consulta autenticada get_vod_info e resolução da mesma entrada recente também tiveram sucesso. Houve uma tentativa anterior com mensagem genérica; não foi possível confirmar sua causa. Ocorrência intermitente permanece sob observação, sem atribuir HTTP403, defeito do player ou culpa do servidor por suposição.

Chaves carregou temporadas e episódios reais no build consolidado. S01E01 já havia reproduzido na medição anterior. O replay final abriu o player em BUFFERING na posição de retomada46903ms e foi encerrado após a medição; essa tentativa NÃO conta como reprodução final concluída. Disponibilidade/latência da origem e retomada real do episódio continuam sob observação.

Áudio audível e TV Box física não foram validados; emulador iniciado sem áudio. Testes de estado da sessão não equivalem a ouvir o aparelho.

## EPG e M81

Guia da amostra FIFA+ abriu e informou ausência de programação. O aplicativo preserva XMLTV/cache/get_short_epg; não inventa grade. Cache operacional anteriormente examinado continha dados antigos; atualizar/corrigir dados EPG exige tarefa própria no M81, não executada.

Conferência final somente leitura: CoreAPI healthy, mesma imagem4594259e e StartedAt de30/10/2026 19:31:33 UTC; publicação290, ID292,170016 registros, arquivo e registro com SHA `d0b0d616a91576eda948f0a6bb2712e73c5a2550d6bd385653d5deb2c84f5fe1`.

Nenhuma implantação, reinício de serviço, escrita operacional, sincronização, republicação, alteração de credenciais, WhatsApp/Mercado Pago ou troca de app dos clientes. Backups e rollback M81 preservados.

## Limites da liberação e instalação

APK liberado como **pré-versão de teste**, não homologação irrestrita/distribuição definitiva. O Quality Gate geral mantém dependency-review indisponível por configuração do repositório. O ajuste final de formatação documental será verificado nesse workflow; não foi desativado para aprovar a entrega. Sem merge na main.

Instala ao lado de `br.starlink.iptv.layout`. Não forçar atualização sobre outro APK Para Você assinado com chave diferente; manter a instalação anterior e seus dados. Este APK não contém a conta do emulador: o usuário informa sua própria conta no aparelho.

Para retorno, abrir o app oficial preservado. Não reverter CoreAPI/banco por causa de um APK de teste. O SQLite local do pacote de teste foi elevado para versão3; não fazer downgrade desse banco usando APK anterior sem estratégia compatível.

Licença MIT/atribuição OTT-play preservadas; código correspondente disponível no commit e no arquivo-fonte do tag de teste. Capturas de mídia, logs brutos e dados privados não são assets da release.
