# FlyWire on Minecraft

O conectoma real do cérebro da mosca-da-fruta (*Drosophila melanogaster*, FlyWire,
materialização 783) simulado neurônio a neurônio e ligado ao corpo de uma abelha do
Minecraft. O que a abelha sente no jogo (luz, chuva, toque, som, ameaça, comida,
calor) entra como estímulo nos neurônios sensoriais. O que ela faz sai da atividade
dos neurônios descendentes, a fronteira motora do cérebro.

O projeto pergunta se circuitos extraídos do conectoma, sem nenhum ajuste para
"parecer comportamento", produzem respostas mensuráveis num corpo. Nada é
afirmado sem **teste de lesão**: silenciar o circuito tem de mudar o comportamento,
com estatística, na mesma sessão e na mesma origem.

---

## Sumário

- [O estudo](#o-estudo)
- [O que já foi construído](#o-que-já-foi-construído)
- [Arquitetura](#arquitetura)
- [Pré-requisitos](#pré-requisitos)
- [Rodando localmente](#rodando-localmente)
- [Rodando um experimento](#rodando-um-experimento)
- [Estrutura do repositório](#estrutura-do-repositório)
- [Documentação](#documentação)
- [Como contribuir](#como-contribuir)
- [Referências bibliográficas](#referências-bibliográficas)
- [Licença e citação](#licença-e-citação)

---

## O estudo

**Pergunta original (v1).** O circuito ocelar real, alimentado pela luz do Minecraft e
ligado aos seus 92 neurônios descendentes, produz uma resposta distinguível de ruído?

**Método.**

1. **Dados.** Conectividade sináptica (15 milhões de pares, 54,5 milhões de sinapses)
   e anotação de tipos celulares do FlyWire. Os totais são conferidos contra o
   artigo antes de qualquer uso.
2. **Extração.** Um subcircuito por modalidade: semente sensorial, saltos sinápticos
   (≥5 sinapses) até os descendentes.
3. **Sinal.** Excitatório ou inibitório pelo neurotransmissor do neurônio
   pré-sináptico. A predição de Eckstein et al. é corrigida pelo transmissor
   confirmado em literatura sempre que ele existe.
4. **Dinâmica.** Modelo *leaky integrate-and-fire* (LIF) a dt = 1 ms, com corrente
   tônica e ruído (subcircuitos isolados ficam silenciosos sem isso).
5. **Decodificação.** Taxa de disparo dos descendentes vira um canal motor em
   [-1, 1], separando quem o estímulo excita de quem ele inibe.
6. **Corpo.** Um plugin do servidor Paper lê os sensores da abelha a 20 Hz, manda à
   simulação e aplica a velocidade resultante.
7. **Falsificação.** Para cada circuito ligado a comportamento, um experimento de
   lesão sorteia trial a trial abelha normal × lesionada, e o resultado só conta se
   Welch e Mann-Whitney concordam.

**Regra que guia tudo:** se o comportamento não emerge, não se ajusta a simulação
para produzi-lo. Ajusta-se a hipótese ou registra-se o resultado negativo.

## O que já foi construído

As fases F0–F30 estão concluídas (detalhe em
[`docs/03-roadmap-fases.md`](docs/03-roadmap-fases.md)). Dez circuitos rodam em
paralelo, um processo por circuito.

| Circuito | Sensor no jogo | Neurônios | Canal → comportamento | Validação por lesão |
|---|---|---|---|---|
| `main` (ocelar) | luz do bloco | 625 | `phototaxis`: velocidade de voo | ✅ Mann-Whitney p=0,0014 (F4), replicado p=0,0002 |
| `bristle` | dano, aproximação de mob/jogador | 1.865 | `grooming`: pousa e se limpa | ✅ p=0,0025 (F7) |
| `hygro` | chuva | 5.638 | `hygrotaxis`: busca abrigo | ✅ p=0,00019 (F7) |
| `johnston` | explosão, mob hostil, música | 1.740 | `startle`: acorda do sono | ✅ Welch = MW p=0,0003 (F15) |
| `escape` | ameaça se aproximando (looming), dano | 768 | `escape_drive`: fuga | ✅ dano: p=0,00011/0,00049; looming: p<0,00001/0,00018 (F14) |
| `taste` | comida por perto | 278 | `appetite`: vai até a comida | ✅ p≈0 (F14) |
| `thermo` | lava/fogo/magma; gelo, neve, bioma frio | 4.852 | `thermal`: foge de calor perigoso | ✅ p=0,00114/0,00002 (F14), margem reduzida pela AD-29 |
| `olfactory` | — | 4.564 | `pheromone`: telemetria | sem sensor no jogo |
| `aggression` | — | 384 | `aggression`: telemetria | sem sensor no jogo |
| `path_integration` | — | 2.572 | `path_integration`: telemetria | sem sensor no jogo |

**Estados de engenharia (não neurais, por achado negativo documentado).**

- **Fome/energia** (`EnergyTracker`, AD-22): os neurônios neurossecretores sinalizam
  por hormônio, não por sinapse, e o conectoma não captura isso.
- **Sono noturno** (`SleepState`, AD-24): o circuito de sono testado não muda os
  descendentes. Os gatilhos de despertar vêm de circuitos reais (johnston, escape,
  dano).
- **Saciedade e busca ativa por comida** (F21, F30).

**Outros resultados medidos.**

- A resposta à luz **não é monotônica**: o pico fica em luz baixa (Kruskal-Wallis
  p=0,00003).
- O canal `yaw_steering` gira a abelha para o lado previsto pela anatomia (p=0,00028).
- A IA nativa da abelha mascarava efeitos. Removê-la pela Mob Goal API do Paper
  reduziu o desvio entre trials de até 8 blocos para 0,22.
- **Regra de sinal validada** contra ground truth de literatura: 90/96 tipos
  independentes do treino com sinal certo e nenhum invertido. Desde a AD-29 o
  transmissor confirmado em literatura prevalece (95/96).

**O que não está provado.** Que o comportamento seja "orientação" ou
"estabilização" no sentido etológico; que o modelo seja biofisicamente realista (LIF,
peso = contagem de sinapses, sem neuromodulação); qualquer conclusão sobre a mosca
real. O corpo é uma abelha com física de jogo. A lista completa está em
[`docs/05-resultados.md`](docs/05-resultados.md).

## Arquitetura

```
 data/raw  ──►  ingest (L0–L1)  ──►  graph: matriz com sinal (L2)  ──►  engine LIF (L3)
                                                                            │ disparos
                                                                            ▼
 abelha no Minecraft (L6)  ◄──  plugin Paper  ◄── TCP/JSON-lines ──  decodificador motor (L4–L5)
            │ sensores, 20 Hz                                               ▲
            └───────────────────────────────────────────────────────────────┘
```

| Camada | Tecnologia | Por quê |
|---|---|---|
| Simulação | Python 3.11, NumPy, SciPy | Álgebra esparsa; escala de centenas a milhares de neurônios sem reescrita |
| Persistência | Parquet + DuckDB | Conectoma estático em Parquet; disparos em DuckDB, sem servidor de banco |
| Encarnação | Java 21, plugin Paper 1.21.1 | Único jeito de controlar um mob nativo do lado do servidor |
| Ponte | TCP, JSON-lines | Desacopla ciência e jogo; o simulador vive sem o Minecraft |
| Ambiente | Docker (só o simulador) | O servidor Minecraft roda nativo |

O tick do jogo **nunca espera** o simulador: cada lado roda no próprio relógio e o
jogo aplica o último vetor motor disponível (RN-06). Contratos e protocolo estão em
[`docs/02-arquitetura.md`](docs/02-arquitetura.md).

## Pré-requisitos

| Item | Versão | Para quê |
|---|---|---|
| Python | ≥ 3.11 | Simulador, extração, análise |
| Docker Desktop | qualquer recente | Opcional: rodar o simulador em contêiner |
| JDK | ≥ 21 | Compilar o plugin (o Gradle wrapper já vem no repositório) |
| Servidor Paper | 1.21.1 (build 133 testado) | Rodar o plugin |
| Minecraft Java Edition | 1.21.1 | Entrar no mundo e ver a abelha |
| RAM | 8 GB mínimo, 16 GB recomendado | A extração lê a conectividade inteira de uma vez |
| Disco | ~200 MB | Arquivos de origem principais (o primário do Zenodo, opcional, tem 10,6 GB) |

## Rodando localmente

### 1. Dados de origem

Baixe para `data/raw/`, **sem renomear** (detalhes e checagens em
[`data/raw/README.md`](data/raw/README.md)):

| Arquivo | Onde |
|---|---|
| `Connectivity_783.parquet` | [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model) (idêntico ao primário do Zenodo, conferido aresta por aresta) |
| `Supplemental_file1_neuron_annotations.tsv` | [flyconnectome/flywire_annotations](https://github.com/flyconnectome/flywire_annotations), pasta `supplemental_files/`, commit `17fc5772` |
| `gt_data.csv`, `202405-starting_gt_data.csv` (opcional) | [flyconnectome/drosophila_neurotransmitters](https://github.com/flyconnectome/drosophila_neurotransmitters), commit `a9417412`. Só para validar a regra de sinal |

`data/raw/` é imutável: nada no código escreve ali.

### 2. Ambiente Python e testes

```bash
cd sim
python -m venv .venv
# Windows: .venv\Scripts\activate    Linux/macOS: source .venv/bin/activate
pip install -e ".[dev]"
pytest -q
ruff check .
```

### 3. Extração dos circuitos

A partir de `sim/`, com o venv ativo:

```bash
python -m flywire_sim.ingest          # circuito ocelar (main); recusa rodar se os totais não baterem
python tools/build_f7_circuits.py     # hygro e bristle
python tools/build_f8_circuit.py      # johnston
python tools/build_f9_circuit.py      # escape
python tools/build_f10_circuit.py     # taste
python tools/build_f12_circuit.py     # thermo
python tools/build_f17_circuit.py     # olfactory
python tools/build_f18_circuit.py     # aggression
python tools/build_f19_circuit.py     # path_integration
```

Cada um grava `nodes.parquet`, `edges.parquet` e `manifest.json` em
`data/processed/<circuito>/`. Para conferir que cada circuito responde ao seu
estímulo: `python tools/<circuito>_calibration_check.py` (o do ocelar é
`tools/calibration_check.py`).

### 4. Simulador

```bash
cd sim
docker compose up -d --build                  # com Docker
# ou, sem Docker, com o venv ativo:
python -m flywire_sim.server
```

A ponte escuta em `localhost:8765`. Para testar sem o Minecraft:
`python tools/fake_minecraft_client.py --duration 10`.

Telemetria (opcional): defina `FLYWIRE_RECORD_TELEMETRY` como `1` (só o ocelar),
`main,taste` ou `all` antes de subir o simulador. Grava um arquivo DuckDB por
circuito em `data/processed/`.

### 5. Plugin e servidor Minecraft

```bash
cd plugin
./gradlew build                 # Windows: .\gradlew.bat build
```

Copie `plugin/build/libs/flywire-bee-0.1.0.jar` para `mc-server/plugins/`, baixe o
Paper 1.21.1 de [papermc.io](https://papermc.io/downloads/paper) para `mc-server/`
e suba **depois** do simulador:

```bash
cd mc-server
java -jar paper-1.21.1-133.jar --nogui
```

Conecte em `localhost` com o cliente 1.21.1. O `mc-server/README.md` explica
`online-mode`, o EULA e como rodar sem console.

### 6. Dentro do jogo

Os comandos exigem op (`op <seu_nome>` no console do servidor).

| Comando | Efeito |
|---|---|
| `/flywirebee give` | Dá o item que cria a abelha controlada pelo conectoma |
| `/flywirebee control start` / `stop` | Liga/desliga o laço sensor → simulação → movimento |
| `/flywirebee goals off` | Remove a IA nativa que compete com o circuito (recomendado em experimento) |
| `/flywirebee visualize on` | Partículas coloridas por circuito ativo |
| `/flywirebee goto <x> <y> <z>` | Teleporta a abelha |
| `/flywirebee ignoreplayer on` | Faz os sensores ignorarem o jogador |
| `/flywirebee mute <grupo>` / `unmute` / `stimulate` | Silencia ou estimula grupos de descendentes do ocelar |

## Rodando um experimento

Todo experimento sorteia as condições trial a trial, na mesma origem, e grava um CSV
na pasta do plugin (`mc-server/plugins/FlywireBee/`):

```
/flywirebee goals off
/flywirebee control start
/flywirebee <experimento> [trials=20] [segundosPorTrial=10] [x y z]
```

| Experimento | Circuito testado |
|---|---|
| `lesion` | ocelar (fotorreceptores) |
| `touchlesion`, `hygrolesion`, `johnstonlesion` | bristle, hygro, johnston |
| `escapelesion`, `loominglesion` | escape, com dano ou com looming |
| `tastelesion`, `thermolesion` | taste, thermo |
| `daynight`, `doseresponse`, `validateyaw` | luz dia/noite, dose-resposta de luz, sentido do giro |

Análise, a partir de `sim/`:

```bash
python tools/lesion_analysis.py ../mc-server/plugins/FlywireBee/lesion_experiment.csv
```

Antes de medir: confira que não está chovendo (`/weather clear`; a busca de abrigo
domina os outros canais) e fique a menos de ~160 blocos da abelha (fora da distância
de simulação o servidor não aplica física a ela). As outras armadilhas medidas estão
em [`CONVENCOES.md`](CONVENCOES.md).

## Estrutura do repositório

```
flywire-minecraft/
├── CONVENCOES.md        Regras de código, de dados e armadilhas medidas — ler antes de contribuir
├── docs/
│   ├── 00-visao-geral.md … 05-resultados.md
│   └── adr/             Registro de decisões (AD-01 … AD-29)
├── data/
│   ├── raw/             Arquivos de origem (imutável, não versionado)
│   ├── interim/         Intermediários gerados
│   └── processed/       Circuitos extraídos e telemetria (gerado)
├── sim/                 Simulador Python
│   ├── src/flywire_sim/ ingest, graph, neuron, engine, *_motor, server, telemetry, config
│   ├── tests/           Regras de negócio, engine, ponte, telemetria
│   └── tools/           Extração, calibração, análise de experimentos, verificação de dados
├── plugin/              Plugin Paper (Java 21, Gradle): sensores, laço de controle, experimentos
└── mc-server/           Servidor Paper de desenvolvimento (gerado; só a configuração é versionada)
```

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/00-visao-geral.md`](docs/00-visao-geral.md) | O que o projeto é, o que não é, critério de falsificação |
| [`docs/01-camada-de-dados.md`](docs/01-camada-de-dados.md) | Fontes, validação, extração de subcircuitos |
| [`docs/02-arquitetura.md`](docs/02-arquitetura.md) | Camadas, relógio, contratos, protocolo da ponte |
| [`docs/03-roadmap-fases.md`](docs/03-roadmap-fases.md) | F0–F30: o que foi feito, medido e encontrado em cada fase |
| [`docs/04-regras-de-negocio.md`](docs/04-regras-de-negocio.md) | RN-01 a RN-09: as regras científicas do domínio |
| [`docs/05-resultados.md`](docs/05-resultados.md) | O que está validado, o que não está, dívida técnica |
| [`docs/adr/README.md`](docs/adr/README.md) | Decisões de arquitetura e de modelagem |
| [`plugin/README.md`](plugin/README.md) | Plugin: funcionamento, sensores, prioridade de decisão, comandos, experimentos |
| [`plugin/HISTORICO.md`](plugin/HISTORICO.md) | Diário do plugin F3–F30: decisões, bugs em servidor real, medições |
| [`mc-server/README.md`](mc-server/README.md) | Servidor de desenvolvimento |
| [`data/raw/README.md`](data/raw/README.md) | Proveniência e checagens dos dados de origem |

## Como contribuir

- Leia [`CONVENCOES.md`](CONVENCOES.md) e
  [`docs/04-regras-de-negocio.md`](docs/04-regras-de-negocio.md) antes de mexer.
- **Regra de negócio muda com ADR** em `docs/adr/`. Não deixe um limiar alterado
  "só para testar".
- **Resultado sem lesão não é resultado.** Circuito novo ligado a comportamento
  precisa de experimento de lesão.
- Constante científica vive em `sim/src/flywire_sim/config.py` com a fonte apontada.
  Histórico e medições vão para `docs/`, não para comentários.
- Antes de abrir PR: `pytest -q`, `ruff check .` e `./gradlew build`.

## Referências bibliográficas

Todas as fontes usadas no projeto, agrupadas pelo papel que tiveram. DOIs, volumes e
páginas aparecem só onde foram registrados durante o trabalho; confira o restante
na fonte antes de citar em publicação.

### Conectoma, anotação e modelo de cérebro

- Dorkenwald, S. et al. (2024). Neuronal wiring diagram of an adult brain. *Nature* 634, 124. Base de conectividade; totais usados na validação do ingest.
- Schlegel, P. et al. (2024). Whole-brain annotation and multi-connectome cell typing of *Drosophila*. *Nature*. Anotação de tipos celulares e `known_nt`.
- Matsliah, A. et al. (2024). Neuronal parts list and wiring diagram for a visual system. *Nature*. Tipos celulares do sistema visual (via Codex).
- Zheng, Z. et al. (2018). A complete electron microscopy volume of the brain of adult *Drosophila melanogaster*. *Cell*. Volume FAFB sobre o qual o FlyWire foi construído.
- Eckstein, N., Bates, A. S. et al. (2024). Neurotransmitter classification from electron microscopy images at synaptic sites in *Drosophila melanogaster*. *Cell*. Predição `top_nt` (RN-01).
- Shiu, P. K. et al. (2024). A *Drosophila* computational brain model reveals sensorimotor processing. *Nature*. Modelo LIF de referência e espelho dos dados.
- Bates, Phelps, Kim, Yang et al. (2026). Distributed control circuits across a brain-and-cord connectome (BANC). *Nature*. DOI 10.1038/s41586-026-10735-w; preprint DOI 10.1101/2025.07.31.667571. Função dos descendentes (AD-15).
- Tastekin et al. (2026). Anotações adicionadas a `flyconnectome/flywire_annotations` depois da versão fixada; registrado, não incorporado.

### Dinâmica de rede

- Brunel, N. (2000). Dynamics of sparsely connected networks of excitatory and inhibitory spiking neurons. *Journal of Computational Neuroscience*. Corrente tônica e ruído no LIF (RN-09, AD-13).

### Neurotransmissores e sinal sináptico

- Yasuyama, K. & Salvaterra, P. M. (1999). Localization of choline acetyltransferase-expressing neurons in *Drosophila* nervous system. *Microscopy Research and Technique*. Identidade colinérgica de neurônios sensoriais (AD-18/19/21).
- Kitamoto, T. et al. (1995). *Journal of Neurobiology* 28, 70–81. Expressão de ChAT no órgão de Johnston (AD-19).
- Barbara et al. (2005). ORNs colinérgicos (AD-18).
- Schlegel, P. et al. (2021). Information flow, cell types and stereotypy in a full olfactory connectome. *eLife*. Locais do lobo antenal GABAérgicos (AD-18).
- Shang, Y., Claridge-Chang, A., Sjulson, L., Pypaert, M. & Miesenböck, G. (2007). Excitatory local circuits and their implications for olfactory processing in the fly antennal lobe. *Cell*. Locais do lobo antenal colinérgicos (AD-29).
- Roy et al. (2007) e Dacks et al. (2009). Neurônio serotonérgico CSD do lobo antenal (RN-01a).
- Davis, F. P. et al. (2020). A genetic, genomic, and computational resource for exploring neural circuit function. *eLife*. Fotorreceptores histaminérgicos (RN-02).
- Sarthy, P. V. (1991). Histamine: a neurotransmitter candidate for *Drosophila* photoreceptors. *Journal of Neurochemistry*. Fotorreceptores histaminérgicos (RN-02).
- Meiselman et al. (2022) e Chen et al. (2015). DNp27 neuromodulatório (RN-01a, RN-08).

### Neurônios descendentes e controle motor

- Namiki, S., Dickinson, M. H., Wong, A. M., Korff, W. & Card, G. M. (2018). The functional organization of descending sensory-motor pathways in *Drosophila*. *eLife*. Anatomia e função dos descendentes (RN-08, AD-14).
- Cande, J. et al. (2018). Optogenetic dissection of descending behavioral control in *Drosophila*. *eLife*. DOI 10.7554/eLife.34275. Comportamento por tipo de descendente (RN-08, AD-14).
- Suver et al. (2016). *Journal of Neuroscience*. DOI 10.1523/JNEUROSCI.2277-16.2016. DNp22 (DNOVS1) ocelar.
- Sapkal, N. et al. (2024). *Nature*. DOI 10.1038/s41586-024-07854-7. DNg75 (cDN1), marcha.
- Feng et al. (2024). *bioRxiv*. DOI 10.1101/2024.06.27.601106. DNa03/DNae003, direção de voo.
- Liessem et al. (2025). DOI 10.1016/j.cub.2022.12.005; 10.1101/2025.12.13.693955. DNg79, pouso.
- Yang et al. (2024). DNb05/DNb06, direção (AD-16).
- Kim et al. (2023). DNp06, decolagem de fuga.
- Cheong, Eichler et al. (2023). DNp05, decolagem de fuga (putativo).

### Visão e fuga por looming

- Schiff, W., Caviness, J. A. & Gibson, J. J. (1962). Persistent fear responses in rhesus monkeys to the optical stimulus of "looming". *Science*.
- Ache, J. M. et al. (2019). Neural basis for looming size and velocity encoding in the *Drosophila* giant fiber escape pathway. *Current Biology*.
- von Reyn, C. R. et al. (2017). Feature integration drives probabilistic behavior in the *Drosophila* escape response. *Neuron*.
- de Vries, S. E. J. & Clandinin, T. R. (2012). Loom-sensitive neurons link computation to action in the *Drosophila* visual system. *Current Biology*.

### Mecanossensação, audição e limpeza

- Kamikouchi, A. et al. (2009). The neural basis of *Drosophila* gravity-sensing and hearing. *Nature*.
- Yorozu, S. et al. (2009). Distinct sensory representations of wind and near-field sound in the *Drosophila* brain. *Nature*.
- Eberl, D. F., Hardy, R. W. & Kernan, M. J. (2000). Genetically similar transduction mechanisms for touch and hearing in *Drosophila*. *Journal of Neuroscience*.
- Seeds, A. M. et al. (2014). A suppression hierarchy among competing motor programs drives sequential grooming in *Drosophila*. *eLife*.
- Hampel, S. et al. (2015). A neural command circuit for grooming movement control. *eLife*.
- Hampel, S. et al. (2017). Simultaneous activation of parallel sensory pathways promotes a grooming sequence in *Drosophila*. *eLife*.

### Umidade e temperatura

- Enjin, A. et al. (2016). Humidity sensing in *Drosophila*. *Current Biology*.
- Frank, D. D. et al. (2017). Early integration of temperature and humidity stimuli in the *Drosophila* brain. *Current Biology*.

### Paladar

- Inagaki, H. K. et al. (2014). Independent, reciprocal neuromodulatory control of sweet and bitter taste sensitivity during starvation in *Drosophila*. *Neuron*.

### Olfato, feromônio e agressão

- Kurtovic, A., Widmer, A. & Dickson, B. J. (2007). A single class of olfactory neurons mediates behavioural responses to a *Drosophila* sex pheromone. *Nature*.
- Wang, L. & Anderson, D. J. (2010). Identification of an aggression-promoting pheromone and its receptor neurons in *Drosophila*. *Nature*.
- Bates, A. S. et al. (2020). Complete connectomic reconstruction of olfactory projection neurons in the fly brain. *Current Biology*.
- Berck, M. E. et al. (2016). The wiring diagram of a glomerular olfactory system. *eLife*.
- Schretter, C. E. et al. (2020). Cell types and neuronal circuitry underlying female aggression in *Drosophila*. *eLife*.
- Deutsch, D. et al. (2020). The neural basis for a persistent internal state in *Drosophila* females. *eLife*.
- Taisz, Galili et al. (2022). *bioRxiv*. DOI 10.1101/2022.05.13.491877. Citação adicional para `pC1d`.

### Sono

- Donlea, J. M. et al. (2011). Inducing sleep by remote control facilitates memory consolidation in *Drosophila*. *Science*.
- Donlea, J. M. et al. (2014). Neuronal machinery of sleep homeostasis in *Drosophila*. *Neuron*.
- Pimentel, D. et al. (2016). Operation of a homeostatic sleep switch. *Nature*.

### Integração de trajetórias

- Lu, J., Behbahani, A. H. et al. (2022). Transforming representations of movement from body- to world-centric space. *Nature*.
- Westeinde, E. A. et al. (2024). Transforming a head direction signal into a goal-oriented steering command. *Nature*.

### Bases de dados e ferramentas

- FlyWire, materialização 783, arquivos primários no Zenodo: DOI 10.5281/zenodo.10676866.
- [FlyWire Codex](https://codex.flywire.ai): tipos consolidados, tags de conectividade, rótulos da comunidade.
- [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model): espelho da conectividade.
- [flyconnectome/flywire_annotations](https://github.com/flyconnectome/flywire_annotations): anotação oficial.
- [flyconnectome/drosophila_neurotransmitters](https://github.com/flyconnectome/drosophila_neurotransmitters): ground truth de neurotransmissores.
- BANC: Harvard Dataverse DOI 10.7910/DVN/7WTH1N e [htem/BANC-project](https://github.com/htem/BANC-project).
- MaleCNS v1.0 (FlyEM/Janelia): consultado como referência cruzada.
- [PaperMC](https://papermc.io) (API do Paper, incluindo a Mob Goal API) e [Minecraft Wiki](https://minecraft.wiki) (mecânica de luz e chuva).

## Licença e citação

Os dados do FlyWire são distribuídos sob CC-BY 4.0. Ao publicar qualquer resultado
derivado deste projeto, cite ao menos Dorkenwald et al. 2024, Schlegel et al. 2024 e
Shiu et al. 2024, além das fontes específicas do circuito usado.
