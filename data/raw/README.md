# data/raw — arquivos de origem

Deposite aqui, **sem renomear**:

| Arquivo | Tamanho esperado | Origem |
|---|---|---|
| `Connectivity_783.parquet` | ~97 MB | `philshiu/Drosophila_brain_model` (espelho do Zenodo `10.5281/zenodo.10676866`) |
| `Supplemental_file1_neuron_annotations.tsv` | ~31 MB | `flyconnectome/flywire_annotations` |

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

## Não versionar

Esta pasta está no `.gitignore`. Os arquivos são grandes e públicos; cada pessoa baixa
o seu. O que é versionado é o checksum, registrado em `data/processed/manifest.json`.
