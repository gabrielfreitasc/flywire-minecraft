"""
Testes de `bristle_motor.py` — curadoria de comportamento (BANC) pro
subcircuito `bristle` (F7/AD-17). Mesmo padrão de
`test_motor_telemetry.py::test_published_behavior_groups_are_real_types` /
`test_connectivity_cluster_groups_are_real_types`, dicionário diferente.

Assume `data/processed/bristle/` já extraído (`python tools/build_f7_circuits.py`),
mesma convenção de `test_business_rules.py` pro circuito ocelar.
"""
from __future__ import annotations

import pytest

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.bristle_motor import (
    BRISTLE_CONNECTIVITY_CLUSTER,
    BRISTLE_NO_DATA,
    BRISTLE_PUBLISHED_DN_BEHAVIOR,
    group_by_connectivity_cluster,
    group_by_published_behavior,
)


@pytest.fixture(scope="session")
def bristle_connectome() -> graph.Connectome:
    return graph.load(C.PROCESSED / "bristle")


def test_published_behavior_groups_are_real_types(bristle_connectome):
    """Os 6 tipos com `cell_function` publicado (BANC) existem de fato no
    subcircuito bristle, todos grooming (achado, não hipótese)."""
    groups = group_by_published_behavior(bristle_connectome)
    assert set(groups) == {"grooming"}
    total = sum(len(nids) for nids in groups.values())
    assert total == 9  # 6 tipos, 9 neurônios (alguns tipos têm >1 neurônio) — conferido contra o dado
    assert total < len(bristle_connectome.output)  # cobertura parcial, não fabricar o resto


def test_connectivity_cluster_groups_are_real_types(bristle_connectome):
    """Os 52 tipos com cluster de conectividade BANC (sem comportamento
    medido) existem de fato no subcircuito bristle."""
    groups = group_by_connectivity_cluster(bristle_connectome)
    total = sum(len(nids) for nids in groups.values())
    assert total == 97  # 52 tipos, 97 neurônios — conferido contra o dado
    assert total < len(bristle_connectome.output)
    # sem sobreposição com o que já tem comportamento medido
    published_nids = {
        nid for nids in group_by_published_behavior(bristle_connectome).values() for nid in nids
    }
    connectivity_nids = {nid for nids in groups.values() for nid in nids}
    assert published_nids.isdisjoint(connectivity_nids)


def test_coverage_accounts_for_all_60_types(bristle_connectome):
    """behavior + cluster + sem-dado cobre exatamente os 60 tipos do bristle
    — nenhum tipo perdido ou contado duas vezes na curadoria."""
    out_types = set(bristle_connectome.nodes.loc[bristle_connectome.output].cell_type)
    curated = set(BRISTLE_PUBLISHED_DN_BEHAVIOR) | set(BRISTLE_CONNECTIVITY_CLUSTER)
    assert curated.isdisjoint(BRISTLE_NO_DATA)
    assert curated | set(BRISTLE_NO_DATA) == out_types
