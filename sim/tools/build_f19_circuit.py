"""Extrai o subcircuito path_integration (PFN) para data/processed/path_integration/ (F19, AD-28).

Uso:  python tools/build_f19_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pfn[pmad]$"
SEED_CELL_TYPES = {"PFNp", "PFNm", "PFNa", "PFNd"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "path_integration",
        circuit="path_integration",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
