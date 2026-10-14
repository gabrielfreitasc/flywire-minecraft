"""
L1 → L2 — matriz esparsa CSR + vetor de sinal, com os overrides de RN-01 e RN-02.

Esta é a camada onde uma regra errada corrompe tudo a jusante em silêncio.
Os testes de `test_photoreceptor_override` e `test_sign_assignment` são obrigatórios.
"""
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


def apply_serotonin_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-18 — dois grupos rotulados "serotonin" pelo classificador de
    Eckstein et al., mas com transmissor real estabelecido por fonte
    independente (não é suposição nova, mesmo padrão de `apply_nt_overrides`):
    ORNs (colinérgicas) e neurônios locais do lobo antenal, família
    lLN1/lLN2 (GABAérgicos, Schlegel et al. 2021). Não cobre o restante dos
    neurônios "serotonin" (inclui `CSD`, a serotonérgica real do lobo
    antenal) — esses continuam sinal 0, incerteza genuína sem fonte pra
    resolver. Ver RN-01a em docs/04-regras-de-negocio.md.
    """
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    cell_class = nodes.cell_class.fillna("")
    sign = sign.copy()
    sign[is_serotonin & (cell_class == "olfactory").to_numpy()] = C.OLFACTORY_RECEPTOR_SIGN
    sign[is_serotonin & (cell_class == "ALLN").to_numpy()] = C.ANTENNAL_LOBE_LOCAL_NEURON_SIGN
    return sign


def apply_johnston_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-19 — mesmo padrão de artefato de RN-02/AD-18, achado ao
    investigar o subcircuito `johnston` (F8, vento/som): neurônios do
    órgão de Johnston (`cell_sub_class` em {"wind_gravity", "auditory"})
    rotulados "serotonin" pelo classificador de Eckstein et al., mas com
    identidade colinérgica estabelecida por evidência independente
    (Kitamoto et al. 1995; Yasuyama & Salvaterra 1999 — expressão de
    ChAT). Ver RN-01a em docs/04-regras-de-negocio.md.
    """
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_johnston = nodes.cell_sub_class.fillna("").isin(["wind_gravity", "auditory"]).to_numpy()
    sign = sign.copy()
    sign[is_serotonin & is_johnston] = C.JOHNSTON_ORGAN_SIGN
    return sign


def apply_gustatory_artifact_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """RN-01a/AD-21 — mesmo padrão de artefato de RN-02/AD-18/AD-19, achado
    ao investigar o subcircuito `taste` (F10, paladar): GRNs de açúcar/água
    (`cell_sub_class == "sugar/water"`) rotuladas "serotonin" pelo
    classificador de Eckstein et al., mesmo DENTRO do mesmo `cell_type`
    (LB3: 84 acetilcolina, 27 serotonin, 11 glutamato — heterogeneidade de
    rótulo numa população geneticamente homogênea, mesmo padrão de
    artefato já visto em ORN/órgão de Johnston). Identidade colinérgica de
    neurônios quimiossensoriais primários é estabelecida por evidência
    independente (Yasuyama & Salvaterra 1999 — expressão de ChAT em
    neurônios sensoriais periféricos, mesma fonte já usada pra ORN/AD-18).
    Não cobre os 11 glutamato do mesmo `cell_type` (LB3) nem os 6 glutamato
    de LB2d — sem fonte pra afirmar que também são artefato, ficam como o
    classificador rotulou (sign real, não incerteza). Ver RN-01a em
    docs/04-regras-de-negocio.md.
    """
    nt = nodes.top_nt.str.lower().fillna("")
    is_serotonin = (nt == "serotonin").to_numpy()
    is_sugar_water = (nodes.cell_sub_class.fillna("") == "sugar/water").to_numpy()
    sign = sign.copy()
    sign[is_serotonin & is_sugar_water] = C.GUSTATORY_RECEPTOR_SIGN
    return sign


def known_nt_sign(known_nt: object) -> int | None:
    """AD-29 — sinal do transmissor CONFIRMADO em literatura (coluna `known_nt`
    da anotação oficial, Schlegel et al. 2024), pela mesma tabela da RN-01.

    O texto é livre ("acetylcholine; sNPF", "glutamate, gaba; glutamate",
    "octopamine, acetylcholine-negative, ..."): conta só transmissor rápido
    que a RN-01 mapeia; ignora "-negative", peptídeos, óxido nítrico,
    serotonina e tiramina (moduladores ao lado de um transmissor rápido).
    `None` (sem override) quando não há transmissor mapeável, quando há
    sinais opostos (co-transmissão, ex. ACh + GABA) ou quando aparece um
    inibitório rápido fora da RN-01 (`KNOWN_NT_AMBIGUOUS`: histamina,
    glicina).
    """
    if not isinstance(known_nt, str) or not known_nt.strip():
        return None
    tokens = {t.strip().lower() for t in re.split(r"[;,]", known_nt)}
    if tokens & set(C.KNOWN_NT_AMBIGUOUS):
        return None
    signs = {1 for t in tokens if t in C.EXCITATORY_NT} | {-1 for t in tokens if t in C.INHIBITORY_NT}
    return signs.pop() if len(signs) == 1 else None


def apply_known_nt_overrides(nodes: pd.DataFrame, sign: np.ndarray) -> np.ndarray:
    """AD-29 — transmissor confirmado em literatura vence a predição do
    classificador (`top_nt`, Eckstein et al. 2024) E os overrides por
    analogia (RN-02, AD-18/19/21). Aplicado por último. Achado da DT-2
    (13/10/2026): no cérebro inteiro, 1.245 de 69.975 neurônios com
    `known_nt` mapeável tinham sinal do modelo diferente da literatura —
    incluindo 21 neurônios locais do lobo antenal colinérgicos (`lLN1_bc`,
    `lLN2T_b/c`, `lLN2X03`; Shang et al. 2007) que a AD-18 forçava a -1.
    Ver RN-01 em docs/04-regras-de-negocio.md.
    """
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
    """Sinal final de cada neurônio, na ordem de precedência do projeto:
    predição (RN-01) → overrides por analogia (RN-02, AD-18, AD-19, AD-21) →
    literatura (AD-29). ÚNICO lugar onde a cadeia é montada — `load`,
    `topology` e as ferramentas chamam isto (antes eram três cópias; a AD-18
    já tinha mostrado o risco de esquecer um call site)."""
    sign = apply_nt_overrides(nodes, assign_sign(nodes))
    sign = apply_serotonin_artifact_overrides(nodes, sign)
    sign = apply_johnston_artifact_overrides(nodes, sign)
    sign = apply_gustatory_artifact_overrides(nodes, sign)
    return apply_known_nt_overrides(nodes, sign)


def load(out_dir: Path | None = None) -> Connectome:
    """Carrega um subcircuito extraído por `ingest.build`. `out_dir` default é
    `C.PROCESSED` (circuito ocelar, v1); outros circuitos (AD-17, F7) passam
    o próprio diretório (ex.: `C.PROCESSED / "bristle"`)."""
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
