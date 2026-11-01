# STARLINK IPTV 0.3.2 — candidata oficial de instalação limpa

Esta branch é **somente candidata**, derivada da correção Xtream/Quality Gate do PR #18. Não publica APK, não faz merge, não altera M81, não sobrescreve o APK de teste aprovado.

## Por que outra variante?

O aplicativo que funcionou no celular está no pacote `br.starlink.iptv.paravoce` e é assinado para testes. A variante antiga `br.starlink.iptv.layout` é uma prévia. O código Android possui uma variante genérica `release` sem `CUSTOMER_MODE` ativo. Esta candidata acrescenta `official` com configuração herdada de produção (`release`), `CUSTOMER_MODE=true`, pacote `br.starlink.iptv` e nome `STARLINK IPTV`. Ela não altera as variantes já distribuídas.

A versão oficial **não usa debug signing**, **não contém keystore**, não permite fallback para APK antigo ou unsigned. A CI mantém dois jobs: validação da variante sem assinatura (artefato NÃO distribuído), e assinatura do APK somente após disponibilização dos quatro segredos permanentes.

## Preparação de assinatura (ação privada do proprietário)

Uma **chave de assinatura nova e permanente** pode ser adotada porque o proprietário autorizou instalação limpa em 31/10/2026. A chave deve ser gerada em equipamento confiável e guardada com backup offline protegido. Sua perda impedirá atualizações sem reinstalação, salvo mecanismo de assinatura delegada por loja.

Configure em [GitHub Actions Secrets](https://github.com/sempaio/ottplay-foss-android/settings/secrets/actions) os quatro campos:

- `STARLINK_OFFICIAL_KEYSTORE_B64`: arquivo keystore convertido integralmente em Base64, sem quebras de linha.
- `STARLINK_OFFICIAL_STORE_PASSWORD`: senha de armazenamento.
- `STARLINK_OFFICIAL_KEY_ALIAS`: alias da chave.
- `STARLINK_OFFICIAL_KEY_PASSWORD`: senha da chave.

**Nunca colocar keystore, senha, Base64 de keystore, tokens ou credenciais em arquivos do repositório, issues, comentários, conversa ou relatório.** Não gerar chave efêmera a cada build. Se for publicar em loja com App Signing, verificar as regras da loja antes de decidir as chaves de envio/assinatura.

## Processo de validação

1. Executar o workflow `.github/workflows/starlink-032-official-clean-install.yml` nesta branch. O job `qualify-official` valida build customer, testes core/layout, lint e manifest release sem publicar APK.
2. Após configurar os segredos, o job `signed-candidate` compila novamente, verifica assinatura criptográfica, pacote, marca, manifest não-debuggable, SHA256 e certificado; disponibiliza artefato de **candidata** por 30 dias.
3. Exigir o Quality Gate completo e testes Android instrumentados reais na mesma revisão. Registros da instrumentação anterior demonstraram um ANR de Quickstep no emulador, portanto não contar testes bloqueados como aprovados.
4. Instalar a candidata em aparelho físico, testar login, personalização, capas, logos, reprodução, fullscreen, Back/Home/background, favoritos, histórico, EPG, séries e TV Box. Não pressupor que todos os streams foram testados.
5. **Instalação limpa:** avisar sobre perda de dados locais. Como `br.starlink.iptv` difere de `br.starlink.iptv.paravoce`, pode coexistir com o teste para comparação. Só desinstalar a versão anterior quando desejado, com backup de preferências suportadas.
6. Após aprovação explícita, integrar cuidadosamente o conjunto final à `main`, publicar release oficial com hash, certificado e plano de atualização; nunca antes.

## Referência e retorno

Preservar `STARLINK-032-PARA-VOCE-INTEGRADO.apk`, SHA256 `f13bed2be48669908eca9f1648a79f214b6337da37d92047bd9d26b58195ea16`, como referência funcional. O registro de limpeza de instalação não é autorização para apagar dados de cliente automaticamente.

Nenhuma assinatura ou validação física nova é reivindicada neste documento.
