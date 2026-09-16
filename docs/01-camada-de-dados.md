# Camada de Dados — FlyWire → Minecraft

Decisão v1: **subcircuito ocelar**, apenas topologia, materialização **783**.

## 1. Fontes

| Camada | Fonte | Acesso |
|---|---|---|
| Anotações (139.248 neurônios) | `flyconnectome/flywire_annotations` → `Supplemental_file1_neuron_annotations.tsv` | GitHub raw, sem login |
| Conectividade (15.091.983 arestas) | `philshiu/Drosophila_brain_model` → `Connectivity_783.parquet` | GitHub raw, sem login |
| Fonte primária equivalente | Zenodo `10.5281/zenodo.10676866` (10,6 GB, feather) | **bloqueado** pela política de egresso do sandbox |
| Portal oficial | `codex.flywire.ai/api/download` | requer login Google; **bloqueado** aqui |

O espelho do GitHub foi escolhido porque Zenodo, Codex e Google Cloud Storage não passam
pelo proxy deste ambiente. Ele já traz o sinal sináptico (`Excitatory` ±1) pré-computado.

### AD-05 — A Zenodo não é bloqueante para a v1  ·  *superseded por AD-11*

Decidido em 10/09/2026. O download direto da Zenodo retorna 504 (timeout do lado deles
nos arquivos grandes; a página do registro está no ar). Seguimos sem ela.

O que existe só na Zenodo, e onde isso importa:

| Dado | Zenodo | Espelho | Necessário na v1? |
|---|---|---|---|
| Pares pré→pós + peso | ✔ | ✔ | sim — **temos** |
| Quebra por neurópilo | ✔ | ✘ | não (topologia pura) |
| Coordenadas XYZ por sinapse (9,5 GB) | ✔ | ✘ | não (topologia pura) |
| Probabilidade dos 6 NTs por sinapse | ✔ | ✘ | não (`top_nt` por neurônio basta) |
| Sinal sináptico ±1 | ✘ | ✔ | sim — **temos** |

As três lacunas caem exatamente no escopo de geometria, descartado na v1.

**Passa a ser necessária quando:** (a) posicionarmos neurônios respeitando a anatomia real,
(b) agruparmos por região cerebral, ou (c) precisarmos reproduzir o pipeline a partir da
fonte primária sem depender de terceiros.

**Dívida técnica assumida:** dependemos de um espelho para os dados e de Shiu et al. para a
regra de sinal. A validação numérica cobre integridade, não proveniência. Quando a Zenodo
voltar, baixar só `proofread_connections_783.feather` (852 MB) ou usar o portal do Codex
com login Google — ambos mais leves que o dump completo.

### Validação contra o artigo (obrigatória, pois é espelho de terceiros)

| Métrica | Artigo (Dorkenwald 2024) | Arquivo baixado | ✔ |
|---|---|---|---|
| Sinapses totais | 54,5 M | **54.492.922** | ✔ |
| Conexões com >4 sinapses | ~2,7 M | **2.700.513** | ✔ |
| Neurônios | 139.255 | 138.639 no grafo / 139.248 anotados | ✔ (diferença = neurônios sem aresta ≥1) |

## 2. Extração do subcircuito

`build_ocellar.py`: semente = todos os neurônios anotados como `ocellar`
(fotorreceptores ocelares + OCG + OCC + DNp28), expansão BFS a jusante com
limiar ≥5 sinapses, parando em neurônios descendentes (fronteira motora).

Varredura de parâmetros:

| Limiar | Saltos | Nós | Arestas | Descendentes |
|---|---|---|---|---|
| ≥5 | **1** | **625** | **2.981** | **92** |
| ≥5 | 2 | 10.578 | 174.466 | 517 |
| ≥10 | 2 | 4.241 | 30.092 | 339 |
| ≥20 | 2 | 1.149 | 3.123 | 149 |

**v1 = limiar ≥5, 1 salto.** Cadeia sensório-motora completa em 625 nós.

## 3. Saída (L1)

- `out/nodes.csv` — `nid` (int32 sequencial), `root_id`, classes, lado, neurotransmissor, `role`
- `out/edges.csv` — `pre_nid`, `post_nid`, `syn` (peso), `sign` (±1)
- `out/manifest.json` — parâmetros, contagens, checksums, citação

