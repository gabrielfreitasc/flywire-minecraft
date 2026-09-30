"""
L4 equivalente pro subcircuito `olfactory` (F17, feromônio). Igual ao
`hygro`/`johnston`/`taste`/`thermo` (e ao contrário do `escape`, que tem
DNp01/DNp02 documentados na literatura): nenhuma leitura de literatura/BANC
foi feita pros 74 descendentes daqui. Não fabricar essa semântica (ver
`docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `pheromone`.** Mesmo mecanismo de `hygrotaxis`/
`startle`/`appetite`/`thermal` — `topology.group_outputs_by_predicted_sign`
separa os descendentes em quem responde de forma excitatória (43) vs.
inibitória (31) ao estímulo da semente (429 ORNs de feromônio — `ORN_DA1`/
`VA1d`/`VA1v`/`DL3`/`DC3`), sem depender de saber o que cada tipo
"significa". `pheromone = tanh((taxa_excitatória − taxa_inibitória) /
C.OLFACTORY_MOTOR_RATE_SCALE)` — escala PRÓPRIA (ver `config.py`).

Só validação de RN-09 (`tools/olfactory_calibration_check.py`: diff
média=294,65 no grupo excitatório, p≈0) — prova que o CIRCUITO responde,
não que o COMPORTAMENTO responde. Telemetria pura, não entra em
`MotorMapping.java` ainda (sem sensor no plugin pra feromônio — mesmo
estágio inicial que taste/thermo/johnston tiveram antes de ganhar sensor
real no jogo).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class OlfactoryMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`thermo_motor.ThermoMotorDecoder`, duplicada aqui
    de propósito (código pequeno, mais seguro que forçar reuso incorreto
    entre circuitos com espaços de `nid` independentes, ver docstring de
    `BristleMotorDecoder`)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "olfactory"
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
        return {"pheromone": float(np.tanh((exc_rate - inh_rate) / C.OLFACTORY_MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        """Quantos descendentes do olfactory dispararam ao menos uma vez na
        janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
