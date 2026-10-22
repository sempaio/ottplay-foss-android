# Rollback STARLINK

Nenhuma main ou configuração do M81 foi alterada. Todo o trabalho está em `starlink/entrega-android-20261022`.

- Base da consolidação preservada: `66fb9d53973fad03b7c30c9ad44452a5967c9f71`.
- Base nativa preservada: `4fc46551af1cdce6eabd879719eb6e6e82d73a35` de sempaio/ottplay-android.
- Para inspecionar a base sem perder alterações: `git worktree add --detach ../starlink-baseline 66fb9d53973fad03b7c30c9ad44452a5967c9f71`.
- Para desfazer uma correção publicada: criar outra branch a partir da entrega e usar `git revert <commit>`; revisar o diff, executar testes e publicar a reversão. Não usar reset --hard nem clean.
- Para parar builds automáticos, remover somente o gatilho da branch no workflow por commit revisável. Não modificar serviços do painel.
- O APK debug usa chave de desenvolvimento, não é publicação de produção. Uma instalação existente com o mesmo package e outra assinatura não pode ser atualizada por este APK. Preservar a instalação e seus dados; não desinstalar automaticamente.
- A desinstalação manual remove sessão, catálogo, favoritos e posições locais. O cofre não participa do backup Android. Uma versão anterior com a mesma chave pode exigir `adb install -r -d`, apenas em dispositivo de testes e com ciência do downgrade; não apagar dados para forçar uma atualização.

Chaves debug locais não são versionadas. Para futuras atualizações distribuídas, adotar uma chave estável própria, fora do Git e com backup seguro. Não usar chave debug em publicação na Play Store.
