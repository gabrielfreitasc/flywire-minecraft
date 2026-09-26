"""
F10 (25/09/2026) — extrai o subcircuito candidato ao paladar apetitivo:
mesma mecânica de F7/F8/F9 (`ingest.select_seed`/`ingest.build`, AD-17).

**Semente:** 129 GRNs (neurônios gustativos receptores) `cell_sub_class ==
"sugar/water"` (`super_class == "sensory"`, `cell_class == "gustatory"`) —
achado real, não inventado: bate com a descrição do usuário de que a mosca
prova com o CORPO TODO, não só a boca — os tipos celulares incluem sensilas
de perna (`SA_VTV_pro_meso_meta` não incluído aqui, é outro `cell_sub_class`
do mesmo `cell_class`) e labelares (LB3/LB2d, boca). Decisão do usuário
(25/09/2026, via `AskUserQuestion`): só a valência APETITIVA (doce/água) por
enquanto — `bitter`/`low-salt` (aversivo) ficam de fora, sem bloco/item
óbvio no Minecraft pra mapear "comida ruim" ainda.

**RN-01a/AD-21:** dentro do MESMO `cell_type` (LB3, 122/129 da semente),
84 acetilcolina / 27 serotonin / 11 glutamato — mesmo padrão de artefato de
classificador já visto em ORN (AD-18) e órgão de Johnston (AD-19). Override
em `graph.py::apply_gustatory_artifact_overrides` (Yasuyama & Salvaterra
1999, ChAT em quimiorreceptores primários). Os 11 glutamato de LB3 e os 6
de LB2d ficam como o classificador rotulou — sem fonte pra afirmar que
também são artefato.

**Alcance de salto (25/09/2026):** 1 salto dá 278 nós, 2.736 arestas, 14
descendentes.

**Escreve em `data/processed/taste/`** — nunca em `data/processed/` direto
(mesma disciplina de F7/F8/F9).

Uso:  python tools/build_f10_circuit.py   (a partir de sim/, venv ativo)

Isto é só L0->L1 (extração + validação de contagem). L2/L3 (calibração de
bias/ruído RN-09) é o próximo passo, feito em `tools/taste_calibration_check.py`.
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = "sugar/water"


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN, hops=1, out_dir=C.PROCESSED / "taste", circuit="taste"
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
