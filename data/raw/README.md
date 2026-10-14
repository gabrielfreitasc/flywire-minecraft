# data/raw — arquivos de origem

Deposite aqui, **sem renomear**:

| Arquivo | Tamanho esperado | Origem |
|---|---|---|
| `Connectivity_783.parquet` | ~97 MB | `philshiu/Drosophila_brain_model` (espelho do Zenodo `10.5281/zenodo.10676866` — **conferido idêntico aresta por aresta**, ver abaixo) |
| `Supplemental_file1_neuron_annotations.tsv` | ~31 MB | `flyconnectome/flywire_annotations` (repositório oficial dos autores, Schlegel et al. 2024), **versão do commit `17fc5772`** (04/05/2026) |

**Proveniência conferida (DT-1, 13/10/2026):** `python sim/tools/verify_primary.py` confere o
MD5 dos arquivos primários do Zenodo, compara o espelho com o primário aresta por aresta e
confere o hash da anotação contra a versão fixada acima. Versões mais novas da anotação
existem (29/09/2026: Tastekin et al. 2026) — trocar de versão é decisão de dado (re-rodar
o ingest e os experimentos), não atualização silenciosa.

## Regra: esta pasta é imutável

Nada aqui é editado, limpo ou sobrescrito por código. Toda transformação lê daqui e
escreve em `../interim/` ou `../processed/`. Se um arquivo precisa mudar, ele é
substituído inteiro e a validação roda de novo.

## Validação obrigatória

Os arquivos vêm de um espelho de terceiros, então `flywire_sim.ingest` **recusa a rodar**
se os totais não baterem com o artigo:

| Métrica | Valor exigido |
|---|---|
| Sinapses totais (soma dos pesos) | `54.492.922` |
| Conexões com ≥5 sinapses | `2.700.513` |
| Linhas de anotação | `139.248` |

Se falhar, o arquivo está truncado, corrompido ou é de outra materialização — **não é
para contornar o check**. Baixe de novo.

## Arquivos de referência (não entram na ingestão)

Diferente da tabela acima, estes NÃO são lidos por `flywire_sim.ingest` — não têm
validação de totais, não alimentam `data/processed/`. Servem só de cross-check manual
(nomenclatura de tipo celular, proveniência). Depositados aqui pela mesma regra de
imutabilidade, não por participarem do pipeline.

| Arquivo | Origem | Nota |
|---|---|---|
| `consolidated_cell_types.csv` | [codex.flywire.ai](https://codex.flywire.ai) (fonte primária, não espelho GitHub) | `root_id`→`primary_type`/`additional_type(s)`, sem descrição de função. 138.327 linhas, 96,4% de acordo com `cell_type` de `Supplemental_file1_neuron_annotations.tsv` (diferenças são sobretudo formatação — rótulo múltiplo com vírgula vira só o primeiro — ou nome alternativo mais padronizado, ex. `IPC`→`m_NSC_DILP`, consistente com o achado do F11 de que são endócrinos). |
| `connectivity_tags.csv` | [codex.flywire.ai](https://codex.flywire.ai) (fonte primária) | `root_id`→tags de TOPOLOGIA de rede (`rich_club`, `reciprocal`, `feedforward_loop_participant`, `3_cycle_participant`...) — não é função biológica, é estrutura do grafo. Usado pra confirmar que `pC1`/`DNa02` são hubs (`rich_club`), não periféricos — ver F18/F19 em `docs/03-roadmap-fases.md`. |
| `labels.csv` | [codex.flywire.ai](https://codex.flywire.ai) (fonte primária — "Community Labels", export bruto) | `root_id`→rótulo de texto livre + autor/data/lab, um por anotação manual feita por pesquisadores durante o proofreading (pode ter várias linhas por neurônio, inclusive conflitantes entre anotadores). 160.045 linhas, 110.038 `root_id` únicos. **Achado real usando este arquivo:** revelou ambiguidade de identidade em 2 dos 10 `pC1` do `aggression` (F18) e recaracterizou os 47 descendentes "incidentais" do `path_integration` (F19) como várias vias descendentes distintas e documentadas (esterçamento, postura de ovos, canto de corte), não ruído sem identidade. |

## Não versionar

Esta pasta está no `.gitignore`. Os arquivos são grandes e públicos; cada pessoa baixa
o seu. O que é versionado é o checksum, registrado em `data/processed/manifest.json`.
