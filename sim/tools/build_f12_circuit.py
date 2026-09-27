"""
F12 (26/09/2026) — extrai o subcircuito de temperatura (calor/frio):
mesma mecânica de F7-F10 (`ingest.select_seed`/`ingest.build`, AD-17).

**Semente:** 16 TRNs (neurônios receptores de temperatura) em
`cell_class == "thermosensory"`: `heating` (7, `TRN_VP2`) e `cold` (9,
`TRN_VP3a`/`TRN_VP3b`). Os 13 `humid` (`TRN_VP1m`) ficam de fora — são
umidade, domínio do `hygro`. 100% colinérgicos (15/16 ACh, sem artefato de
serotonina tipo RN-01a — checado).

**Alcance de salto (26/09/2026):** 1 salto dá só 2 descendentes (163 nós,
insuficiente pra decodificar); 2 saltos dão 71 descendentes mas arrastam
1.561 neurônios sensoriais NÃO relacionados — por isso
`sensory_only_seed=True` (AD-23): só os 16 TRNs recebem estímulo.

**Escreve em `data/processed/thermo/`** — nunca em `data/processed/` direto.

Uso:  python tools/build_f12_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^(cold|heating)$"


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "thermo",
        circuit="thermo",
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
