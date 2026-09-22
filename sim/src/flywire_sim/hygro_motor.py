"""
L4 equivalente pro subcircuito `hygro` (F7/AD-17, candidato a sensor de
chuva). Ao contrário do `bristle` (que já tem RN-08 equivalente via BANC,
ver `bristle_motor.py`), o `hygro` ainda não tem curadoria de comportamento
por tipo de descendente — nenhuma leitura de literatura/BANC foi feita pros
41 tipos de descendente daqui. Não fabricar essa semântica (ver
`docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `hygrotaxis`.** Mesmo mecanismo que gerou
`phototaxis` pro ocelar (F4, ANTES da curadoria RN-08 existir) —
`topology.group_outputs_by_predicted_sign` separa os descendentes em quem
responde de forma excitatória vs. inibitória ao estímulo da semente
higrossensorial, sem depender de saber o que cada tipo "significa"
biologicamente. `hygrotaxis = tanh((taxa_excitatória − taxa_inibitória) /
MOTOR_RATE_SCALE)`.

**Diferença importante em relação a `phototaxis`:** aquele canal foi
validado por um experimento de LESÃO em servidor real (F4, Mann-Whitney
p=0,0014) — prova que o sinal afeta o comportamento observável da abelha, não
só a atividade interna do circuito. `hygrotaxis` tem só a validação
equivalente de RN-09 (`tools/hygro_calibration_check.py`: estimular a semente
muda a atividade dos dois grupos de saída, p≈0, N=30) — prova que o CIRCUITO
responde, não que o COMPORTAMENTO responde. **Por isso não entra em
`MotorMapping.java` ainda** — mesma disciplina que manteve `grooming` como
telemetria pura até a lesão do `bristle` fechar (F7). Exposto aqui só para
visualização/exploração (`ActivityVisualizer`, `LiveHud`).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class HygroMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`bristle_motor.BristleMotorDecoder`, duplicada aqui
    de propósito (código pequeno, mais seguro que forçar reuso incorreto
    entre circuitos com espaços de `nid` independentes, ver docstring de
    `BristleMotorDecoder`)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "hygro"
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
        return {"hygrotaxis": float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE))}

    def active_output_count(self) -> int:
        """Quantos descendentes do hygro dispararam ao menos uma vez na
        janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
