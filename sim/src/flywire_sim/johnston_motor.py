"""
L4 equivalente pro subcircuito `johnston` (F8, candidato a sensor de
vento/som). Mesma disciplina de `hygro_motor.py`: sem curadoria RN-08
equivalente ainda (nenhuma leitura de literatura/BANC feita pros 136 tipos
de descendente daqui). Não fabricar essa semântica (ver
`docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `startle`.** Mesmo mecanismo que gerou `phototaxis`
(ocelar) e `hygrotaxis` (hygro) — `topology.group_outputs_by_predicted_sign`
separa os descendentes em quem responde de forma excitatória vs. inibitória
ao estímulo da semente do órgão de Johnston, sem depender de saber o que
cada tipo "significa" biologicamente. Neste circuito a topologia é quase
toda excitatória (135/136 descendentes, RN-09/AD-19) — `startle` fica
próximo de só usar a taxa excitatória na prática, mas a subtração continua
lá por consistência com os outros circuitos e por segurança (não assumir
"não precisa separar" sem checar, mesma lição de RN-09).

**Telemetria pura** — sem lesão validando que afeta comportamento
observável ainda (mesma disciplina que manteve `grooming`/`hygrotaxis`
como telemetria até a lesão de cada um fechar). Exposto aqui só pra
visualização/exploração (`ActivityVisualizer`, `LiveHud`).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class JohnstonMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `hygro_motor.HygroMotorDecoder`, duplicada aqui de propósito (código
    pequeno, mais seguro que forçar reuso incorreto entre circuitos com
    espaços de `nid` independentes)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "johnston"
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
        exc_rate = self._rate_hz(self._excitatory)
        inh_rate = self._rate_hz(self._inhibitory)
        return {"startle": float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        """Quantos descendentes do johnston dispararam ao menos uma vez na
        janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
