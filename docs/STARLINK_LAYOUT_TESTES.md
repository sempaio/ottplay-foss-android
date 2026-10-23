# Testes da prévia neon

## Local

Com JDK17/SDK37, executar :core:test :app:testLayoutUnitTest :app:lintLayout :app:assembleLayout :app:assembleLayoutAndroidTest com a chave estável configurada por ambiente. Resultado: BUILD SUCCESSFUL; 66+114 testes, zero falhas; lint0 erros/21 avisos. Evidência: evidencias-starlink-layout/local-tests.json, apk-validation.txt e signature.txt.

Novos casos: ida/volta de descritor de episódio criptografado, ausência de senha no payload, conclusão removida, limite100 e exclusão da fonte, migração preservando catálogo; atualização nula/pacote errado/versão antiga, URL HTTP/com credenciais, checksum ausente e resposta inválida rejeitados.

O primeiro bloqueio local foi o cache Robolectric em diretório somente leitura; o cache JVM foi redirecionado para workspace. Não foram suprimidos testes ou reduzidas versões de dependências.

## Emuladores

Suite única StarlinkAcceptanceSuite, 15 casos/6 classes: login M81, playlist independente, inicialização real, início/detalhes/temporadas, filtros/localização, favoritos e fluxos existentes, foco DPAD, editor, MP4 Media3/session restore e HLS master/media/TS com307 e isolamento de cabeçalhos. Não há servidor IPTV real ou conta real nas fixtures.

Primeira rodada: build/180 testes passaram; celular14/15 e TV13/15. Causas: seletor por texto Movies encontrou dois botões válidos; na grade TV o destaque grande ocultava a primeira capa. Correções: seletores estáveis por tag e destaque concentrado no Início, liberando a grade. Capturas são recolhidas também em caso de falha.

Rodada final: https://github.com/sempaio/ottplay-foss-android/actions/runs/39998982196 — **SUCCESS**. Celular Android35/Pixel7:15 testes,zero falhas; TV Android36/tv1080p:15 testes,zero falhas. Todas as6 classes executadas em cada emulador. Somados aos180 testes locais,210 execuções aprovadas. Instrumentation JSON/TXT e5 capturas por dispositivo em evidencias-starlink-layout/phone e tv. O ZIP do artefato do app foi baixado e seu APK comparado por SHA256 com o arquivo local e o download público: idênticos.

## Limites da evidência

Validação simulada não comprova autenticação de uma conta real nem disponibilidade de cada canal do M81. Nenhuma senha da captura enviada foi reutilizada. Reprodução real validada é do conteúdo sintético empacotado/servido localmente. Não houve teste de Cast, atualização real, Tizen, webOS ou PiP manual.

Inspeção visual: marca/tema/login/detalhes/temporadas registrados. A imagem inicial de Início na TV captura um quadro anterior ao destaque dinâmico; não foi tratada como prova visual completa desse estado. Uma experiência de espera adicional para captura foi preservada em scratch, sem alterar os fontes/binário da entrega validada.
