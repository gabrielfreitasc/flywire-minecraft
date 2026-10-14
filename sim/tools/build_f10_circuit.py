"""Extrai o subcircuito taste (paladar) para data/processed/taste/ (F10).

Uso:  python tools/build_f10_circuit.py   (a partir de sim/, venv ativo)
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
