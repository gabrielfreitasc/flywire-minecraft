"""
L4 equivalente pro subcircuito `taste` (F10, candidato a paladar
apetitivo). Igual ao `hygro`/`johnston` (e ao contrário do `escape`, que
tem DNp01/DNp02 documentados na literatura): nenhuma leitura de literatura/
BANC foi feita pros 14 tipos de descendente daqui. Não fabricar essa
semântica (ver `docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `appetite`.** Mesmo mecanismo que gerou
`hygrotaxis`/`startle` — `topology.group_outputs_by_predicted_sign` separa
os descendentes em quem responde de forma excitatória vs. inibitória ao
estímulo da semente gustativa (GRNs de açúcar/água), sem depender de saber
o que cada tipo "significa" biologicamente. `appetite = tanh((taxa_excitatória
− taxa_inibitória) / C.TASTE_MOTOR_RATE_SCALE)` — escala PRÓPRIA, não a
genérica (ver docstring da constante em `config.py`: grupo pequeno de 12
neurônios satura demais com a escala calibrada pro ocelar inteiro).

Só validação de RN-09 (`tools/taste_calibration_check.py`: diff média=120,73
no grupo excitatório, N=30, p≈0) — prova que o CIRCUITO responde, não que o
COMPORTAMENTO responde. Por isso não entra em `MotorMapping.java` ainda —
mesma disciplina que manteve `hygrotaxis`/`startle` como telemetria até a
lesão fechar. Exposto aqui só para visualização/exploração
(`ActivityVisualizer`, `LiveHud`).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class TasteMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`hygro_motor.HygroMotorDecoder`, duplicada aqui de
    propósito (código pequeno, mais seguro que forçar reuso incorreto entre
    circuitos com espaços de `nid` independentes, ver docstring de
    `BristleMotorDecoder`)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "taste"
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
        return {"appetite": float(np.tanh((exc_rate - inh_rate) / C.TASTE_MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        """Quantos descendentes do taste dispararam ao menos uma vez na
        janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
