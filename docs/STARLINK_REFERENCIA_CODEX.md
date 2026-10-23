# Referência para o Codex do computador

## Base confirmada no celular pelo operador

- Versão: **0.3.1-layout**, código 9, pacote `br.starlink.iptv.layout`.
- Repositório: https://github.com/sempaio/ottplay-foss-android
- Branch de referência: `starlink/layout-neon`.
- Commit exato de código e APK: `028cde75bbc45eb1ed41f0adc7d5485e953e36c3`.
- Código: https://github.com/sempaio/ottplay-foss-android/tree/028cde75bbc45eb1ed41f0adc7d5485e953e36c3/native
- APK: https://raw.githubusercontent.com/sempaio/ottplay-foss-android/028cde75bbc45eb1ed41f0adc7d5485e953e36c3/dist/STARLINK-IPTV-layout.apk
- SHA256: `414abb9a8c5bf49ef3a0b4f1536cd5b58e3994fe057ce85df2d1477130c85e26`.
- CI: https://github.com/sempaio/ottplay-foss-android/actions/runs/40004079694 — 18 testes de celular aprovados; 186 testes unitários locais aprovados.

O usuário confirmou login e uso no celular. Confirmou especificamente Chaves com temporadas/episódios e reprodução. Isso não valida todos os registros do catálogo.

## Atualização do catálogo em verificação

- Versão: **0.3.2-layout**, código 10, mesmo pacote e chave.
- Branch: `starlink/celular-catalogo-032`.
- Commit exato: `f25fafdcc39ab91390c428dd21dbdb5dda8d90f5`.
- Código: https://github.com/sempaio/ottplay-foss-android/tree/f25fafdcc39ab91390c428dd21dbdb5dda8d90f5/native
- APK: https://raw.githubusercontent.com/sempaio/ottplay-foss-android/f25fafdcc39ab91390c428dd21dbdb5dda8d90f5/dist/STARLINK-IPTV-layout.apk
- SHA256: `856c576fb36a002c69082907077355455017ab6ff541cce361a51afbfb147bd4`.
- CI: https://github.com/sempaio/ottplay-foss-android/actions/runs/40008804419

187 testes unitários locais e build CI aprovados. Primeira execução do emulador: 16/19 aprovados; três falhas de janela sem foco no teste de teclas. Login, rolagem, temporadas, MP4 e HLS aprovados. Nova tentativa solicitada com os mesmos APKs para avaliar instabilidade; não declarar a suíte inteira aprovada antes do resultado.

Arquitetura real: `native/app` (Android Kotlin/Compose/Media3), `native/core` e núcleo compartilhado existente. Não usar o projeto web/Rust como substituto do Android nativo. APK de cliente fica em `dist/STARLINK-IPTV-layout.apk`; não distribuir o APK de instrumentação como app.

## Painel e escopo

Resolução das séries transferida pelo usuário ao Codex do computador. Candidata de parser (sem deploy): https://github.com/sempaio/painel-m81-iptv-cloud/pull/1 . Formatos com separadores explícitos foram corrigidos em branch. Temporada isolada não deve gerar episódios inventados. Verificar classificação/metadados reais dos registros afetados antes de alterar a publicação.

Servidor Xtream fixo: https://panel.taila5c5c2.ts.net . Playlist independente: https://panel.taila5c5c2.ts.net/app/starlink-iptv/playlist.m3u . O cliente informa somente usuário e senha; edição de servidor/fontes bloqueada na versão cliente. Não compartilhar credenciais nos relatos.

TV, DLNA, espelhamento e comportamento em segundo plano seguem adiados. Botão Atualizar fixo no topo é preferência registrada para próxima melhoria; não integra a alteração atual. Preservar 0.3.1 como referência estável, sem aplicar downgrade destrutivo no dispositivo.
