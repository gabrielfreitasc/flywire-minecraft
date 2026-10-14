"""Extrai o subcircuito escape (looming) para data/processed/escape/ (F9, AD-20).

Uso:  python tools/build_f9_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^(lc4|lplc2|dnp01|dnp02)$"
SENSORY_CELL_TYPES = {"LC4", "LPLC2"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=1,
        out_dir=C.PROCESSED / "escape",
        circuit="escape",
        sensory_cell_types=SENSORY_CELL_TYPES,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
