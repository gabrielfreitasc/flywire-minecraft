# FlyWire on Minecraft

Simulação do conectoma da *Drosophila melanogaster* (FlyWire, materialização 783)
encarnada em um mob do Minecraft, para estudar o comportamento das sinapses in situ.

**Escopo da v1:** subcircuito ocelar — 625 neurônios, 2.981 conexões, cadeia
sensório-motora completa (273 fotorreceptores → 260 interneurônios → 92 descendentes).

## Stack

| Camada | Tecnologia | Por quê |
|---|---|---|
| Simulação | **Python 3.11** + NumPy/SciPy | Álgebra esparsa; escala de 625 a 139k neurônios sem reescrita |
| Persistência | **DuckDB + Parquet** | Conectoma estático em Parquet; spike trains em DuckDB. Sem servidor |
| Encarnação | **Java** — plugin Paper/Spigot | Único caminho para controlar um mob nativo server-side |
| Ponte | Socket TCP, protocolo JSON-lines | Desacopla ciência de jogo |
| Ambiente | **Docker** (só o simulador) | Servidor Minecraft roda nativo no Windows |

A separação Python/Java é deliberada: o simulador roda e se valida **sem o Minecraft
aberto**. O jogo é um consumidor do vetor motor, não o dono da simulação.

## Estrutura

```
flywire-minecraft/
├── docs/            Documentação viva — fases, arquitetura, regras, decisões
│   └── adr/         Architecture Decision Records
├── data/
│   ├── raw/         ← DEPOSITE AQUI os arquivos do mapeamento neural
│   ├── interim/     Artefatos intermediários (gerados)
│   └── processed/   Subcircuito pronto para simulação (gerado)
├── sim/             Simulador Python
│   ├── src/flywire_sim/
│   └── tests/
├── plugin/          Plugin Java (a partir da Fase 3)
└── .claude/         Convenções do projeto para agentes
```

## Começando

1. Deposite `Connectivity_783.parquet` e `Supplemental_file1_neuron_annotations.tsv`
   em `data/raw/` (veja `data/raw/README.md`).
2. `cd sim && docker compose up --build`
3. `python -m flywire_sim.ingest` gera `data/processed/`.

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/00-visao-geral.md`](docs/00-visao-geral.md) | O que é, por que existe, o que não é |
| [`docs/01-camada-de-dados.md`](docs/01-camada-de-dados.md) | Fontes, validação, extração do subcircuito |
| [`docs/02-arquitetura.md`](docs/02-arquitetura.md) | Camadas, contratos, protocolo da ponte |
| [`docs/03-roadmap-fases.md`](docs/03-roadmap-fases.md) | Fases F0–F5, entregáveis, critérios de saída |
| [`docs/04-regras-de-negocio.md`](docs/04-regras-de-negocio.md) | RN-01…RN-08, as regras do domínio |
| [`docs/adr/`](docs/adr/) | Decisões arquiteturais registradas |

## Licença e citação

Dados sob CC-BY-4.0. Ao publicar qualquer resultado, citar:
Dorkenwald et al. 2024, *Nature* 634:124 · Schlegel et al. 2024 · Shiu et al. 2024.
