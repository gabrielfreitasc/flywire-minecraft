"""Extrai o subcircuito hygro (chuva) e bristle (toque) para data/processed/{hygro,bristle}/ (F7, AD-17).

Uso:  python tools/build_f7_circuits.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest


def build_all() -> dict[str, dict]:
    hygro = ingest.build(
        pattern="hygro", hops=2, out_dir=C.PROCESSED / "hygro", circuit="hygro"
    )
    bristle = ingest.build(
        pattern="bristle", hops=1, out_dir=C.PROCESSED / "bristle", circuit="bristle"
    )
    return {"hygro": hygro, "bristle": bristle}


if __name__ == "__main__":
    print(json.dumps(build_all(), indent=2, ensure_ascii=False))
