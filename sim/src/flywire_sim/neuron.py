"""L3 — modelo de neurônio: leaky integrate-and-fire."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from . import config as C


@dataclass
class LIFState:
    """Estado mutável de uma população de neurônios LIF, indexado por nid."""

    V: NDArray[np.float32]
    refractory_ms: NDArray[np.float32]

    @classmethod
    def zeros(cls, n: int) -> LIFState:
        return cls(
            V=np.full(n, C.V_REST, dtype=np.float32),
            refractory_ms=np.zeros(n, dtype=np.float32),
        )


def step(
    state: LIFState,
    current: NDArray[np.floating],
    dt_ms: float = C.DT_MS,
) -> NDArray[np.bool_]:
    """Avança `state` em um passo de dt_ms."""
    in_refractory = state.refractory_ms > 0.0
    state.refractory_ms = np.maximum(state.refractory_ms - dt_ms, 0.0)

    active = ~in_refractory
    leak = -(state.V - C.V_REST) / C.TAU_MS
    state.V = np.where(
        active,
        state.V + dt_ms * (leak + current),
        C.V_REST,
    ).astype(np.float32)

    spikes = active & (state.V >= C.V_THRESHOLD)
    state.V[spikes] = C.V_REST
    state.refractory_ms[spikes] = C.REFRACTORY_MS

    return spikes
