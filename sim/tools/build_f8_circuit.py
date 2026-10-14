"""Extrai o subcircuito johnston (vento/som) para data/processed/johnston/ (F8).

Uso:  python tools/build_f8_circuit.py   (a partir de sim/, venv ativo)
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
