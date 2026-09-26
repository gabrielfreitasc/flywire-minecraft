"""
L4 equivalente pro subcircuito `escape` (F9/AD-20, candidato a circuito de
fuga por looming). Diferente de `hygro_motor.py`/`johnston_motor.py` (que
usam `topology.group_outputs_by_predicted_sign`, sem saber o que cada
descendente "significa" biologicamente): aqui o grupo de saída é curado por
IDENTIDADE de tipo celular — `DNp01` (Giant Fiber) e `DNp02` (via paralela),
os dois únicos entre os 31 descendentes alcançados em 1 salto que a
literatura (Ache et al. 2019; von Reyn et al. 2017) liga diretamente a
looming. Ver `tools/build_f9_circuit.py` e `tools/escape_calibration_check.py`.

**Único canal exposto: `escape_drive`.** Diferente de `phototaxis`/
`hygrotaxis` (diferença excitatório menos inibitório — canal de DIREÇÃO),
`escape_drive` é a taxa de disparo agregada de DNp01+DNp02 normalizada por
tanh — um canal de INTENSIDADE (quanto a via de fuga está ativa), não de
direção. Isso é uma escolha de modelagem consciente com a biologia: GF
dispara um comando de fuga (salto+voo), não "vira pra direita/esquerda".

**Sem separação excitatório/inibitório:** os dois tipos recebem só entrada
colinérgica direta de LC4/LPLC2 dentro deste subcircuito extraído (ver
docstring de `build_f9_circuit.py`) — não há via inibitória concorrente
conhecida aqui, agregar os dois não dilui/cancela sinal (não é o mesmo caso
de RN-08/RN-09 documentado em CLAUDE.md).

**Escala própria (`C.ESCAPE_MOTOR_RATE_SCALE`), não `C.MOTOR_RATE_SCALE`.**
Achado real em servidor real (25/09/2026): com a escala genérica (30,
calibrada pro ocelar inteiro), a taxa BASAL desse grupo de só 4 neurônios
já satura o tanh (média 0,83, 73% das amostras acima de 0,8 sem nenhum
estímulo) — a abelha entrava em "fuga" o tempo todo mesmo sem ameaça
nenhuma. Ver docstring de `ESCAPE_MOTOR_RATE_SCALE` em `config.py` pros
números completos.

**Mesmo status de `hygrotaxis`/`startle`:** só validação de RN-09 (circuito
responde), sem lesão em servidor real validando que afeta comportamento
observável. Por isso não entra em `MotorMapping.java` ainda — exposto só
para visualização/exploração (`ActivityVisualizer`, `LiveHud`).
"""
from __future__ import annotations

from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from .graph import Connectome

ESCAPE_DN_TYPES = ("DNp01", "DNp02")


class EscapeMotorDecoder:
    """Mesma mecânica de janela deslizante/normalização tanh de
    `motor.MotorDecoder`/`bristle_motor.BristleMotorDecoder`/
    `hygro_motor.HygroMotorDecoder`, duplicada aqui de propósito (ver
    docstring de `BristleMotorDecoder`)."""

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
        """Quantos descendentes do escape (todos os 31, não só DNp01/DNp02)
        dispararam ao menos uma vez na janela atual — mesma semântica de
        `active_output_count` nos outros decoders (telemetria geral, não
        o grupo curado do canal `escape_drive`)."""
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
