"""
L1 → L2 — matriz esparsa CSR + vetor de sinal, com os overrides de RN-01 e RN-02.

Esta é a camada onde uma regra errada corrompe tudo a jusante em silêncio.
Os testes de `test_photoreceptor_override` e `test_sign_assignment` são obrigatórios.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix

from . import config as C


@dataclass(frozen=True)
class Connectome:
    """Grafo pronto para simulação. `W[i, j]` = peso de j → i (pós × pré)."""

    W: csr_matrix          # (n, n) float32, já com sinal e ganho aplicados
    nodes: pd.DataFrame    # indexado por nid
    sensory: np.ndarray    # nids dos fotorreceptores
    output: np.ndarray     # nids dos descendentes (RN-04)

    @property
    def n(self) -> int:
        return self.W.shape[0]


def assign_sign(nodes: pd.DataFrame) -> np.ndarray:
    """RN-01 — sinal a partir do neurotransmissor do neurônio PRÉ-sináptico.

    Dale's law: o sinal é propriedade do neurônio que emite, não da conexão.
    """
    nt = nodes.top_nt.str.lower().fillna("")
    sign = np.zeros(len(nodes), dtype=np.int8)
    sign[nt.isin(C.EXCITATORY_NT).to_numpy()] = 1
    sign[nt.isin(C.INHIBITORY_NT).to_numpy()] = -1
    return sign


def apply_nt_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-02 — fotorreceptores ocelares são histaminérgicos, logo inibitórios.

    O classificador de Eckstein et al. tem 6 classes e histamina não está entre elas;
    ele os rotula como serotonina com confiança baixa. Sem este override a entrada
    sensorial inteira entra com o sinal trocado.
    """
    is_photoreceptor = (
        (nodes.super_class == "sensory") & (nodes.cell_sub_class == "ocellar")
    ).to_numpy()
    sign = sign.copy()
    sign[is_photoreceptor] = C.PHOTORECEPTOR_SIGN
    return sign


def load() -> Connectome:
    nodes = pd.read_parquet(C.PROCESSED / "nodes.parquet").set_index("nid", drop=False)
    edges = pd.read_parquet(C.PROCESSED / "edges.parquet")

    sign = apply_nt_overrides(nodes, assign_sign(nodes))

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
