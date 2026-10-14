"""L4 equivalente pro subcircuito `escape` (F9/AD-20, candidato a circuito de fuga por looming)."""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from .graph import Connectome

ESCAPE_DN_TYPES = ("DNp01", "DNp02")


class EscapeMotorDecoder:
    """Canal `escape_drive` do circuito escape: taxa de DNp01 + DNp02 (AD-20)."""
    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._escape_dn = connectome.nodes.index[
            connectome.nodes.cell_type.isin(ESCAPE_DN_TYPES)
        ].to_numpy()
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
        rate = self._rate_hz(self._escape_dn)
        return {"escape_drive": float(np.tanh(rate / C.ESCAPE_MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
