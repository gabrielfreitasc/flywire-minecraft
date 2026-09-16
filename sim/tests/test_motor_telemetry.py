"""
Testes de motor.py e telemetry.py. Ver docs/03-roadmap-fases.md (F2).

Não testam semântica comportamental — RN-08 (mapeamento descendente ->
comportamento) segue em aberto, precisa de curadoria. Testam só o mecanismo:
agrupamento, normalização, e que o esquema DuckDB grava/lê o que promete.
"""
from __future__ import annotations

import numpy as np
import pytest

from flywire_sim import graph
from flywire_sim.motor import (
    MotorDecoder,
    group_by_cell_type_prefix,
    group_by_published_behavior,
)
from flywire_sim.telemetry import Recorder, top_cell_types


@pytest.fixture(scope="session")
def connectome() -> graph.Connectome:
    return graph.load()


def test_motor_groups_cover_all_descendants(connectome):
    """RN-08 provisório — todo descendente cai em exatamente um grupo por prefixo."""
    groups = group_by_cell_type_prefix(connectome)
    total = sum(len(nids) for nids in groups.values())
    assert total == len(connectome.output)
    all_nids = np.concatenate(list(groups.values()))
    assert len(all_nids) == len(set(all_nids.tolist()))  # sem sobreposição


def test_motor_decode_range(connectome):
    """decode() nunca sai do range (-1, 1) — tanh satura, não estoura."""
    motor = MotorDecoder(connectome)
    rng = np.random.default_rng(0)
    for t_ms in range(1, 101):
        spikes = rng.random(connectome.n) < 0.2  # taxa alta o bastante pra saturar
        motor.push(t_ms, spikes)
    vec = motor.decode()
    expected_keys = (
        set(motor.groups)
        | {"phototaxis", "locomotion_drive"}
        | set(group_by_published_behavior(connectome))
    )
    assert set(vec) == expected_keys
    assert all(-1.0 < v < 1.0 for v in vec.values())


def test_published_behavior_groups_are_real_types(connectome):
    """RN-08 — os 13 tipos com categoria publicada existem de fato no subcircuito."""
    groups = group_by_published_behavior(connectome)
    assert set(groups) == {
        "fast_locomotion",
        "broad_locomotion",
        "anterior_movements",
        "wing_abdomen_movements",
    }
    total = sum(len(nids) for nids in groups.values())
    assert total == 29  # 13 tipos, 29 neurônios — conferido manualmente contra o dado
    assert total < len(connectome.output)  # cobertura parcial, não fabricar o resto


def test_motor_decode_empty_history_is_zero(connectome):
    motor = MotorDecoder(connectome)
    vec = motor.decode()
    assert all(v == 0.0 for v in vec.values())


def test_telemetry_roundtrip(tmp_path, connectome):
    """Esquema runs/spikes/stimuli/motor_frames grava e lê de volta."""
    db_path = tmp_path / "test_runs.duckdb"
    spikes = np.zeros(connectome.n, dtype=bool)
    spikes[[0, 1, 2]] = True

    with Recorder(db_path=db_path) as rec:
        rec.log_stimulus(0, connectome.sensory[:5], 2.0)
        rec.log_spikes(1, spikes)
        rec.log_motor_frame(1, {"DNp": 0.5, "DNg": -0.1})
        run_id = rec.run_id

    df = top_cell_types(run_id, db_path=db_path)
    assert len(df) > 0
    assert {"cell_type", "disparos"} <= set(df.columns)
