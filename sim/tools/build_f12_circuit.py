"""Extrai o subcircuito thermo (calor/frio) para data/processed/thermo/ (F12, AD-23).

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
