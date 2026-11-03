# M81 / STARLINK IPTV — Cache rápido, categorias e continuidade (2026-11-03)

**Escopo:** candidata isolada `feat/starlink-xtream-cachefast-20261103` sobre a QA2 (`feat/starlink-xtream-on-demand-20261103`). Não substitui APK funcional nem modifica containers, banco ou fontes do M81 operacional. A QA2 continua não homologada e foi suspensa como proposta de lançamento.

## Auditoria feita no código verdadeiro

- M81 operacional: publicação 291, 170.106 entradas, 68 fontes e 32 grupos no M3U entregue; 141.433 URLs de capas com `image.tmdb.org`. Contagens de leitura, não benchmark de tempo.
- Séries no runtime: índice compactado por publicação, cache de sumários e detalhes sob demanda; o app anterior guardava o catálogo criptografado em blocos SQLite e renderizava cache antes da rede.
- Categorias originais podem misturar conteúdos por tipo: o grupo `Futebol` inclui canais lineares genuínos (FIFA+, NFL Channel etc.) e entradas com marcadores de episódio; `Filmes` inclui canais lineares de filmes e VOD. Não excluir esses grupos nem renomear os valores dos dados.
- Xtream `get_live_categories/get_vod_categories/get_series_categories` e respectivos `get_*_streams` já existem. Reutilizar IDs da origem, sem M3U por categoria.
- QA2 carregava só grupos ao entrar e guardava canais visitados exclusivamente em memória: fechando o app, perdia o conteúdo já baixado. Este é um problema verificado no código e não uma medição direta do aparelho.

## Alterações da candidata cachefast

- Banco local `catalog.db` versionado de 3 para 4 com duas tabelas novas para cache Xtream; mantém as tabelas anteriores intactas. Migrações v1/v2/v3 e histórico protegidos por testes Robolectric.
- Cache local criptografado de lista de categorias e das categorias efetivamente visitadas, em blocos de até 128 KiB para evitar limites do Android. Limite de 8 MiB por payload; payload maior não é truncado e continua disponível pela rede, mas não é salvo em disco.
- Retém no máximo oito grupos por origem no disco e memória agregada limitada a 20 mil itens sempre que houver vários grupos; nunca corta silenciosamente o grupo ativo. Esvazia cache ao remover ou editar uma fonte.
- Primeiro mostra dados locais, quando existem; revalida pela rede após 30 minutos ou ao atualizar manualmente. A gravação criptografada ocorre **após** os conteúdos serem exibidos, para não atrasar a primeira resposta.
- A interface esclarece nomes na aba **Canais ao vivo**: `Filmes` aparece como `Canais de filmes`, `Séries` como `Canais de séries`, `Desenhos` como `Canais de desenhos`. Strings em inglês e português, sem alterar `category_id`, `group`, fluxos e rotas.

## O que preservamos

Autenticação Starlink, Xtream, reprodução, URLs, IDs, EPG, VOD, séries e episódios, área Para Você, preferências, histórico, SQLite legado e biblioteca de imagens Coil. Fontes M3U/Xtream externas fora da conta Starlink não receberam nova estratégia. Painel M81 e sua integração Brasil IPTV 2026, incluindo BR04 mas não limitada a ela, continuam intocados.

## Limitações e pendências

- O cache é uma otimização da **segunda visita**; primeiro download de uma categoria grande ainda depende de rede/servidor. Não houve benchmark real no smartphone nem teste de capas e reprodução; não declarar a lentidão resolvida.
- Xtream atual não tem paginação completa por canais na API utilizada. Precisamos estudar categorias muito grandes em cópia de banco, política editorial de classificação e melhoria para primeiro acesso, sem varreduras pesadas na produção.
- A próxima auditoria do M81 deve comparar `live/vod/series` por tipo efetivo e categoria, sem apagar canais genuínos que pertençam a grupos como `Filmes`.
- PR M81 #28 (M3U por origem) e PR Android #24 (QA2) permanecem candidatos. Nova branch não deve ser fundida/implantada sem testes de aparelho e plano para conflito de branches. Nenhum upload de APK oficial ou modificação no painel operacional por este trabalho.

## Testes

Executar em `native`:
`./gradlew :core:test :app:testLayoutUnitTest :app:assembleParaVoceTest`.

A suíte cobre criptografia, round-trip de conteúdos, isolamento por fonte, limite de grupos, migrações de v1/v2/v3, integridade do histórico e teste sintético de blocos acima de 1 MiB. Registrar aqui somente os resultados verificados e o SHA correspondente após o commit.

### Evidências executadas após as alterações (2026-11-03)

- `:core:test`: 86 testes aprovados, 0 falhas.
- `:app:testLayoutUnitTest`: 153 testes aprovados, 0 falhas; inclui migrações e cache criptografado.
- `:app:assembleParaVoceTest`: compilação concluída com sucesso, APK local de teste com 23.884.512 bytes, SHA-256 `7C5EECA4094A2B7F13FAFA1F23CDE9EA17EE18C7C979D6832ED6C3BAF84B65E1`.
- O resultado no Android real, o comportamento das imagens e a latência inicial de rede não foram medidos; esta versão NÃO está homologada para distribuição oficial.
