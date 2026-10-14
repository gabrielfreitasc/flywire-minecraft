"""Extrai o subcircuito olfactory (feromônio) para data/processed/olfactory/ (F17, AD-26).

Uso:  python tools/build_f17_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pheromone$"


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "olfactory",
        circuit="olfactory",
        sensory_only_seed=True,
        exclude_cell_classes={"Kenyon_Cell"},
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
