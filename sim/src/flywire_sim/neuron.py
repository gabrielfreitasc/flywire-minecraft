"""
L3 — modelo de neurônio: leaky integrate-and-fire.

Contrato:

    state = LIFState.zeros(n)
    spikes = step(state, current, dt_ms)   # -> NDArray[bool] (n,), muta `state`

RN-07 — período refratário de REFRACTORY_MS com reset ao repouso: um neurônio
que acabou de disparar não integra corrente nem pode disparar de novo até o
contador de refratário zerar.

Parâmetros em config.py (V_REST, V_THRESHOLD, TAU_MS, REFRACTORY_MS).
Herdados de Shiu et al. 2024, ajustados para o cérebro INTEIRO — num
subcircuito de 625 neurônios a excitação recorrente é muito menor e a rede
pode não disparar. Calibrar SYNAPTIC_GAIN por varredura em engine.py antes
de mexer aqui.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from . import config as C


@dataclass
class LIFState:
    """Estado mutável de uma população de neurônios LIF, indexado por nid."""

    V: NDArray[np.float32]              # potencial de membrana
    refractory_ms: NDArray[np.float32]  # tempo restante de refratário (0 = livre)

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
    """Avança `state` em um passo de dt_ms. Retorna a máscara de quem disparou.

    Integração de Euler: dV/dt = -(V - V_REST) / TAU_MS + current.
    Neurônios em refratário (RN-07) não integram e ficam presos em V_REST;
    apenas o contador decresce.
    """
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
