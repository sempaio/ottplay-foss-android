# Estado inicial — 2026-10-22

Workspace: `/workspace`. Todos os clones chegaram limpos; nenhum trabalho local preexistente. Nenhuma edição em main. Desenvolvimento em `starlink/entrega-android-20261022`, derivada de `66fb9d53973fad03b7c30c9ad44452a5967c9f71`.

## ottplay-android
4fc46551af1cdce6eabd879719eb6e6e82d73a35 ci: prepare reviewed ARC standby routing (#93)
Licenças: THIRD_PARTY_LICENSES, LICENSE

## ottplay-foss-android
66fb9d53973fad03b7c30c9ad44452a5967c9f71 fix: install Android command-line tools before sdkmanager
Licenças: LICENSE

## ottplay-netflix
1573dd2d8edaf647e9f6f19dca9864dd5fe88adc Adiciona instruções de build
Licenças: 

## ottplay-foss
f51bac7ac2327d6cfab9e4c72a02f0d7e7311ff0 docs: translate full manuals into 24 languages (#839)
Licenças: LICENSE

## painel-m81-iptv-cloud
3635440e055351479a168ba35437dff36343fb7c chore: initial sanitized cloud workspace
Licenças: 

## Decisão arquitetural
Preservar Kotlin/Compose/Media3 e módulos app/core do nativo em native/. Não misturar Capacitor/web/Rust no APK. Fonte nativa fixada em 4fc46551af1cdce6eabd879719eb6e6e82d73a35; MIT, NOTICE e licenças transitivas preservadas. Core compartilhado já fornecido como JAR com recibo de integridade.
Toolchain original: AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, Java 17, compileSdk 37, targetSdk 36, minSdk 26, Compose BOM 2026.09.00, Media3 1.11.1. Compilação ainda não demonstrada neste ambiente; Java disponível inicialmente: 21; SDK ausente.
Workflow 39736040191 em 66fb9d53 falhou: setup-android@v3 usa packages padrão tools platform-tools; tools não existe. Workflow também instala SDK 36 enquanto fonte móvel main exige 37. Corrigir pacote e fixar fonte.
Tema Netflix: apenas CSS e README, sem licença explícita. Reutilizar somente conceitos de cores escuras, contraste e cartões; não copiar CSS nem marcas/imagens. Identidade existente em starlink/brand_native.py apenas muda nome/ID e grava URL sem integração UI.
M81 somente leitura: app/routers/xtream_api.py documenta login, seis catálogos, get_vod_info, get_series_info, get_short_epg (lista vazia atualmente), XMLTV e redirecionamento HTTP 307. Não há transcodificação presumida.
Base nativa já contém catálogos Xtream, episódios, filtros, favoritos, cofre AES-GCM AndroidKeyStore, retomada, PiP e controles TV. Verificar com testes antes de declarar funcional. Cast não encontrado. Sem dispositivo ou assinatura existente fornecidos; package br.starlink.iptv será debug e não garante atualização de instalação assinada por outra chave.
