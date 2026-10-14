"""Testes de motor.py e telemetry.py."""
from __future__ import annotations

import duckdb
import numpy as np
import pytest

from flywire_sim import graph
from flywire_sim.motor import (
    MotorDecoder,
    group_by_cell_type_prefix,
    group_by_connectivity_cluster,
    group_by_published_behavior,
    group_steering_by_side,
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
    assert len(all_nids) == len(set(all_nids.tolist()))


def test_motor_decode_range(connectome):
    """decode() nunca sai do range (-1, 1) — tanh satura, não estoura."""
    motor = MotorDecoder(connectome)
    rng = np.random.default_rng(0)
    for t_ms in range(1, 101):
        spikes = rng.random(connectome.n) < 0.2
        motor.push(t_ms, spikes)
    vec = motor.decode()
    expected_keys = (
        set(motor.groups)
        | {"phototaxis", "locomotion_drive", "yaw_steering"}
        | set(group_by_published_behavior(connectome))
        | {f"conn_{name}" for name in group_by_connectivity_cluster(connectome)}
    )
    assert set(vec) == expected_keys
    assert all(-1.0 < v < 1.0 for v in vec.values())


def test_published_behavior_groups_are_real_types(connectome):
    """RN-08/AD-14+AD-15 — os 18 tipos com categoria publicada (Namiki 2018 + BANC) existem de fato no subcircuito."""
    groups = group_by_published_behavior(connectome)
    assert set(groups) == {
        "fast_locomotion",
        "broad_locomotion",
        "wing_abdomen_movements",
        "steering",
        "escape_takeoff",
        "landing",
        "flight",
        "walking",
        "ocellar",
        "neuromodulatory",
    }
    total = sum(len(nids) for nids in groups.values())
    assert total == 39
    assert total < len(connectome.output)


def test_connectivity_cluster_groups_are_real_types(connectome):
    groups = group_by_connectivity_cluster(connectome)
    assert set(groups) == {
        "flight_steering_1",
        "flight_steering_2",
        "head_orienting",
        "flight_power",
        "walking",
        "postural_control",
        "threat_response",
        "probing",
    }
    total = sum(len(nids) for nids in groups.values())
    assert total == 50
    assert total < len(connectome.output)
    published_nids = {
        nid for nids in group_by_published_behavior(connectome).values() for nid in nids
    }
    connectivity_nids = {nid for nids in groups.values() for nid in nids}
    assert published_nids.isdisjoint(connectivity_nids)


def test_steering_bilateral_pairs_are_balanced(connectome):
    left, right = group_steering_by_side(connectome)
    assert len(left) == 4
    assert len(right) == 4
    assert set(left).isdisjoint(right)
    out_nodes = connectome.nodes.loc[connectome.output]
    left_types = set(out_nodes.loc[left].cell_type)
    right_types = set(out_nodes.loc[right].cell_type)
    assert left_types == right_types == {"DNae003", "DNb05", "DNb06", "DNge070"}


def test_motor_decode_empty_history_is_zero(connectome):
    motor = MotorDecoder(connectome)
    vec = motor.decode()
    assert all(v == 0.0 for v in vec.values())


def test_motor_history_stays_bounded_by_window(connectome):
    motor = MotorDecoder(connectome)
    spikes = np.zeros(connectome.n, dtype=bool)
    for t_ms in range(500):
        motor.push(t_ms, spikes)
    assert len(motor._history) <= 60


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


def test_telemetry_stimulus_logged_only_on_change(tmp_path, connectome):
    db_path = tmp_path / "test_runs.duckdb"
    nids = np.asarray(connectome.sensory[:5])

    with Recorder(db_path=db_path) as rec:
        for t in range(10):
            rec.log_stimulus(t, nids, 2.0)
        rec.log_stimulus(10, nids, 3.0)
        rec.log_stimulus(11, np.array([], dtype=np.int64), 0.0)
        rec.log_stimulus(12, nids, 3.0)
        run_id = rec.run_id

    con = duckdb.connect(str(db_path))
    try:
        rows = con.execute(
            "SELECT t_ms, count(*), min(amplitude) FROM stimuli WHERE run_id = ? GROUP BY 1 ORDER BY 1",
            [run_id],
        ).fetchall()
    finally:
        con.close()
    assert rows == [(0, 5, 2.0), (10, 5, 3.0), (12, 5, 3.0)]
