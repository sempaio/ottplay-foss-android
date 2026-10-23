> Atualização 0.3.1 para celular: veja [STARLINK_CLIENTE_031.md](STARLINK_CLIENTE_031.md). O conteúdo abaixo registra a entrega anterior. TV, DLNA e espelhamento estão adiados nesta atualização.

# Retorno da prévia

A prévia usa `br.starlink.iptv.layout`. A versão anterior `br.starlink.iptv` permanece instalada com seus dados. Para retornar, abrir o ícone anterior; opcionalmente desinstalar somente “STARLINK IPTV Prévia”. Desinstalar apaga os dados dessa instalação; não é necessário para comparar.

Código anterior: branch `starlink/entrega-android-20261022`, commit `18e95f8ee743bd1d28020a9a837cfe02aed0bf7e`. Para inspecionar, criar outro worktree ou checkout limpo; não usar reset/clean. APK anterior preservado em `dist/STARLINK-IPTV-debug.apk`.

A migração SQLite1→2 apenas cria `recent`; não apaga catálogos, fontes ou favoritos. Não há migração entre os pacotes independentes. As duas fontes permanecem habilitadas; não houve alteração no M81.

Chave estável local fora do Git: `/workspace/.starlink-signing/layout.jks`, configuração privada no mesmo diretório. Guardar backup seguro desse diretório antes de trocar o computador. Nunca publicar o keystore ou o JSON privado. O certificado público pode constar no relatório. CI usa quatro segredos dedicados quando disponíveis: STARLINK_LAYOUT_KEYSTORE_B64, STARLINK_LAYOUT_STORE_PASSWORD, STARLINK_LAYOUT_KEY_ALIAS, STARLINK_LAYOUT_KEY_PASSWORD. Não substituir segredos de outras variantes.

A alternativa CI de APK fixado só aceita binários que correspondam ao recibo `starlink/layout-build.json` e ao fingerprint dos fontes nativos. Para novo build, usar a mesma chave, executar os testes, atualizar os dois APKs versionados e gerar novo recibo. Se não houver a chave, não assinar substituto como se fosse atualização; entregar outra variante ou recuperar o backup seguro.

O upload de assets em uploads.github.com retornou HTTP401 com a credencial desta integração. Alternativa: APK público versionado em dist e APK de instrumentação em starlink/validation, além do artefato do Actions. O keystore não é versionado.
