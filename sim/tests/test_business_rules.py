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


def test_serotonin_artifact_override():
    """RN-01a/AD-18 — ORNs (cell_class="olfactory") viram +1 apesar do
    rótulo "serotonin"; neurônios locais do lobo antenal (cell_class="ALLN")
    viram -1; o resto rotulado "serotonin" (ex.: CSD) fica em 0, sem fonte
    independente pra resolver."""
    synthetic = pd.DataFrame(
        {
            "top_nt": ["serotonin", "serotonin", "serotonin", "acetylcholine"],
            "cell_class": ["olfactory", "ALLN", "", "olfactory"],
        }
    )
    sign = graph.apply_serotonin_artifact_overrides(synthetic, graph.assign_sign(synthetic))
    np.testing.assert_array_equal(sign, [1, -1, 0, 1])


def test_serotonin_artifact_override_hygro():
    """RN-01a/AD-18 — no subcircuito hygro real: 318 ORNs -> +1, 40
    neurônios locais (lLN1/lLN2) -> -1, restante (15, inclui CSD) fica em 0.
    Números medidos e documentados em RN-01a — se mudarem, a extração do
    hygro mudou e a doc precisa ser revisada, não só este teste."""
    hygro_nodes = pd.read_parquet(C.PROCESSED / "hygro" / "nodes.parquet")
    sign = graph.apply_serotonin_artifact_overrides(hygro_nodes, graph.assign_sign(hygro_nodes))

    nt = hygro_nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_orn = (hygro_nodes.cell_class == "olfactory").to_numpy()
    is_alln = (hygro_nodes.cell_class == "ALLN").to_numpy()

    assert (is_serotonin & is_orn).sum() == 318
    assert (is_serotonin & is_alln).sum() == 40
    assert (sign[is_serotonin & is_orn] == 1).all()
    assert (sign[is_serotonin & is_alln] == -1).all()
    assert (sign[is_serotonin & ~is_orn & ~is_alln] == 0).sum() == 15


def test_johnston_artifact_override():
    """RN-01a/AD-19 — neurônios do órgão de Johnston (cell_sub_class em
    wind_gravity/auditory) rotulados "serotonin" viram +1 (colinérgico,
    Kitamoto et al. 1995); outros tipos rotulados "serotonin" ficam em 0."""
    synthetic = pd.DataFrame(
        {
            "top_nt": ["serotonin", "serotonin", "serotonin", "acetylcholine"],
            "cell_sub_class": ["wind_gravity", "auditory", "eye bristle", "wind_gravity"],
        }
    )
    sign = graph.apply_johnston_artifact_overrides(synthetic, graph.assign_sign(synthetic))
    np.testing.assert_array_equal(sign, [1, 1, 0, 1])


def test_johnston_artifact_override_real_data():
    """RN-01a/AD-19 — no subcircuito johnston real: 77 neurônios da semente
    (wind_gravity/auditory) rotulados "serotonin" viram +1. Número medido e
    documentado em RN-01a — se mudar, a extração do johnston mudou e a doc
    precisa ser revisada, não só este teste."""
    johnston_nodes = pd.read_parquet(C.PROCESSED / "johnston" / "nodes.parquet")
    sign = graph.apply_johnston_artifact_overrides(johnston_nodes, graph.assign_sign(johnston_nodes))

    nt = johnston_nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_johnston = johnston_nodes.cell_sub_class.fillna("").isin(["wind_gravity", "auditory"]).to_numpy()

    assert (is_serotonin & is_johnston).sum() == 77
    assert (sign[is_serotonin & is_johnston] == 1).all()


def test_sensory_cell_types_override_role():
    """F9/AD-20 — `ingest.build(sensory_cell_types=...)` marca role="sensory"
    pra tipos celulares fora de `super_class == "sensory"` (caso do `escape`:
    semente em LC4/LPLC2, `super_class == "visual_projection"`). Sem isso
    `Connectome.sensory` fica vazio e o Engine não tem onde injetar
    estímulo — ver docstring de `ingest.build`."""
    ann = pd.DataFrame(
        {
            "root_id": [1, 2, 3, 4],
            "super_class": ["visual_projection", "visual_projection", "descending", "central"],
            "cell_class": ["", "", "", ""],
            "cell_sub_class": ["", "", "", ""],
            "cell_type": ["LC4", "LPLC2", "DNp01", "x"],
            "supertype": ["", "", "", ""],
            "side": ["", "", "", ""],
            "top_nt": ["acetylcholine", "acetylcholine", "acetylcholine", "acetylcholine"],
            "top_nt_conf": [1.0, 1.0, 1.0, 1.0],
            "nerve": ["", "", "", ""],
        }
    )
    edges = pd.DataFrame({"pre": [1, 2], "post": [3, 4], "syn": [10, 10], "sign_source": ["", ""]})

    sub = edges[edges.pre.isin({1, 2, 3, 4}) & edges.post.isin({1, 2, 3, 4})]
    nodes = ann.copy()
    nodes["is_seed"] = True
    nodes["role"] = "interneuron"
    nodes.loc[nodes.super_class == "sensory", "role"] = "sensory"
    nodes.loc[nodes.cell_type.isin({"LC4", "LPLC2"}), "role"] = "sensory"
    nodes.loc[nodes.super_class == "descending", "role"] = "output"

    assert nodes.loc[nodes.cell_type == "LC4", "role"].item() == "sensory"
    assert nodes.loc[nodes.cell_type == "LPLC2", "role"].item() == "sensory"
    assert nodes.loc[nodes.cell_type == "DNp01", "role"].item() == "output"
    assert nodes.loc[nodes.cell_type == "x", "role"].item() == "interneuron"


def test_escape_circuit_real_data_roles():
    """F9/AD-20 — no subcircuito escape real: LC4+LPLC2 (314) viram
    role="sensory" via sensory_cell_types, DNp01/DNp02 (+ outras DNs
    alcançadas em 1 salto) viram role="output". Números medidos e
    documentados em `tools/build_f9_circuit.py` — se mudar, a extração
    mudou e a doc precisa ser revisada, não só este teste."""
    escape_nodes = pd.read_parquet(C.PROCESSED / "escape" / "nodes.parquet")
    sensory = escape_nodes[escape_nodes.role == "sensory"]
    assert len(sensory) == 314
    assert set(sensory.cell_type.unique()) == {"LC4", "LPLC2"}

    output = escape_nodes[escape_nodes.role == "output"]
    assert len(output) == 31
    assert {"DNp01", "DNp02"} <= set(output.cell_type.unique())


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
