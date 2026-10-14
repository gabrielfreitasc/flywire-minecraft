# Architecture Decision Records

Decisões com consequência arquitetural. Uma ADR nunca é editada depois de aceita —
é **substituída** por outra que a supersede. O histórico é o valor.

| # | Decisão | Data | Status |
|---|---|---|---|
| AD-01 | Identidade congelada em `nid` int32 (materialização 783) | 2026-09-09 | Aceita |
| AD-02 | Sinal sináptico derivado do neurotransmissor, versionado como código | 2026-09-09 | Aceita |
| AD-03 | Override de histamina nos fotorreceptores ocelares | 2026-09-09 | Aceita |
| AD-04 | Neurônios descendentes são a fronteira motora | 2026-09-09 | Aceita |
| AD-05 | Zenodo não é bloqueante para a v1; espelho GitHub validado | 2026-09-10 | **Superseded por AD-11** |
| AD-06 | Escopo v1 = subcircuito ocelar, 1 salto, limiar ≥5 | 2026-09-09 | Aceita |
| AD-07 | Python (simulação) + Java (encarnação), separados por socket | 2026-09-15 | Aceita |
| AD-08 | DuckDB + Parquet; sem servidor de banco | 2026-09-15 | Aceita |
| AD-09 | Docker só no simulador; Minecraft nativo | 2026-09-15 | Aceita |
| AD-10 | Acoplamento assíncrono entre engine e tick do jogo | 2026-09-15 | Aceita |
| AD-11 | Fonte primária da Zenodo reincorporada; egresso segue bloqueado para o código | 2026-09-16 | Aceita |
| AD-12 | Leitura de arquivos grandes só por memory-map com colunas selecionadas (RAM 3,8 GB) | 2026-09-16 | Aceita |
| AD-13 | Corrente tônica de base + ruído no LIF (RN-09) | 2026-09-16 | Aceita |
| AD-14 | Curadoria parcial de RN-08 via Namiki et al. 2018 (13/47 tipos) | 2026-09-16 | Aceita |
| AD-15 | Segunda fonte de curadoria de RN-08 via BANC connectome (Bates, Phelps, Kim, Yang et al. 2026) — literatura (+5 tipos) e cluster de conectividade (+33 tipos, epistemicamente mais fraco) | 2026-09-16 | Aceita |
| AD-16 | Canal `yaw_steering` (RN-08) — direção via par bilateral (`side`) dos 4 tipos steering; telemetria só, sem tradução validada pra `MotorMapping.java` | 2026-09-17 | Aceita |
| AD-17 | Múltiplos subcircuitos (F7 — chuva/toque) como engines separados, não grafo único fundido com o ocelar; `ingest.py` generalizado para semente/hops/saída nomeados | 2026-09-19 | Aceita |
| AD-18 | RN-01a — override de 358/373 neurônios "serotonin" do hygro (artefato de classificador): ORN→colinérgico (Yasuyama & Salvaterra 1999), lLN1/lLN2→GABAérgico (Schlegel et al. 2021) | 2026-09-21 | Aceita |
| AD-19 | RN-01a — override de 77/874 neurônios "serotonin" do johnston (F8, vento/som): órgão de Johnston→colinérgico (Kitamoto et al. 1995; Yasuyama & Salvaterra 1999) | 2026-09-23 | Aceita |
| AD-20 | Circuito `escape` (F9, fuga por looming): semente LC4/LPLC2/DNp01/DNp02 fora de `super_class == "sensory"` — `ingest.build` ganha `sensory_cell_types` pra declarar o primeiro estágio de UM subcircuito específico sem mudar o default dos outros; canal `escape_drive` curado por identidade de tipo celular (DNp01+DNp02), não por `topology.group_outputs_by_predicted_sign` | 2026-09-24 | Aceita |
| AD-21 | RN-01a — override de 27/129 neurônios "serotonin" do taste (F10, paladar apetitivo): GRNs de açúcar/água→colinérgico (Yasuyama & Salvaterra 1999); escala própria `TASTE_MOTOR_RATE_SCALE` pro grupo de saída pequeno (mesma lição do escape) | 2026-09-25 | Aceita |
| AD-22 | Fome (F11) NÃO vira circuito LIF — achado negativo real: IPC/Hugin-RG/DH44/ITP (neurônios neurosecretores endócrinos) têm zero arestas de saída acima de `SYN_THRESHOLD` no conectoma inteiro (sinalizam por hormônio, não sinapse). `EnergyTracker.java` é medidor de engenharia puro do lado do plugin, documentado como proxy de jogo — não simula neurônio real, ao contrário de todo canal anterior | 2026-09-26 | Aceita |
| AD-23 | Circuito `thermo` (F12, calor/frio): `ingest.build(sensory_only_seed=True)` — só a SEMENTE vira `role="sensory"`; sensoriais de outras modalidades alcançados por salto viram interneurônios (2 saltos arrastavam 1.561 sensoriais não relacionados). Escala própria `THERMO_MOTOR_RATE_SCALE=60`. O canal `thermal` não distingue calor de frio — o sensor distingue. Também: `ingest.build` agora exclui neurônios sem anotação (`unannotated_dropped` no manifest) | 2026-09-26 | Aceita |
| AD-24 | Sono (F13) NÃO vira circuito LIF — achado negativo real: sementes inibitórias da literatura (ER5 + dFB FB6A/H/I/Z) estimuladas não mudam os descendentes em 2 saltos (p=0,083/p=1,0) e em 3 saltos o efeito é ~3% com passo de ~1,12 ms (inviável em tempo real). `SleepState.java` é estado de engenharia (relógio do mundo), como `EnergyTracker`; os gatilhos de despertar vêm de circuitos reais (johnston `startle`, escape, dano) | 2026-09-26 | Aceita |
| AD-25 | `server.py` vira multiprocessing (F16) — um processo por circuito em vez de um laço único numa thread (RN-06 preservada: acoplamento assíncrono, só a implementação interna muda). Medido antes de decidir: laço único usava 99,6% do orçamento de 1ms/tick; multiprocessing sem controlar threads de BLAS deu só 0,666ms/tick; travando `OPENBLAS_NUM_THREADS=1`/`OMP_NUM_THREADS=1`/`MKL_NUM_THREADS=1` (ANTES de qualquer import que traga numpy/scipy) deu 0,407ms/tick, 59,3% livre. Coordenação só por `multiprocessing.Array` compartilhado (estímulo) + fila `maxsize=1` por circuito (saída) — sem mudar o protocolo TCP/JSON | 2026-09-30 | Aceita |
| AD-26 | Circuito `olfactory` (F17, feromônio): `ingest.expand(exclude=...)`/`ingest.build(exclude_cell_classes=...)` — segunda barreira de BFS, mesmo mecanismo de `descending` (RN-04), por outro motivo: excluir uma via funcional inteira (`Kenyon_Cell`, corpo cogumelo/memória) confirmada por medição (não presumida) como irrelevante pros descendentes alcançados — corta 1.806 nós sem perder nenhum dos 74 descendentes. Semente = 429 ORNs de feromônio (`cell_sub_class=="pheromone"`); escala própria `OLFACTORY_MOTOR_RATE_SCALE=60` | 2026-09-30 | Aceita |
| AD-27 | Circuito `aggression` (F18, corte/agressividade): semente = 10 neurônios `pC1a-e` (literatura: Schretter et al. 2020, Deutsch et al. 2020), fora de `super_class=="sensory"` — mesmo mecanismo `sensory_cell_types` do `escape`/AD-20. Confirmado por medição que NÃO é extensão do `olfactory` (zero overlap entre os descendentes de feromônio e os `pC1`, semente própria). 1 salto já basta (384 nós, 25 descendentes) — o mais raso de todos os circuitos; escala própria `AGGRESSION_MOTOR_RATE_SCALE=60`, grupo inibitório previsto vazio (achado real) | 2026-09-30 | Aceita |
| AD-28 | Circuito `path_integration` (F19, integração de trajetórias): semente = 422 neurônios `PFNp/m/a/d` (literatura: Westeinde et al. 2024; Lu & Behbahani et al. 2022 — "P-FN integra direção+velocidade ao longo do tempo", definição literal de path integration), fora de `super_class=="sensory"`. Achado real: sinal líquido (exc−inh) é NEGATIVO sob estímulo, ao contrário de todos os canais anteriores — `tanh` absorve isso sem problema, só documentado. Escala própria `PATH_INTEGRATION_MOTOR_RATE_SCALE=60`. Diferente de `olfactory`/`aggression`, o estímulo real não seria um evento de mundo e sim self-motion da própria abelha (`ControlLoop.heading`) | 2026-10-01 | Aceita |
| AD-29 | RN-01 — transmissor CONFIRMADO em literatura (coluna `known_nt` da anotação oficial, Schlegel et al. 2024) vence a predição `top_nt` e os overrides por analogia (RN-02, AD-18/19/21); aplicado por último. Achado da DT-2: 1.245 de 69.975 neurônios com `known_nt` mapeável tinham sinal diferente no modelo, incluindo 21 locais do lobo antenal colinérgicos (`lLN1_bc`, `lLN2T_b/c`, `lLN2X03`; Shang et al. 2007) que a AD-18 forçava a −1 (corrige a AD-18 nesses 21, sem supersedê-la). Co-transmissão de sinais opostos, histamina e glicina → sem override. Cadeia de sinal centralizada em `graph.final_sign` (antes copiada em 3 lugares). Ingest passa a carregar `known_nt` nos nós | 2026-10-14 | Aceita |

