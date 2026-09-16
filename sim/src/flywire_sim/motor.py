"""
L4 — tradução de disparos em intenção motora.

Contrato:

    motor = MotorDecoder(connectome)
    motor.push(t_ms, spikes)          # a cada passo do engine
    vec = motor.decode()              # -> {"DNp": 0.31, "DNg": -0.02, ...}

RN-08 — mapeamento dos 92 descendentes para canais motores. **Ainda não
definido** qual grupo corresponde a qual comportamento (forward/yaw/lift) —
isso exige curadoria por tipo celular (DNp/DNa/DNg têm funções documentadas
na literatura, mas a leitura específica não foi feita). Não fabricar essa
semântica aqui — ver `CONVENCOES.md`.

Regra provisória (RN-08): agrupar por prefixo alfabético do `cell_type`
(descarta os dígitos finais). No subcircuito v1 dá 8 grupos: DNp (34),
DNpe (21), DNg (16), DNge (10), DNb (4), DNbe (3), DNa (2), DNae (2).

Cada canal por prefixo é a taxa de disparo do grupo numa janela deslizante de
`MOTOR_WINDOW_MS`, normalizada por `tanh(taxa_hz / MOTOR_RATE_SCALE)`. Como
taxa de disparo é sempre ≥0, o valor prático fica em [0, 1) — sem direção.

**Canal `phototaxis` (F4)** — o único canal com direção real (sinal), porque
vem de algo já VALIDADO estatisticamente (RN-09), não de curadoria: a
topologia de sinal (`topology.group_outputs_by_predicted_sign`) separa os 92
descendentes em quem responde de forma excitatória (29, desinibição de 2
saltos) vs inibitória (63, caminho direto) ao estímulo dos fotorreceptores.
`phototaxis = tanh((taxa_excitatória − taxa_inibitória) / MOTOR_RATE_SCALE)`.
Descoberto necessário na F4: o `ControlLoop` do plugin Java inicialmente
usava a MÉDIA dos 8 grupos por prefixo como magnitude de avanço, e o
experimento de lesão deu nulo (p=0,37) — repetindo o mesmo erro já corrigido
uma vez em RN-09 (agregar excitatório+inibitório cancela o sinal). Ver
`docs/04-regras-de-negocio.md`.

**Canais de comportamento publicado (RN-08, F5→F6, 16/09/2026)** — início da
curadoria real de RN-08. `PUBLISHED_DN_BEHAVIOR` mapeia 13 dos nossos 46 tipos
de descendente para a categoria comportamental que Namiki et al. 2018 (eLife,
Figura 6) mediu por ativação optogenética — leitura direta dos rótulos da
figura (classificação dos autores) para 12 tipos, mais 1 (`DNge070`) via
correspondência de identidade entre conectomas (`hemibrain_type`, Schlegel et
al. 2024) — não inferência nossa a partir de gráfico bruto (ver AD-14).
Cobertura: 29 de 92 neurônios (~32%); o resto segue sem dado publicado.

**Só "fast_locomotion" e "broad_locomotion" entram em `locomotion_drive`,
usado pelo `MotorMapping.java`.** "anterior_movements" e
"wing_abdomen_movements" também têm dado real (2 e 3 tipos), mas o ensaio de
Namiki testa MOSCA ANDANDO (perna dianteira, extensão de asa em contexto de
canto de corte) — sem tradução validada pra voo de abelha. Expostos em
`decode()` para visualização/exploração (F5: `/flywirebee mute|stimulate`),
mas deliberadamente FORA do cálculo de velocidade — usar seria fabricar a
mesma semântica que RN-08 proíbe, só que com uma camada a mais de disfarce
("tem citação" ≠ "a tradução é válida").
"""
from __future__ import annotations

import re
from collections import deque

import numpy as np
from numpy.typing import NDArray

from . import config as C
from . import topology
from .graph import Connectome

_PREFIX_RE = re.compile(r"^[A-Za-z]+")