Composição: 273 sensoriais · 260 interneurônios · 92 descendentes (+8 motores).
1.636 arestas excitatórias / 1.345 inibitórias.

**Nota (achada na F2):** os 273 fotorreceptores ocelares têm `cell_type` **vazio** na
fonte — não é bug do pipeline, o classificador não atribui tipo celular a eles. Qualquer
consulta que agrupe/filtre por `cell_type` (ex.: `telemetry.top_cell_types`) precisa
descartar `cell_type = ''` explicitamente, senão o próprio estímulo aparece disfarçado
de "resultado" na consulta.

## 4. Decisões de arquitetura registradas

**AD-01 — Identidade.** `root_id` é int64 e muda a cada proofreading. Congelado em 783
e remapeado para `nid` int32 sequencial. Só `nid` circula no Minecraft/NBT.

**AD-02 — Sinal sináptico.** Não é dado da fonte; é regra nossa derivada da probabilidade
de neurotransmissor (Eckstein et al. 2024): ACh/DA/OA → excitatório, GABA/Glu → inibitório.
Versionar essa regra.

**AD-03 — Override dos fotorreceptores.** Os 273 fotorreceptores ocelares aparecem como
`serotonin` com confiança média 0,58. Isso é artefato: o classificador tem só 6
neurotransmissores e **histamina não está entre eles**. Fotorreceptores de *Drosophila* são
histaminérgicos e **inibitórios**. Precisa de override explícito na camada L2, senão a
entrada sensorial inteira fica com o sinal errado.

**AD-04 — Fronteira motora.** Neurônios descendentes são o contrato de saída: o vetor de
92 canais que o mob consome. A BFS não atravessa essa fronteira.

## 5. Citação (CC-BY-4.0)

Dorkenwald et al. 2024, *Nature* 634:124 · Schlegel et al. 2024 · Shiu et al. 2024

**Citação por coluna de dado:** `FlyWire Citation Guidelines - Data.csv` (raiz do
projeto) é a tabela oficial do FlyWire — qual paper citar pra cada coluna específica
usada (`cell_type` → Dorkenwald/Schlegel/Zheng/Matsliah 2024; sinal sináptico/NT →
Eckstein/Bates 2024; etc.). Consultar antes de publicar qualquer resultado citando só
"Dorkenwald et al." genericamente.


---

## AD-11 — Fonte primária reincorporada (supersede AD-05)

Decidido em 16/09/2026. A Zenodo voltou a ficar acessível **para o usuário**, e o
conjunto completo (10,6 GB) foi baixado para `data/raw/`.

Importante: **o egresso continua bloqueando a Zenodo tanto no contêiner de nuvem
quanto na VM Linux do computador** (403 no proxy, em ambos). O download é feito pelo
navegador do usuário; o pipeline apenas consome o que já está em disco. Não escrever
código que tente buscar da Zenodo — vai falhar.

### O que muda

| Item | Antes | Agora |
|---|---|---|
| DT-1 proveniência | aberta | fechável por `sim/tools/verify_primary.py` |
| Validação | 2 totais agregados | **aresta por aresta** contra o primário |
| Quebra por neurópilo | indisponível | disponível (habilita v2) |
| Coordenadas por sinapse | indisponível | disponível (habilita v2) |

### AD-12 — Restrição de memória: nada de leitura integral

A VM Linux tem **3,8 GB de RAM**. O `flywire_synapses_783.feather` tem **9,5 GB**.

**Regra:** todo acesso a arquivos primários grandes usa `pyarrow.feather.read_table`
com `memory_map=True` **e seleção explícita de colunas**, ou iteração por record
batches. `read_feather()` sem colunas derruba o processo.

Isso não é otimização — é a diferença entre o pipeline rodar e não rodar. Vale também
para a v2: qualquer passo que precise das coordenadas processa por lote e escreve
resultado parcial, nunca acumula em memória.

Disco no volume do usuário: **35 GB livres de 475 GB (93% em uso)** no momento da
decisão. Margem suficiente, mas não sobra para uma segunda cópia do conjunto.
