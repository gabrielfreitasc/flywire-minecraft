"""L4 — tradução de disparos em intenção motora."""
from __future__ import annotations

import re
from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome

_PREFIX_RE = re.compile(r"^[A-Za-z]+")

# RN-08, AD-14, AD-15, Namiki, Cande et al. 2018, Schlegel et al. 2024, Bates, Phelps, Kim, Yang et al. 2026
PUBLISHED_DN_BEHAVIOR: dict[str, str] = {
    "DNa10": "fast_locomotion",
    "DNp05": "fast_locomotion",  # Cheong & Eichler 2023
    "DNp16": "fast_locomotion",
    "DNp18": "fast_locomotion",
    "DNp28": "broad_locomotion",
    "DNg11": "wing_abdomen_movements",
    "DNa03": "steering",
    "DNae003": "steering",
    "DNb05": "steering",
    "DNb06": "steering",
    "DNge070": "steering",
    "DNp06": "escape_takeoff",  # Kim et al. 2023
    "DNp10": "landing",  # Ache et al. 2019
    "DNg79": "landing",  # Liessem et al. 2025
    "DNp20": "flight",  # Suver et al. 2016
    "DNg75": "walking",  # Sapkal et al. 2024
    "DNp22": "ocellar",  # Suver et al. 2016
    "DNp27": "neuromodulatory",
}

_LOCOMOTION_CATEGORIES = {"fast_locomotion", "broad_locomotion"}

# RN-08, AD-15, RN-09, F4
CONNECTIVITY_CLUSTER_BANC: dict[str, str] = {
    "DNbe001": "flight_steering_1",
    "DNbe005": "flight_steering_1",
    "DNge107": "flight_steering_1",
    "DNp31": "flight_steering_1",
    "DNpe017": "flight_steering_1",
    "DNg99": "flight_steering_2",
    "DNp19": "flight_steering_2",
    "DNp73": "flight_steering_2",
    "DNg49": "head_orienting",
    "DNg94": "head_orienting",
    "DNge043": "head_orienting",
    "DNge088": "head_orienting",
    "DNp53": "head_orienting",
    "DNpe004": "head_orienting",
    "DNpe009": "head_orienting",
    "DNpe013": "head_orienting",
    "DNge091": "flight_power",
    "DNpe011": "flight_power",
    "DNpe012": "flight_power",
    "DNpe014": "flight_power",
    "DNp102": "walking",
    "DNp41": "walking",
    "DNp12": "postural_control",
    "DNpe021": "postural_control",
    "DNp103": "threat_response",
    "DNpe026": "threat_response",
    "DNg90": "probing",
}


def group_by_published_behavior(
    connectome: Connectome, behavior: dict[str, str] | None = None
) -> dict[str, NDArray[np.int64]]:
    """RN-08: agrupa descendentes pela categoria de comportamento publicada para o tipo celular."""
    behavior = PUBLISHED_DN_BEHAVIOR if behavior is None else behavior
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        category = behavior.get(cell_type)
        if category is not None:
            groups.setdefault(category, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


def group_by_connectivity_cluster(
    connectome: Connectome, clusters: dict[str, str] | None = None
) -> dict[str, NDArray[np.int64]]:
    """RN-08: agrupa descendentes pelo cluster de conectividade do BANC (AD-15)."""
    clusters = CONNECTIVITY_CLUSTER_BANC if clusters is None else clusters
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        cluster = clusters.get(cell_type)
        if cluster is not None:
            groups.setdefault(cluster, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


# RN-08, F6
_STEERING_BILATERAL_TYPES = {"DNae003", "DNb05", "DNb06", "DNge070"}


def group_steering_by_side(connectome: Connectome) -> tuple[NDArray[np.int64], NDArray[np.int64]]:
    """RN-08/F6 — separa os 4 tipos steering bilaterais em (esquerda, direita) pela coluna `side`."""
    out_nodes = connectome.nodes.loc[connectome.output]
    sub = out_nodes[out_nodes.cell_type.isin(_STEERING_BILATERAL_TYPES)]
    left = np.array(sorted(sub.index[sub.side == "left"]), dtype=np.int64)
    right = np.array(sorted(sub.index[sub.side == "right"]), dtype=np.int64)
    return left, right


def group_by_cell_type_prefix(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """RN-08 provisório — agrupa os nids de saída pelo prefixo alfabético do cell_type."""
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        match = _PREFIX_RE.match(cell_type)
        prefix = match.group(0) if match else "unknown"
        groups.setdefault(prefix, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


class MotorDecoder:
    """Converte histórico de disparos dos descendentes em taxa normalizada por grupo."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self.groups = group_by_cell_type_prefix(connectome)
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(connectome)
        self._published_groups = group_by_published_behavior(connectome)
        self._connectivity_groups = group_by_connectivity_cluster(connectome)
        self._steering_left, self._steering_right = group_steering_by_side(connectome)
        self._locomotion_nids = np.array(
            sorted(
                nid
                for name in _LOCOMOTION_CATEGORIES
                for nid in self._published_groups.get(name, np.array([], dtype=np.int64))
            ),
            dtype=np.int64,
        )
        self._history: deque[tuple[int, NDArray[np.bool_]]] = deque()

    def push(self, t_ms: int, spikes: NDArray[np.bool_]) -> None:
        """Registra um frame de disparo e descarta o que saiu da janela."""
        self._history.append((t_ms, spikes))
        cutoff = t_ms - self.window_ms
        while self._history and self._history[0][0] < cutoff:
            self._history.popleft()

    def _rate_hz(self, nids: NDArray[np.int64]) -> float:
        if not self._history or len(nids) == 0:
            return 0.0
        window_s = self.window_ms / 1000.0
        count = sum(int(spikes[nids].sum()) for _, spikes in self._history)
        return (count / len(nids)) / window_s

    def decode(self) -> dict[str, float]:
        """Taxa de disparo por grupo na janela atual, normalizada via tanh."""
        vec = {name: float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
               for name, nids in self.groups.items()}

        exc_rate = self._rate_hz(self._excitatory)
        inh_rate = self._rate_hz(self._inhibitory)
        vec["phototaxis"] = float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE))

        for name, nids in self._published_groups.items():
            vec[name] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
        vec["locomotion_drive"] = float(np.tanh(self._rate_hz(self._locomotion_nids) / C.MOTOR_RATE_SCALE))

        for name, nids in self._connectivity_groups.items():
            vec[f"conn_{name}"] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))

        left_rate = self._rate_hz(self._steering_left)
        right_rate = self._rate_hz(self._steering_right)
        vec["yaw_steering"] = float(np.tanh((left_rate - right_rate) / C.MOTOR_RATE_SCALE))

        return vec

    def active_output_count(self) -> int:
        """Quantos descendentes dispararam ao menos uma vez na janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
