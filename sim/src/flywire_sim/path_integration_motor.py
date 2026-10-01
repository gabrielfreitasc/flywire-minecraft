"""
L4 equivalente pro subcircuito `path_integration` (F19, integração de
trajetórias). Igual ao `hygro`/`johnston`/`taste`/`thermo`/`olfactory`/
`aggression` (e ao contrário do `escape`, que tem DNp01/DNp02 documentados
na literatura): nenhuma leitura de literatura/BANC foi feita pros 49
descendentes daqui — a literatura identifica a SEMENTE (`PFNp/m/a/d`), não
os descendentes. Não fabricar essa semântica (ver
`docs/04-regras-de-negocio.md` RN-08).

**Único canal exposto: `path_integration`.** Mesmo mecanismo de
`hygrotaxis`/`startle`/`appetite`/`thermal`/`pheromone`/`aggression` —
`topology.group_outputs_by_predicted_sign` separa os descendentes em quem
responde de forma excitatória (34) vs. inibitória (15) ao estímulo da
semente (422 neurônios PFN), sem depender de saber o que cada tipo
"significa". `path_integration = tanh((taxa_excitatória − taxa_inibitória)
/ C.PATH_INTEGRATION_MOTOR_RATE_SCALE)` — escala PRÓPRIA (ver `config.py`).

**Achado real: o sinal líquido é NEGATIVO sob estímulo** (diferente de
todos os canais anteriores, que saturam positivo) — o caminho inibitório
domina mais que o excitatório neste circuito específico. `tanh` lida bem
com isso (o canal só fica perto de -1 em vez de +1 quando ativo); não é
erro, é assim que a topologia de sinal real deste circuito se comporta.

Só validação de RN-09 (`tools/path_integration_calibration_check.py`:
diff média exc=90,20/inh=62,30, ambos p≈0) — prova que o CIRCUITO
responde, não que o COMPORTAMENTO responde. Telemetria pura, não entra em
`MotorMapping.java` ainda (sem sensor no plugin — diferente de
`olfactory`/`aggression`, aqui o estímulo real seria derivado do próprio
movimento da abelha, não de um evento de ambiente).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome


class PathIntegrationMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`thermo_motor.ThermoMotorDecoder`, duplicada aqui
    de propósito (código pequeno, mais seguro que forçar reuso incorreto
    entre circuitos com espaços de `nid` independentes, ver docstring de
    `BristleMotorDecoder`)."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(
            connectome, C.PROCESSED / "path_integration"
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
        return {
            "path_integration": float(
                np.tanh((exc_rate - inh_rate) / C.PATH_INTEGRATION_MOTOR_RATE_SCALE)
            )
        }

    def active_output_count(self) -> int:
        """Quantos descendentes do path_integration dispararam ao menos
        uma vez na janela atual."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
