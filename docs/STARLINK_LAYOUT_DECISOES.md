# Decisões da prévia

- Variante `layout`, pacote `br.starlink.iptv.layout`, código8/0.3.0-layout; namespace e motor nativo preservados. O usuário autorizou substituir o anterior, mas manter instalação separada permite comparar e reverter sem perda de dados.
- Paleta escura/ciano/magenta/violeta; marca geométrica original local, enquanto o arquivo mestre da marca fornecida não está disponível. Sem logotipos ou capas de terceiros embutidos.
- Catálogos e filtros calculados fora da thread principal; busca com debounce250ms. Prévia de cada fileira limitada20, categorias iniciais12; grade completa virtualizada.
- SQLite v1→v2 aditivo: tabela recent criptografada com o mesmo Keystore privado, limite100, descritor de episódio incluído. Favoritos/fontes/resumePositions mantidos.
- Login valida o servidor antes de salvar. URLs Xtream e M3U preservadas, sem fusão automática. Sem cadastro ou recuperação inventados.
- Atualização somente por consulta ao contrato existente M81, pacote instalado exato, HTTPS inclusive redirecionamentos, SHA256 e certificado de assinatura iguais. A prévia nunca obriga atualizar.
- Chave de desenvolvimento estável exclusiva em `/workspace/.starlink-signing`, fora do Git. A integração GitHub retorna HTTP403 para Actions Secrets: nenhum segredo existente foi alterado. CI aceita segredos dedicados; enquanto indisponíveis, usa APKs assinados e fixados versionados na branch segura, verificando hashes dos binários e de todos os fontes nativos. Qualquer modificação nativa sem novo build invalida essa alternativa.

O upload de assets em uploads.github.com retornou HTTP401 com a credencial desta integração. Alternativa: APK público versionado em dist e APK de instrumentação em starlink/validation, além do artefato do Actions. O keystore não é versionado.
