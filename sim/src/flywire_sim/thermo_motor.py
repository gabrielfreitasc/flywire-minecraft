"""
L4 equivalente pro subcircuito `thermo` (F12, temperatura: calor/frio). Igual
ao `hygro`/`johnston`/`taste` (e ao contrário do `escape`, que tem DNp01/
DNp02 documentados na literatura): nenhuma leitura de literatura/BANC foi
feita pros 71 descendentes daqui. Não fabricar essa semântica (ver
`docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `thermal`.** Mesmo mecanismo de `hygrotaxis`/
`startle`/`appetite` — `topology.group_outputs_by_predicted_sign` separa os
descendentes em quem responde de forma excitatória (47) vs. inibitória (24)
ao estímulo da semente (TRNs de aquecimento e de frio), sem depender de
saber o que cada tipo "significa". `thermal = tanh((taxa_excitatória −
taxa_inibitória) / C.THERMO_MOTOR_RATE_SCALE)` — escala PRÓPRIA (ver
`config.py`: a genérica satura o baseline, tanh 0,79).

**Não distingue calor de frio** — as duas sementes (heating/cold) alimentam
o MESMO canal; quem chama (`server.py`) escolhe qual semente
estimular. Distinguir os dois no canal exigiria decodificar grupos de saída
separados por semente, não feito ainda.

Só validação de RN-09 (`tools/thermo_calibration_check.py`: diff média
=173,10 no grupo excitatório com as duas sementes, p≈0) — prova que o
CIRCUITO responde, não que o COMPORTAMENTO responde. Telemetria pura, não
entra em `MotorMapping.java` (mesma disciplina de `hygrotaxis`/`startle`).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class ThermoMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`hygro_motor.HygroMotorDecoder`, duplicada aqui de
    propósito (código pequeno, mais seguro que forçar reuso incorreto entre
    circuitos com espaços de `nid` independentes, ver docstring de
    `BristleMotorDecoder`)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "thermo"
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
        return {"thermal": float(np.tanh((exc_rate - inh_rate) / C.THERMO_MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        """Quantos descendentes do thermo dispararam ao menos uma vez na
        janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
