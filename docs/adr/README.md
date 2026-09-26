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

AD-01 a AD-05 estão detalhadas em [`../01-camada-de-dados.md`](../01-camada-de-dados.md).
AD-07 a AD-10 e AD-17 estão detalhadas em [`../02-arquitetura.md`](../02-arquitetura.md).
AD-13, AD-14, AD-15, AD-16, AD-18, AD-19, AD-20 estão detalhadas em
[`../04-regras-de-negocio.md`](../04-regras-de-negocio.md) como RN-09, RN-08, RN-01a, RN-04.

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
