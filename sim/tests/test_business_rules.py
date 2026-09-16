"""
Testes das regras de negócio. Ver docs/04-regras-de-negocio.md.

Os marcados como CRÍTICO protegem correção científica, não só código.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from flywire_sim import config as C
from flywire_sim import graph, ingest
from flywire_sim.neuron import LIFState
from flywire_sim.neuron import step as lif_step


@pytest.fixture(scope="session")
def real_nodes() -> pd.DataFrame:
    return pd.read_parquet(C.PROCESSED / "nodes.parquet")


@pytest.fixture(scope="session")
def real_edges() -> pd.DataFrame:
    return pd.read_parquet(C.PROCESSED / "edges.parquet")


def test_sign_assignment():
    """RN-01 — ACh/DA/OA -> +1; GABA/Glutamato -> -1 (GluCl, inibitório em Drosophila)."""
    synthetic = pd.DataFrame(
        {
            "top_nt": [
                "acetylcholine", "dopamine", "octopamine",
                "gaba", "glutamate",
                "serotonin", "",
            ]
        }
    )
    sign = graph.assign_sign(synthetic)
    np.testing.assert_array_equal(sign, [1, 1, 1, -1, -1, 0, 0])


def test_photoreceptor_override():
    """RN-02 CRÍTICO — fotorreceptores ocelares devem ter sinal -1,
    apesar de virem rotulados como 'serotonin' no dado."""
    synthetic = pd.DataFrame(
        {
            "top_nt": ["serotonin", "acetylcholine", "serotonin"],
            "super_class": ["sensory", "sensory", "sensory"],
            "cell_sub_class": ["ocellar", "ocellar", "ommatidia"],
        }
    )
    sign = graph.apply_nt_overrides(synthetic, graph.assign_sign(synthetic))
    np.testing.assert_array_equal(sign, [-1, -1, 0])


def test_photoreceptor_override_real_data(real_nodes):
    """RN-02 CRÍTICO — no dado real, os 273 fotorreceptores ocelares -> -1."""
    sign = graph.apply_nt_overrides(real_nodes, graph.assign_sign(real_nodes))
    is_photoreceptor = (
        (real_nodes.super_class == "sensory") & (real_nodes.cell_sub_class == "ocellar")
    ).to_numpy()
    assert is_photoreceptor.sum() == 273
    assert (sign[is_photoreceptor] == -1).all()


def test_threshold(real_edges):
    """RN-03 — nenhuma aresta com menos de SYN_THRESHOLD sinapses sobrevive."""
    assert (real_edges.syn >= C.SYN_THRESHOLD).all()


def test_motor_boundary(monkeypatch):
    """RN-04 — nenhum neurônio foi alcançado ATRAVÉS de um descendente."""
    monkeypatch.setattr(C, "HOPS", 2)
    edges = pd.DataFrame({"pre": [1, 2, 3], "post": [2, 3, 4]})
    # 1 -> 2 -> 3 -> 4, com 2 marcado como descendente: a expansão deve
    # parar em 2 e nunca alcançar 3 ou 4, mesmo com HOPS=2.
    keep = ingest.expand(edges, seed={1}, descending={2})
    assert keep == {1, 2}


def test_nid_stability():
    """RN-05 — rodar o ingest duas vezes produz o mesmo mapa root_id -> nid."""
    ingest.build()
    nodes_a = pd.read_parquet(C.PROCESSED / "nodes.parquet")[["root_id", "nid"]]

    ingest.build()
    nodes_b = pd.read_parquet(C.PROCESSED / "nodes.parquet")[["root_id", "nid"]]

    pd.testing.assert_frame_equal(nodes_a, nodes_b)


def test_refractory():
    """RN-07 — nenhum neurônio dispara duas vezes dentro de REFRACTORY_MS."""
    state = LIFState.zeros(1)
    strong_current = np.array([10.0], dtype=np.float32)

    spike_times_ms = []
    for t_ms in range(1, 21):
        spikes = lif_step(state, strong_current, C.DT_MS)
        if spikes[0]:
            spike_times_ms.append(t_ms)

    assert len(spike_times_ms) >= 2, "corrente forte deveria produzir vários disparos"
    gaps = np.diff(spike_times_ms)
    assert (gaps >= C.REFRACTORY_MS).all()
