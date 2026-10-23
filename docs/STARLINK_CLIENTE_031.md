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

## Validação e limites

Os testes usam contas fictícias e mídias sintéticas. Não houve autenticação com conta real; portanto esta alteração não comprova a disponibilidade do servidor nem resolve por si só eventual bloqueio de acesso no painel. O erro mostrado no celular ainda não permite distinguir credenciais recusadas de restrições da rede/serviço. Não foram alterados serviços, dados ou configurações de produção.

Resultados, hash do APK e execução dos emuladores serão registrados após o término das verificações.

## Instalação e rollback

Instalar `dist/STARLINK-IPTV-layout.apk` sobre a prévia anterior: pacote e chave estável mantidos. Nunca instalar o APK de instrumentação como aplicativo do cliente.

A versão anterior permanece no commit `8caf5b7cfe4ca9c47391381b208d82ddec2c456e`. Para consultar/compilar a versão antiga sem descartar alterações, criar um worktree separado desse commit. Android pode bloquear downgrade de código 9 para 8; não desinstalar nem apagar dados sem necessidade e autorização. Consulte também `STARLINK_LAYOUT_ROLLBACK.md` para assinatura e preservação de dados. Nenhuma operação de rollback no painel é necessária.
