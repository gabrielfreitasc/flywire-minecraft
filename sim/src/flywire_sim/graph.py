"""L1 → L2 — matriz esparsa CSR + vetor de sinal, com os overrides de RN-01 e RN-02."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix

from . import config as C


@dataclass(frozen=True)
class Connectome:
    """Grafo pronto para simulação."""

    W: csr_matrix
    nodes: pd.DataFrame
    sensory: np.ndarray
    output: np.ndarray

    @property
    def n(self) -> int:
        return self.W.shape[0]


def assign_sign(nodes: pd.DataFrame) -> np.ndarray:
    """RN-01 — sinal a partir do neurotransmissor do neurônio PRÉ-sináptico."""
    nt = nodes.top_nt.str.lower().fillna("")
    sign = np.zeros(len(nodes), dtype=np.int8)
    sign[nt.isin(C.EXCITATORY_NT).to_numpy()] = 1
    sign[nt.isin(C.INHIBITORY_NT).to_numpy()] = -1
    return sign


def apply_nt_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-02 — fotorreceptores ocelares são histaminérgicos, logo inibitórios."""
    is_photoreceptor = (
        (nodes.super_class == "sensory") & (nodes.cell_sub_class == "ocellar")
    ).to_numpy()
    sign = sign.copy()
    sign[is_photoreceptor] = C.PHOTORECEPTOR_SIGN
    return sign


def apply_serotonin_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-18: rótulo 'serotonin' em ORN vira +1 e em local do lobo antenal vira -1."""
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    cell_class = nodes.cell_class.fillna("")
    sign = sign.copy()
    sign[is_serotonin & (cell_class == "olfactory").to_numpy()] = C.OLFACTORY_RECEPTOR_SIGN
    sign[is_serotonin & (cell_class == "ALLN").to_numpy()] = C.ANTENNAL_LOBE_LOCAL_NEURON_SIGN
    return sign


def apply_johnston_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-19: órgão de Johnston rotulado 'serotonin' vira colinérgico (+1)."""
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_johnston = nodes.cell_sub_class.fillna("").isin(["wind_gravity", "auditory"]).to_numpy()
    sign = sign.copy()
    sign[is_serotonin & is_johnston] = C.JOHNSTON_ORGAN_SIGN
    return sign


def apply_gustatory_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-21: GRN de açúcar/água rotulada 'serotonin' vira colinérgica (+1)."""
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_sugar_water = (nodes.cell_sub_class.fillna("") == "sugar/water").to_numpy()
    sign = sign.copy()
    sign[is_serotonin & is_sugar_water] = C.GUSTATORY_RECEPTOR_SIGN
    return sign


def known_nt_sign(known_nt: object) -> int | None:
    """AD-29: sinal do texto livre de `known_nt`; None se não houver transmissor rápido inequívoco."""
    if not isinstance(known_nt, str) or not known_nt.strip():
        return None
    tokens = {t.strip().lower() for t in re.split(r"[;,]", known_nt)}
    if tokens & set(C.KNOWN_NT_AMBIGUOUS):
        return None
    signs = {1 for t in tokens if t in C.EXCITATORY_NT} | {-1 for t in tokens if t in C.INHIBITORY_NT}
    return signs.pop() if len(signs) == 1 else None


def apply_known_nt_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """AD-29: transmissor de literatura vence a predição e os overrides por analogia."""
    if "known_nt" not in nodes.columns:
        raise ValueError(
            "nodes sem a coluna `known_nt` (AD-29) — re-rodar a extração do subcircuito "
            "(`python -m flywire_sim.ingest` ou o `tools/build_*` correspondente)"
        )
    known = nodes.known_nt.map(known_nt_sign)
    has = known.notna().to_numpy()
    sign = sign.copy()
    sign[has] = known[has].astype(np.int8).to_numpy()
    return sign


def final_sign(nodes: pd.DataFrame) -> np.ndarray:
    """Cadeia de sinal completa (RN-01 → RN-02/AD-18/19/21 → AD-29). Único lugar onde é montada."""
    sign = apply_nt_overrides(nodes, assign_sign(nodes))
    sign = apply_serotonin_artifact_overrides(nodes, sign)
    sign = apply_johnston_artifact_overrides(nodes, sign)
    sign = apply_gustatory_artifact_overrides(nodes, sign)
    return apply_known_nt_overrides(nodes, sign)


def load(out_dir: Path | None = None) -> Connectome:
    """Carrega um subcircuito extraído por `ingest.build`."""
    out_dir = C.PROCESSED if out_dir is None else out_dir
    nodes = pd.read_parquet(out_dir / "nodes.parquet").set_index("nid", drop=False)
    edges = pd.read_parquet(out_dir / "edges.parquet")

    sign = final_sign(nodes)

    n = len(nodes)
    w = edges.syn.to_numpy(np.float32) * C.SYNAPTIC_GAIN
    w = w * sign[edges.pre_nid.to_numpy()]

    W = csr_matrix(
        (w, (edges.post_nid.to_numpy(), edges.pre_nid.to_numpy())),
        shape=(n, n),
        dtype=np.float32,
    )

    return Connectome(
        W=W,
        nodes=nodes,
        sensory=nodes.index[nodes.role == "sensory"].to_numpy(),
        output=nodes.index[nodes.role == "output"].to_numpy(),
    )
