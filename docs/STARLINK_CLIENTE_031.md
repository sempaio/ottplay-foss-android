# STARLINK IPTV — versão cliente 0.3.1

Branch: `starlink/layout-neon`, repositório `sempaio/ottplay-foss-android`.
Pacote preservado: `br.starlink.iptv.layout`, nome STARLINK IPTV Prévia, código 9.

## Alterações

- Login exclusivo para usuário e senha, com logo STARLINK IPTV, campos arredondados, senha oculta e suporte discreto. Servidor e playlist são fixos.
- Interface cliente sem adicionar/editar/remover fontes, tipos M3U/Xtream/Stalker, URLs, cabeçalhos, EPG ou importação/exportação de configurações. As ações também são bloqueadas no controlador; registros antigos externos ficam preservados e invisíveis.
- Conta permite atualizar credenciais e sair. Sair remove as fontes gerenciadas locais, sem modificar serviços ou contas do painel.
- Mensagens curtas para autenticação, internet, tempo de espera e indisponibilidade; sem URLs, protocolos ou detalhes internos. Classificação interna preserva a diferença entre DNS, TLS, timeout, resposta inválida e autenticação, sem registrar credenciais.
- Logo derivada do arquivo de identidade existente `tv_apps/starlink_m3u/base/starlink/brand.png` no repositório do operador, com adaptação visual para IPTV. Créditos e licenças originais preservados.
- DLNA e espelhamento excluídos desta entrega por solicitação do usuário. Google Cast continua pendente.
- Entrega priorizada para celular. Adaptação e validação de Android TV adiadas por solicitação posterior; o workflow desta versão executa apenas o emulador de celular.

## Validação e limites

Os testes usam contas fictícias e mídias sintéticas. Não houve autenticação com conta real; portanto esta alteração não comprova a disponibilidade do servidor nem resolve por si só eventual bloqueio de acesso no painel. O erro mostrado no celular ainda não permite distinguir credenciais recusadas de restrições da rede/serviço. Não foram alterados serviços, dados ou configurações de produção.

Validação local concluída: 68 testes do núcleo e 118 do aplicativo, todos aprovados; lint sem erros e 21 avisos legados. Compilação do aplicativo e APK de instrumentação aprovadas. aapt/apksigner e inspeção dos DEX confirmaram pacote, nome, versão, Internet, assinatura íntegra, URLs previstas e ausência da senha sintética no APK cliente.

APK: `dist/STARLINK-IPTV-layout.apk`, 23.860.026 bytes, versão `0.3.1-layout`.
SHA256: `414abb9a8c5bf49ef3a0b4f1536cd5b58e3994fe057ce85df2d1477130c85e26`.
Código e APK: commit `028cde75bbc45eb1ed41f0adc7d5485e953e36c3`.
Execução celular: https://github.com/sempaio/ottplay-foss-android/actions/runs/40004079694 — **concluída com sucesso**, build e 18/18 testes no celular Android 35, incluindo login com servidor fixo, ausência de gestão de fontes, inicialização, catálogo, temporadas e reprodução MP4/HLS sintética. Evidências em `evidencias-starlink-cliente-031/`. O download público foi baixado e seu SHA256 coincide com o APK local validado. Artefato baixável: `STARLINK-IPTV-layout-APK` (ID 12522894044).

[Download desta versão](https://raw.githubusercontent.com/sempaio/ottplay-foss-android/028cde75bbc45eb1ed41f0adc7d5485e953e36c3/dist/STARLINK-IPTV-layout.apk).

## Instalação e rollback

Instalar `dist/STARLINK-IPTV-layout.apk` sobre a prévia anterior: pacote e chave estável mantidos. Nunca instalar o APK de instrumentação como aplicativo do cliente.

A versão anterior permanece no commit `8caf5b7cfe4ca9c47391381b208d82ddec2c456e`. Para consultar/compilar a versão antiga sem descartar alterações, criar um worktree separado desse commit. Android pode bloquear downgrade de código 9 para 8; não desinstalar nem apagar dados sem necessidade e autorização. Consulte também `STARLINK_LAYOUT_ROLLBACK.md` para assinatura e preservação de dados. Nenhuma operação de rollback no painel é necessária.
