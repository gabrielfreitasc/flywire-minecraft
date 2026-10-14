"""Extrai o subcircuito aggression (pC1) para data/processed/aggression/ (F18, AD-27).

Uso:  python tools/build_f18_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pc1[a-e]$"
SEED_CELL_TYPES = {"pC1a", "pC1b", "pC1c", "pC1d", "pC1e"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=1,
        out_dir=C.PROCESSED / "aggression",
        circuit="aggression",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