AD-01 a AD-05 estão detalhadas em [`../01-camada-de-dados.md`](../01-camada-de-dados.md).
AD-07 a AD-10 e AD-17 estão detalhadas em [`../02-arquitetura.md`](../02-arquitetura.md).
AD-13, AD-14, AD-15, AD-16, AD-18, AD-19, AD-20, AD-21 estão detalhadas em
[`../04-regras-de-negocio.md`](../04-regras-de-negocio.md) como RN-09, RN-08, RN-01a, RN-04.
AD-22 está detalhada em [`../03-roadmap-fases.md`](../03-roadmap-fases.md) F11 e na
docstring de `plugin/.../EnergyTracker.java`.
AD-23 está detalhada em [`../03-roadmap-fases.md`](../03-roadmap-fases.md) F12 e na
docstring de `sim/src/flywire_sim/ingest.py::build`. AD-24 está detalhada em F13 e nas
docstrings de `sim/tools/build_f13_circuit.py` e `plugin/.../SleepState.java`.
AD-25 está detalhada em F16 e no topo de `sim/src/flywire_sim/server.py`.
AD-26 está detalhada em F17 e nas docstrings de `sim/tools/build_f17_circuit.py`
e `sim/src/flywire_sim/ingest.py::expand`. AD-27 está detalhada em F18 e na
docstring de `sim/tools/build_f18_circuit.py`. AD-28 está detalhada em F19 e
na docstring de `sim/tools/build_f19_circuit.py`.
AD-29 está detalhada em [`../04-regras-de-negocio.md`](../04-regras-de-negocio.md) RN-01
(seção "Transmissor de literatura") e nas docstrings de `graph.py::known_nt_sign`/`final_sign`.

## Formato para novas ADRs

```
# AD-NN — Título

**Data** · **Status** (Proposta | Aceita | Superseded por AD-MM)

## Contexto
O que forçou a decisão.

## Decisão
O que foi decidido.

## Consequências
O que fica mais fácil, o que fica mais difícil, o que passa a ser proibido.

## Alternativas descartadas
E por quê.
```
