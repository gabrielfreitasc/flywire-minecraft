"""L4 do circuito bristle: descendentes de toque curados por literatura e por BANC (RN-08)."""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from .graph import Connectome
from .motor import group_by_connectivity_cluster as _group_by_connectivity_cluster
from .motor import group_by_published_behavior as _group_by_published_behavior

# Bates, Phelps, Kim, Yang et al. 2026
BRISTLE_PUBLISHED_DN_BEHAVIOR: dict[str, str] = {
    "DNg12_e": "grooming",
    "DNge011": "grooming",
    "DNge012": "grooming",
    "DNge025": "grooming",
    "DNge028": "grooming",
    "DNge078": "grooming",
}

BRISTLE_CONNECTIVITY_CLUSTER: dict[str, str] = {
    "DNae007": "DN_10",
    "DNde006": "DN_11",
    "DNg15": "DN_11",
    "DNg20": "DN_13",
    "DNg23": "DN_06",
    "DNg29": "DN_01",
    "DNg35": "DN_10",
    "DNg37": "DN_09",
    "DNg39": "DN_10",
    "DNg48": "DN_09",
    "DNg54": "DN_09",
    "DNg57": "DN_11",
    "DNg59": "DN_11",
    "DNg61": "DN_09",
    "DNg81": "DN_11",
    "DNg83": "DN_13",
    "DNg84": "DN_13",
    "DNg85": "DN_11",
    "DNg87": "DN_11",
    "DNge001": "DN_17",
    "DNge002": "DN_02",
    "DNge003": "DN_09",
    "DNge019": "DN_17",
    "DNge029": "DN_09",
    "DNge036": "DN_09",
    "DNge037": "DN_09",
    "DNge038": "DN_01",
    "DNge039": "DN_17",
    "DNge041": "DN_09",
    "DNge044": "DN_17",
    "DNge051": "DN_09",
    "DNge054": "DN_03",
    "DNge055": "DN_09",
    "DNge056": "DN_09",
    "DNge057": "DN_11",
    "DNge065": "DN_03",
    "DNge067": "DN_09",
    "DNge080": "DN_09",
    "DNge082": "DN_01",
    "DNge096": "DN_09",
    "DNge098": "DN_01",
    "DNge100": "DN_09",
    "DNge101": "DN_10",
    "DNge104": "DN_13",
    "DNge105": "DN_11",
    "DNge121": "DN_11",
    "DNge122": "DN_13",
    "DNge128": "DN_09",
    "DNge132": "DN_11",
    "DNge133": "DN_13",
    "DNge139": "DN_01",
    "DNpe002": "DN_09",
}

BRISTLE_NO_DATA = ("DNge008", "DNge021")


def group_by_published_behavior(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """Wrapper de `motor.group_by_published_behavior` com o dicionário do `bristle`."""
    return _group_by_published_behavior(connectome, BRISTLE_PUBLISHED_DN_BEHAVIOR)


def group_by_connectivity_cluster(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """Wrapper de `motor.group_by_connectivity_cluster` com o dicionário do `bristle`."""
    return _group_by_connectivity_cluster(connectome, BRISTLE_CONNECTIVITY_CLUSTER)


class BristleMotorDecoder:
    """Canal `grooming` do circuito bristle (toque), a partir dos descendentes curados."""
    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._published_groups = group_by_published_behavior(connectome)
        self._connectivity_groups = group_by_connectivity_cluster(connectome)
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
               for name, nids in self._published_groups.items()}
        for name, nids in self._connectivity_groups.items():
            vec[f"conn_{name}"] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
        return vec

    def active_output_count(self) -> int:
        """Quantos descendentes do bristle dispararam ao menos uma vez na janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
