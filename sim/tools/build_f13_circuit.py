"""Extrai o subcircuito sleep (não usado pelo simulador, AD-24) para data/processed/sleep/ (F13).

Uso:  python tools/build_f13_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^(er5|fb6a|fb6h|fb6i|fb6z)$"
SEED_CELL_TYPES = {"ER5", "FB6A", "FB6H", "FB6I", "FB6Z"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "sleep",
        circuit="sleep",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
