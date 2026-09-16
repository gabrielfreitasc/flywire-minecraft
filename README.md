# FlyWire on Minecraft

Simulação do conectoma da *Drosophila melanogaster* (FlyWire, materialização 783)
encarnada em um mob do Minecraft, para estudar o comportamento das sinapses in situ.

**Escopo da v1:** subcircuito ocelar — 625 neurônios, 2.981 conexões, cadeia
sensório-motora completa (273 fotorreceptores → 260 interneurônios → 92 descendentes).

**Estado atual (16/09/2026):** F0–F4 concluídas. O circuito roda, se conecta a uma
abelha real via plugin Paper, e o **experimento de lesão confirmou acoplamento real**
(Mann-Whitney p=0,0014 — abelha se move diferente com fotorreceptores normais vs.
silenciados). Ver `docs/03-roadmap-fases.md` para o detalhe de cada fase.

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
│   ├── tests/
│   └── tools/       Scripts de análise/calibração reproduzíveis (não é lixo de sessão)
├── plugin/          Plugin Paper (Java 21, Gradle) — encarnação, funcional desde a F4
├── mc-server/       Servidor Paper de desenvolvimento (gerado; não versionado)
└── .claude/         Convenções do projeto para agentes
```

## Começando

1. Deposite `Connectivity_783.parquet` e `Supplemental_file1_neuron_annotations.tsv`
   em `data/raw/` (veja `data/raw/README.md`).
2. `cd sim && docker compose up --build` — sobe o simulador (porta 8765).
3. `python -m flywire_sim.ingest` gera `data/processed/`.
4. Para testar a encarnação: `cd plugin && ./gradlew build`, copie o `.jar` de
   `build/libs/` para `mc-server/plugins/` e suba um servidor Paper (ver
   `mc-server/README.md` e `plugin/README.md`).

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/00-visao-geral.md`](docs/00-visao-geral.md) | O que é, por que existe, o que não é |
| [`docs/01-camada-de-dados.md`](docs/01-camada-de-dados.md) | Fontes, validação, extração do subcircuito |
| [`docs/02-arquitetura.md`](docs/02-arquitetura.md) | Camadas, contratos, protocolo da ponte |
| [`docs/03-roadmap-fases.md`](docs/03-roadmap-fases.md) | Fases F0–F5, entregáveis, critérios de saída |
| [`docs/04-regras-de-negocio.md`](docs/04-regras-de-negocio.md) | RN-01…RN-09, as regras do domínio |
| [`docs/adr/`](docs/adr/) | Decisões arquiteturais registradas |
| [`plugin/README.md`](plugin/README.md) | Estado do plugin, comandos, spikes técnicos |
| [`mc-server/README.md`](mc-server/README.md) | Como subir o servidor de desenvolvimento |

## Licença e citação

Dados sob CC-BY-4.0. Ao publicar qualquer resultado, citar:
Dorkenwald et al. 2024, *Nature* 634:124 · Schlegel et al. 2024 · Shiu et al. 2024.
