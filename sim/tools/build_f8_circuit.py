"""
F8 (planejamento, 23/09/2026) — extrai o subcircuito candidato a sensor de
vento/som: órgão de Johnston, mesma mecânica da F7 (`ingest.select_seed`/
`ingest.build`, AD-17).

**Semente:** 874 neurônios `cell_sub_class` em {"wind_gravity", "auditory"}
(`super_class == "sensory"`, `nerve == "AN"` — nervo antenal, órgão de
Johnston de verdade, bate com a nomenclatura JO-* de Kamikouchi et al. 2009
/ Yorozu et al. 2009, duas zonas funcionais: vento/gravidade via deflexão da
arista, e som/canção de corte via vibração antenal). Achado real, não
inventado — mesmos 874 neurônios já identificados e DELIBERADAMENTE
excluídos da semente do `bristle` na F7 (ver
`sim/tools/build_f7_circuits.py`, "não confundir com órgão de Johnston").

**Cuidado ao escolher o padrão:** o prefixo `cell_type` "JO-" sozinho pega
1.103 neurônios, não 874 — inclui um terceiro grupo (`cell_sub_class ==
"grooming"` ou vazio, ex.: JO-FV/JO-FD1/JO-FD2) que não é vento/som.
Padrão usado aqui (`wind_gravity|auditory`, regex OR — `select_seed` usa
`str.contains` com regex habilitado por padrão) pega exatamente os 874,
não o superconjunto.

**Alcance de salto testado (23/09/2026):** 1 salto dá 1.740 nós, 136
descendentes — mais que os 110 do bristle e os 92 do ocelar, sem precisar
abrir mão de mais saltos (2 saltos deu erro técnico não investigado,
`IntCastingNaNError`, irrelevante já que 1 salto é suficiente, mesmo
padrão do bristle que também não precisou de 2 saltos).

**Escreve em `data/processed/johnston/`** — nunca em `data/processed/`
direto (colidiria com o ocelar; erro real cometido e corrigido nesta
sessão ao testar sem `out_dir` explícito — ver
`docs/03-roadmap-fases.md` F8).

Uso:  python tools/build_f8_circuit.py   (a partir de sim/, venv ativo)

Isto é só L0→L1 (extração + validação de contagem). L2/L3 (sinal RN-01,
override tipo RN-02 se necessário, calibração de bias/ruído RN-09) é o
próximo passo, não feito aqui.
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = "wind_gravity|auditory"


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN, hops=1, out_dir=C.PROCESSED / "johnston", circuit="johnston"
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