# RN-08 / AD-14 — Namiki, Cande et al. 2018, eLife, Figura 6 (DOI:
# 10.7554/eLife.34275). Leitura direta dos rótulos da figura de categorização
# dos autores (não gráfico bruto). Só os tipos que existem no nosso
# subcircuito e aparecem nomeados na figura.
#
# "DNge070" não está na Figura 6 diretamente — entra via correspondência
# hemibrain_type=="DNb06" (coluna já presente em
# Supplemental_file1_neuron_annotations.tsv, gerada por Schlegel et al. 2024
# comparando FlyWire x Hemibrain). Match limpo: os 2 neurônios de DNge070
# batem 100% com DNb06 no hemibrain_type, conferido manualmente. É um passo
# de inferência a mais que os outros 12 (identidade de tipo entre
# conectomas, não behavior medido direto nesse root_id) — ver
# docs/04-regras-de-negocio.md (RN-08).
PUBLISHED_DN_BEHAVIOR: dict[str, str] = {
    "DNa10": "fast_locomotion",
    "DNb05": "fast_locomotion",
    "DNb06": "fast_locomotion",
    "DNge070": "fast_locomotion",  # via hemibrain_type==DNb06, ver nota acima
    "DNp05": "fast_locomotion",
    "DNp16": "fast_locomotion",
    "DNp18": "fast_locomotion",
    "DNp28": "broad_locomotion",
    "DNp06": "anterior_movements",
    "DNp20": "anterior_movements",
    "DNg11": "wing_abdomen_movements",
    "DNp10": "wing_abdomen_movements",
    "DNp27": "wing_abdomen_movements",
}

# Categorias com tradução defensável pra magnitude de voo (locomoção em
# geral). "anterior_movements" e "wing_abdomen_movements" ficam de fora — ver
# docstring do módulo.
_LOCOMOTION_CATEGORIES = {"fast_locomotion", "broad_locomotion"}


def group_by_published_behavior(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """RN-08 — agrupa os nids de saída pela categoria comportamental publicada
    (Namiki et al. 2018), só para os 12 tipos com dado real. Ver docstring
    do módulo."""
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        category = PUBLISHED_DN_BEHAVIOR.get(cell_type)
        if category is not None:
            groups.setdefault(category, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


def group_by_cell_type_prefix(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """RN-08 provisório — agrupa os nids de saída pelo prefixo alfabético do cell_type."""
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        match = _PREFIX_RE.match(cell_type)
        prefix = match.group(0) if match else "unknown"
        groups.setdefault(prefix, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


class MotorDecoder:
    """Converte histórico de disparos dos descendentes em taxa normalizada por grupo."""

    def __init__(self, connectome: Connectome, window_ms: float = C.MOTOR_WINDOW_MS) -> None:
        self.connectome = connectome
        self.window_ms = window_ms
        self.groups = group_by_cell_type_prefix(connectome)
        self._excitatory, self._inhibitory = topology.group_outputs_by_predicted_sign(connectome)
        self._published_groups = group_by_published_behavior(connectome)
        self._locomotion_nids = np.array(
            sorted(
                nid
                for name in _LOCOMOTION_CATEGORIES
                for nid in self._published_groups.get(name, np.array([], dtype=np.int64))
            ),
            dtype=np.int64,
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
        """Taxa de disparo por grupo na janela atual, normalizada via tanh.

        Inclui, além dos 8 grupos provisórios por prefixo de cell_type
        (RN-08): `phototaxis` (validado por RN-09/lesão F4), os canais de
        comportamento publicado (`fast_locomotion`, `broad_locomotion`,
        `anterior_movements`, `wing_abdomen_movements` — Namiki et al. 2018,
        só os presentes em `PUBLISHED_DN_BEHAVIOR`) e `locomotion_drive`
        (agregado de fast+broad, o único desses usado por `MotorMapping.java`
        — ver docstring do módulo pra por que os outros dois ficam de fora).
        """
        vec = {name: float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
               for name, nids in self.groups.items()}

        exc_rate = self._rate_hz(self._excitatory)
        inh_rate = self._rate_hz(self._inhibitory)
        vec["phototaxis"] = float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE))

        for name, nids in self._published_groups.items():
            vec[name] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
        vec["locomotion_drive"] = float(np.tanh(self._rate_hz(self._locomotion_nids) / C.MOTOR_RATE_SCALE))

        return vec

    def active_output_count(self) -> int:
        """Quantos descendentes dispararam ao menos uma vez na janela atual.

        Usado pela ponte (server.py) para o campo `active_dn` do protocolo.
        """
        if not self._history:
            return 0
        active = np.zeros(self.connectome.n, dtype=bool)
        for _, spikes in self._history:
            active |= spikes
        return int(active[self.connectome.output].sum())
