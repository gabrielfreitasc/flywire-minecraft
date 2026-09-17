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
curadoria real de RN-08. `PUBLISHED_DN_BEHAVIOR` mapeia 18 dos nossos 47 tipos
de descendente (39/92 neurônios, ~42%) para categoria comportamental medida —
Namiki et al. 2018 (eLife, Figura 6, ativação optogenética) e, desde AD-15,
Bates/Phelps/Kim/Yang et al. 2026 (BANC connectome, `Supplementary Data 9` —
revisão de literatura dos próprios autores, mesmo nível de evidência que
Namiki). Ver `docs/04-regras-de-negocio.md` RN-08 pra tabela completa de
fontes e a resolução tipo-a-tipo dos 7 conflitos entre as duas fontes.

**Só "fast_locomotion" e "broad_locomotion" entram em `locomotion_drive`,
usado pelo `MotorMapping.java`.** As outras categorias (`steering`,
`escape_takeoff`, `landing`, `flight`, `walking`, `ocellar`,
`wing_abdomen_movements`, `neuromodulatory`) têm dado real mas sem tradução
validada pra magnitude de voo da abelha (algumas vêm de ensaio de mosca
ANDANDO; `neuromodulatory` nem é categoria motora). Expostas em `decode()`
para visualização/exploração (F5: `/flywirebee mute|stimulate`), mas
deliberadamente FORA do cálculo de velocidade — usar seria fabricar a mesma
semântica que RN-08 proíbe, só que com uma camada a mais de disfarce ("tem
citação" ≠ "a tradução é válida").

**Canais de cluster de conectividade BANC (RN-08/AD-15, F6, 16/09/2026)** —
`CONNECTIVITY_CLUSTER_BANC` mapeia mais 27 tipos (50/92 neurônios) que não
têm comportamento medido, só cluster de UMAP sobre conectividade até
efetores (`Supplementary Data 6` do BANC, Fig. 3/Extended Data Fig. 6 do
paper). **Nível de evidência mais fraco que `PUBLISHED_DN_BEHAVIOR`** — é
inferência de topologia, igual ao que gerou `phototaxis`, mas sem a validação
estatística que `phototaxis` teve (RN-09/lesão F4). Expostos em `decode()`
com prefixo `conn_` pra deixar isso visualmente óbvio, e ficam fora de
`locomotion_drive`/`MotorMapping.java` até validação própria por lesão —
agregar canal não validado em cima de `phototaxis` já diluiu o sinal 3 vezes
neste projeto (RN-08/RN-09), não repetir.

Dois tipos ficam de fora de tudo: `DNpe027` (2 neurônios) é ambíguo — os 3
neurônios do tipo se dividem entre clusters diferentes no BANC, sem consenso
por tipo; `DNp40` (1 neurônio) não aparece em BANC, nem literatura nem
conectividade.

**Canal `yaw_steering` (RN-08, F6, 17/09/2026) — primeiro candidato real a
direção além de `phototaxis`.** `nodes.parquet` tem uma coluna `side`
(esquerda/direita/centro, de `Supplemental_file1`) que nunca tinha sido usada
em lugar nenhum do código. Cruzando contra `PUBLISHED_DN_BEHAVIOR`: dos 5
tipos rotulados `steering` (Feng et al. 2024, Yang et al. 2024), **4 têm par
bilateral limpo — exatamente 1 neurônio à esquerda + 1 à direita cada**
(`DNae003`, `DNb05`, `DNb06`, `DNge070`; `DNa03` fica de fora, só tem 1
neurônio, sem par). `yaw_steering = tanh((taxa_esquerda − taxa_direita) /
MOTOR_RATE_SCALE)`.

**O que isso NÃO estabelece:** que o sinal do canal corresponde a "virar pra
esquerda" ou "virar pra direita" no mundo. `side` é o lado do CORPO CELULAR,
não necessariamente o lado do efeito comportamental (circuito pode ser
ipsi- ou contralateral) — mesma ressalva já registrada pro `side` do BANC.
**Exposto só como telemetria** (`decode()`), fora de `MotorMapping.java` até
um experimento real validar o que o sinal significa (precisaria medir
mudança de direção da abelha, não só distância — infraestrutura que não
existe ainda). Ver `docs/04-regras-de-negocio.md` RN-08.
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

# RN-08 / AD-14+AD-15 — comportamento medido, duas fontes:
#
# (1) Namiki, Cande et al. 2018, eLife, Figura 6 (DOI: 10.7554/eLife.34275).
#     Leitura direta dos rótulos da figura de categorização dos autores (não
#     gráfico bruto). "DNge070" entra por correspondência de identidade
#     hemibrain_type=="DNb06" (Schlegel et al. 2024) — segue a classificação
#     que DNb06 tiver, não tem citação própria.
#
# (2) Bates, Phelps, Kim, Yang et al. 2026 (BANC connectome), Nature (DOI:
#     10.1038/s41586-026-10735-w), `Supplementary Data 9` — revisão de
#     literatura curada pelos próprios autores do BANC, citando o paper
#     original que mediu o comportamento (não é achado do BANC em si).
#     Harvard Dataverse DOI 10.7910/DVN/7WTH1N, baixado 16/09/2026.
#
# Conflitos entre as duas fontes (7 tipos) resolvidos em 16/09/2026 — ver
# docs/04-regras-de-negocio.md RN-08 pra tabela com a justificativa de cada
# um. Critério geral: medido bate putativo; entre duas fontes medidas,
# prevalece a mais específica sobre direção e/ou mais relevante a voo (não
# mosca andando).
PUBLISHED_DN_BEHAVIOR: dict[str, str] = {
    "DNa10": "fast_locomotion",
    "DNp05": "fast_locomotion",  # Namiki medido > BANC putativo (Cheong & Eichler 2023)
    "DNp16": "fast_locomotion",
    "DNp18": "fast_locomotion",
    "DNp28": "broad_locomotion",
    "DNg11": "wing_abdomen_movements",
    # steering — Feng et al. 2024 (DNa03/DNae003), Yang et al. 2024 (DNb05/DNb06).
    # DNb05/DNb06 eram "fast_locomotion" no AD-14; mudaram porque BANC também é
    # medido (não putativo) e é mais específico sobre o eixo que falta (direção).
    "DNa03": "steering",
    "DNae003": "steering",
    "DNb05": "steering",
    "DNb06": "steering",
    "DNge070": "steering",  # segue DNb06 (hemibrain_type), ver nota acima
    "DNp06": "escape_takeoff",  # era anterior_movements (Namiki, mosca andando); Kim et al. 2023
    "DNp10": "landing",  # era wing_abdomen_movements (Namiki, mosca andando); Ache et al. 2019
    "DNg79": "landing",  # Liessem et al. 2025
    "DNp20": "flight",  # era anterior_movements (Namiki); Suver et al. 2016 — mesmo paper de DNp22/ocellar
    "DNg75": "walking",  # =cDN1, Sapkal et al. 2024
    "DNp22": "ocellar",  # =DNOVS1, Suver et al. 2016 — único tipo com função ocelar específica
    "DNp27": "neuromodulatory",  # era wing_abdomen_movements; não é categoria motora (RN-01a)
}

# Categorias com tradução defensável pra magnitude de voo (locomoção em
# geral). As demais têm dado real mas não entram — ver docstring do módulo.
_LOCOMOTION_CATEGORIES = {"fast_locomotion", "broad_locomotion"}

# RN-08/AD-15 — cluster de conectividade do BANC (`Supplementary Data 6`,
# PCA-UMAP sobre influência até efetores; Fig. 3/Extended Data Fig. 6 do
# paper). NÃO é comportamento medido — é o mesmo tipo de inferência que gerou
# `phototaxis`, só que sem a validação estatística que `phototaxis` teve
# (RN-09/lesão F4). Só os 27 tipos com cluster CONSISTENTE (mesmo cluster em
# todos os neurônios do tipo); `DNpe027` ficou de fora por ser ambíguo (3
# neurônios divididos entre "postural control" e "walking"). `DNp40` não tem
# nenhuma entrada no BANC. Ver docs/04-regras-de-negocio.md RN-08.
CONNECTIVITY_CLUSTER_BANC: dict[str, str] = {
    "DNbe001": "flight_steering_1",
    "DNbe005": "flight_steering_1",
    "DNge107": "flight_steering_1",
    "DNp31": "flight_steering_1",
    "DNpe017": "flight_steering_1",
    "DNg99": "flight_steering_2",
    "DNp19": "flight_steering_2",
    "DNp73": "flight_steering_2",
    "DNg49": "head_orienting",
    "DNg94": "head_orienting",
    "DNge043": "head_orienting",
    "DNge088": "head_orienting",
    "DNp53": "head_orienting",
    "DNpe004": "head_orienting",
    "DNpe009": "head_orienting",
    "DNpe013": "head_orienting",
    "DNge091": "flight_power",
    "DNpe011": "flight_power",
    "DNpe012": "flight_power",
    "DNpe014": "flight_power",
    "DNp102": "walking",
    "DNp41": "walking",
    "DNp12": "postural_control",
    "DNpe021": "postural_control",
    "DNp103": "threat_response",
    "DNpe026": "threat_response",
    "DNg90": "probing",
}


def group_by_published_behavior(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """RN-08 — agrupa os nids de saída pela categoria comportamental publicada
    (Namiki et al. 2018 + BANC/AD-15), só para os 18 tipos com dado real. Ver
    docstring do módulo."""
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        category = PUBLISHED_DN_BEHAVIOR.get(cell_type)
        if category is not None:
            groups.setdefault(category, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


def group_by_connectivity_cluster(connectome: Connectome) -> dict[str, NDArray[np.int64]]:
    """RN-08/AD-15 — agrupa os nids de saída pelo cluster de conectividade
    BANC, só para os 27 tipos sem comportamento medido mas com cluster
    consistente. Evidência mais fraca que `group_by_published_behavior` — ver
    docstring do módulo. Não usar pra cálculo de velocidade sem validar."""
    groups: dict[str, list[int]] = {}
    out_nodes = connectome.nodes.loc[connectome.output]
    for nid, cell_type in zip(out_nodes.index, out_nodes.cell_type):
        cluster = CONNECTIVITY_CLUSTER_BANC.get(cell_type)
        if cluster is not None:
            groups.setdefault(cluster, []).append(nid)
    return {name: np.array(sorted(nids), dtype=np.int64) for name, nids in groups.items()}


# RN-08/F6 — os 4 tipos "steering" (PUBLISHED_DN_BEHAVIOR) com par bilateral
# limpo (1 neurônio esquerda + 1 direita cada, via coluna `side` de
# nodes.parquet). `DNa03` (5º tipo steering) fica de fora — só 1 neurônio,
# sem par. Ver docstring do módulo — canal de telemetria, sentido do sinal
# (esquerda/direita do MUNDO) não validado.
_STEERING_BILATERAL_TYPES = {"DNae003", "DNb05", "DNb06", "DNge070"}


def group_steering_by_side(connectome: Connectome) -> tuple[NDArray[np.int64], NDArray[np.int64]]:
    """RN-08/F6 — separa os 4 tipos steering bilaterais em (esquerda, direita)
    pela coluna `side`. Base do canal de telemetria `yaw_steering` em
    `decode()`. Ver docstring do módulo."""
    out_nodes = connectome.nodes.loc[connectome.output]
    sub = out_nodes[out_nodes.cell_type.isin(_STEERING_BILATERAL_TYPES)]
    left = np.array(sorted(sub.index[sub.side == "left"]), dtype=np.int64)
    right = np.array(sorted(sub.index[sub.side == "right"]), dtype=np.int64)
    return left, right


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
        self._connectivity_groups = group_by_connectivity_cluster(connectome)
        self._steering_left, self._steering_right = group_steering_by_side(connectome)
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
        comportamento publicado (`PUBLISHED_DN_BEHAVIOR` — Namiki et al. 2018
        + BANC/AD-15), os canais de cluster de conectividade BANC com prefixo
        `conn_` (`CONNECTIVITY_CLUSTER_BANC` — evidência mais fraca, ver
        docstring do módulo), `yaw_steering` (par bilateral dos 4 tipos
        steering, telemetria — sentido do sinal não validado) e
        `locomotion_drive` (agregado de fast+broad, o único desses usado por
        `MotorMapping.java` — os demais ficam de fora do cálculo de
        velocidade, ver docstring do módulo).
        """
        vec = {name: float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
               for name, nids in self.groups.items()}

        exc_rate = self._rate_hz(self._excitatory)
        inh_rate = self._rate_hz(self._inhibitory)
        vec["phototaxis"] = float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE))

        for name, nids in self._published_groups.items():
            vec[name] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))
        vec["locomotion_drive"] = float(np.tanh(self._rate_hz(self._locomotion_nids) / C.MOTOR_RATE_SCALE))

        for name, nids in self._connectivity_groups.items():
            vec[f"conn_{name}"] = float(np.tanh(self._rate_hz(nids) / C.MOTOR_RATE_SCALE))

        left_rate = self._rate_hz(self._steering_left)
        right_rate = self._rate_hz(self._steering_right)
        vec["yaw_steering"] = float(np.tanh((left_rate - right_rate) / C.MOTOR_RATE_SCALE))

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
