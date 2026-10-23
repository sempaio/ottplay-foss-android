# STARLINK IPTV — prévia neon 0.3.0-layout

Repositório: https://github.com/sempaio/ottplay-foss-android
Branch: `starlink/layout-neon`
Commit do código e APK validado: `8caf5b7cfe4ca9c47391381b208d82ddec2c456e`
Pacote: `br.starlink.iptv.layout` · nome: **STARLINK IPTV Prévia** · código8 · Android8+ (min26, target36, compile37).

## Download e instalação

[Baixar APK](https://github.com/sempaio/ottplay-foss-android/raw/8caf5b7cfe4ca9c47391381b208d82ddec2c456e/dist/STARLINK-IPTV-layout.apk). Arquivo local: `dist/STARLINK-IPTV-layout.apk` (21.318.837 bytes). SHA256: `98e19552f9eab0a2d027da49424a93d372e5a5e5b07f4b7e125561f4b5bddf0d`.

No Android, abrir o link, baixar e abrir o APK; autorizar instalação pelo navegador/gerenciador usado quando solicitado pelo sistema. O aplicativo aparece como STARLINK IPTV Prévia e pode coexistir com o anterior. Informar somente usuário e senha no login M81. Na TV, transferir o mesmo APK para um gerenciador de arquivos e abrir; navegação por controle remoto. Não é necessário instalar o APK de instrumentação em starlink/validation.

## Implementado

- Tema escuro ciano/magenta/violeta; identidade vetorial local neon; navegação inferior no celular e lateral rolável na TV.
- Início com destaque, canais, filmes, séries e fileiras por categoria; máximo20 itens por fileira e acesso à grade virtualizada completa. Conteúdo e imagens vêm da fonte; placeholders locais quando faltam imagens. Sem rankings, capas ou anúncios de atualização fictícios.
- Login dedicado com dois campos, alternância da senha, carregamento e proteção contra envio repetido; fontes avançadas continuam disponíveis. Erro de autenticação mantém o editor; somente salvamento confirmado fecha a tela.
- Detalhes de filmes por get_vod_info sob demanda, temporadas e episódios, busca/filtros, favoritos, seleção de canal para guia EPG, configurações, privacidade e suporte por ação do usuário.
- Player Media3 preservado com nova paleta/marca, controles nativos, faixas, tela cheia, PiP e políticas de reprodução em segundo plano existentes.
- Continue assistindo com descritor de filme/episódio e posição criptografados no banco local; limite100 registros, exclusão de concluídos e remoção da fonte. Migração SQLite1→2 aditiva; seleções e fontes existentes preservadas. Minutos restantes somente quando há duração conhecida.
- Filtros/agrupamentos fora da thread principal, busca com debounce250ms, listas/grades lazy e identificação estável.
- Consulta de atualização uma vez por sessão e manualmente: pacote exato, versão superior, HTTPS inclusive redirecionamentos, checksum e assinatura; instalação somente pelo usuário/sistema. Ausência de release oculta o aviso. A prévia não força atualização.

## Integrações preservadas

Xtream: `https://panel.taila5c5c2.ts.net`. Playlist independente: `https://panel.taila5c5c2.ts.net/app/starlink-iptv/playlist.m3u`. Xtream permanece a fonte padrão após login aceito; a playlist é adicionada se ausente. Nenhuma fonte foi desativada ou mesclada. Chamadas de catálogo, episódios, EPG e formatos de reprodução existentes preservadas. Não há alteração de painel, produção, Docker, PostgreSQL ou serviços M81.

## Validação

Testes locais: 66 do núcleo e114 do aplicativo, sem falhas; lint sem erros (21 avisos, incluindo versões/recursos legados). APK validado com aapt/apksigner e inspeção dos DEX: pacote/nome/Internet/launchers celular+TV corretos, assinatura íntegra, URLs corretas e ausência da senha sintética de teste no executável do app.

Execução dos emuladores: https://github.com/sempaio/ottplay-foss-android/actions/runs/39998982196 — **concluída com sucesso**: build aprovado,15/15 testes no celular Android35 e15/15 na TV Android36, sem falhas. Resultados e capturas em STARLINK_LAYOUT_TESTES.md. Artefato: STARLINK-IPTV-layout-APK (ID12521631467). O mesmo APK assinado é usado no celular, TV e download; hashes e fingerprint dos fontes impedem validar binário desatualizado.

## Limitações e pendências

O arquivo mestre do logotipo fornecido não está disponível no workspace; esta prévia usa marca vetorial geométrica original em neon. Não é uma reprodução literal da montagem enviada. Novos textos têm português e inglês; demais idiomas preservados com esses novos rótulos em inglês. Não houve benchmark com168mil itens nesta prévia.

Não foram usadas credenciais reais: autenticação e conteúdo do servidor do cliente precisam da conta do usuário. Testes de reprodução usam MP4/HLS sintéticos e redirecionamento307. Histórico de mídia com duração desconhecida pode não ser capturado pelo gravador atual; a interface não inventa minutos. Atualização não tem release publicada no M81 para este pacote; instalação de atualização real não foi exercitada.

Google Cast e gravação permanecem pendentes; não há botões fictícios. Android TV não implica Tizen ou webOS. PiP existente foi preservado; falta validação manual desse fluxo na prévia.

Actions Secrets retorna403 e uploads de release retornam401 para a integração atual. A chave estável está fora do Git; CI aceita segredos exclusivos quando provisionados. Alternativa já aplicada: APKs assinados versionados na branch, com recibo e fingerprint obrigatório; CI testa e distribui esses mesmos arquivos. Nenhuma chave privada/credencial foi publicada. Instruções em STARLINK_LAYOUT_ROLLBACK.md.

As capturas foram inspecionadas: login, detalhes e temporadas nos dois formatos. A captura inicial do Início na TV foi obtida antes de apresentar o destaque; essa imagem não comprova a apresentação final de todas as fileiras. Os testes de ação/foco e reprodução passaram. Refinamento dessa captura pode ser feito separadamente sem alterar o APK validado.
