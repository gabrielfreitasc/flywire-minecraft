"""L3 — laço de integração."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from . import config as C
from .graph import Connectome
from .neuron import LIFState
from .neuron import step as lif_step


@dataclass
class SpikeFrame:
    """Disparos de um passo: `spikes[nid]` é True se o neurônio disparou."""
    t_ms: int
    spikes: NDArray[np.bool_]


class Engine:
    """Integra o Connectome no tempo."""

    def __init__(
        self,
        connectome: Connectome,
        dt_ms: float = C.DT_MS,
        seed: int = C.NOISE_SEED,
    ) -> None:
        self.connectome = connectome
        self.dt_ms = dt_ms
        self.state = LIFState.zeros(connectome.n)
        self._bias = np.full(connectome.n, C.BIAS_CURRENT, dtype=np.float32)
        self._external = np.zeros(connectome.n, dtype=np.float32)
        self._directed = np.zeros(connectome.n, dtype=np.float32)
        self._recurrent = np.zeros(connectome.n, dtype=np.float32)
        self._rng = np.random.default_rng(seed)
        self._silenced = np.zeros(connectome.n, dtype=bool)
        self.t_ms = 0

    def stimulate(self, nids: NDArray[np.integer], amplitude: float) -> None:
        """Define a corrente externa constante nos `nids` dados (substitui a anterior)."""
        self._external[:] = 0.0
        self._external[np.asarray(nids)] = amplitude

    def set_directed_stimulus(self, nids: NDArray[np.integer], amplitude: float) -> None:
        """F5 — canal de estímulo independente de `stimulate()`; os dois somam."""
        self._directed[:] = 0.0
        if len(nids):
            self._directed[np.asarray(nids)] = amplitude

    def set_silenced(self, nids: NDArray[np.integer]) -> None:
        """F5 — substitui o conjunto de neurônios silenciados (não acumula com o anterior)."""
        self._silenced[:] = False
        if len(nids):
            self._silenced[np.asarray(nids)] = True

    def step(self) -> SpikeFrame:
        """Avança um passo de dt_ms."""
        noise = self._rng.normal(0.0, C.NOISE_STD, self.connectome.n).astype(np.float32)
        current = self._bias + self._external + self._directed + self._recurrent + noise
        spikes = lif_step(self.state, current, self.dt_ms)
        spikes = spikes & ~self._silenced
        self._recurrent = self.connectome.W @ spikes.astype(np.float32)
        self.t_ms += round(self.dt_ms)
        return SpikeFrame(t_ms=self.t_ms, spikes=spikes)
