"""
L5 — ponte TCP com o plugin Minecraft.

Protocolo: JSON-lines sobre TCP em BRIDGE_PORT. Ver docs/02-arquitetura.md.

  plugin -> sim : {"t_ms":.., "light":.., "dorsal_light":.., "damage":false,
                    "touch_contact":false, "touch_proximity":false,       # F7/AD-17
                    "raining":false,                                     # F7/AD-17, hygro
                    "alarm_explosion":false, "alarm_hostile_mob":false,   # F8, johnston
                    "sound_music":false,                                 # F8, johnston (24/09/2026)
                    "looming_threat":false,                              # F9, escape (24/09/2026)
                    "food_contact":false,                                # F10, taste (25/09/2026)
                    "thermo_heat":false, "thermo_cold":false,            # F12, thermo (26/09/2026)
                    "mute": ["DNp", "sensory"],               # opcional
                    "stimulate": {"group": "DNg", "amplitude": 3.0}}  # opcional
  sim -> plugin : {"t_ms":.., "motor": {<canal>: valor, ...}, "active_dn":..,
                    "bristle_motor": {<canal>: valor, ...}, "bristle_active_dn":..,  # se bristle_connectome foi passado
                    "hygro_motor": {<canal>: valor, ...}, "hygro_active_dn":..,  # se hygro_connectome foi passado
                    "johnston_motor": {<canal>: valor, ...}, "johnston_active_dn":..,  # se johnston_connectome foi passado
                    "escape_motor": {<canal>: valor, ...}, "escape_active_dn":..,  # se escape_connectome foi passado
                    "taste_motor": {<canal>: valor, ...}, "taste_active_dn":..,  # se taste_connectome foi passado
                    "thermo_motor": {<canal>: valor, ...}, "thermo_active_dn":..}  # se thermo_connectome foi passado

F7/AD-17 — segundo e terceiro `Engine` opcionais pros subcircuitos `bristle`
(toque) e `hygro` (chuva), independentes do ocelar e entre si (AD-17:
"engines separados", não grafo único — ver docs/02-arquitetura.md). F8
(23/09/2026) acrescenta um quarto `Engine`, `johnston` (vento/som), mesmo
padrão. F9 (24/09/2026) acrescenta um quinto, `escape` (fuga por looming,
AD-20) — mesmo padrão, com uma diferença: sua semente (LC4/LPLC2) não é
`super_class == "sensory"`, ver `ingest.build(sensory_cell_types=...)` e
`tools/build_f9_circuit.py`. `damage`/`touch_proximity` (a família de
sensores de toque decidida pelo usuário, ver `plugin/README.md` —
`touch_contact` saiu do OR em 25/09/2026, ver `on_sensor`) combinam em OR
simples — qualquer um presente estimula a semente do `bristle` com
`SENSOR_TOUCH_AMPLITUDE`; `raining` (`World#hasStorm()`)
estimula a semente do `hygro` com `SENSOR_RAIN_AMPLITUDE`; `alarm_explosion`/
`alarm_hostile_mob` (ver `AlarmSensor.java` — deliberadamente mais
restrito que `touch_proximity`, só explosão e mob HOSTIL, achado do
usuário citando Eberl, Hardy & Kernan 2000) e `sound_music` (jukebox
tocando por perto, som AMBIENTE não só ameaça — pedido do usuário,
24/09/2026) combinam em OR e estimulam a semente do `johnston` com
`SENSOR_ALARM_AMPLITUDE`; `looming_threat` (`LoomingSensor.java` — distância
até ameaça mais próxima caindo rápido) OU `damage` (dano real — hit mais
forte que aproximação, escala pra fuga plena, pedido do usuário 25/09/2026)
combinam em OR e estimulam a semente do `escape` com
`SENSOR_LOOMING_AMPLITUDE`; `food_contact` (`TasteSensor.java` — contato
com bloco/item comestível, F10 25/09/2026) estimula a semente do `taste`
com `SENSOR_TASTE_AMPLITUDE`; `thermo_heat`/`thermo_cold` (`ThermalSensor.java`
— fonte de calor/frio por perto, F12 26/09/2026) estimulam SEPARADAMENTE os
TRNs de aquecimento e os de frio do `thermo` com `SENSOR_THERMO_AMPLITUDE`
(cada flag só a sua semente, por `cell_sub_class`). Ausência de estímulo = só a
dinâmica basal (RN-09) roda. Campos `bristle_*`/`hygro_*`/`johnston_*`/
`escape_*`/`taste_*` na resposta só aparecem se `SimulationServer` foi
construído com o `Connectome` correspondente (default `None` nos seis —
sem isso, comportamento idêntico a antes desta mudança, compatível com
`main()` chamado só com o ocelar e com os testes existentes). `hygro_motor`/
`johnston_motor`/`escape_motor`/`taste_motor` são TELEMETRIA — os canais
`hygrotaxis`/`startle`/`escape_drive`/`appetite` (ver `hygro_motor.py`/
`johnston_motor.py`/`escape_motor.py`/`taste_motor.py`) não entram em
`MotorMapping.java` ainda, sem lesão em servidor real validando que afetam
comportamento observável (mesma etapa que `grooming` já passou pro
`bristle`, ver docs/03-roadmap-fases.md F7). `bristle_motor` continua
telemetria pelo mesmo motivo nos canais que não são `grooming` (`conn_*`).

Campo `mute` (F5, ferramenta de lesão por comando): lista de nomes de grupo a
silenciar (os 8 grupos de `motor.groups` + "sensory" = os 273 fotorreceptores).
Quando AUSENTE, o silenciamento atual não muda — só é alterado quando o campo
está presente (mesmo lista vazia, que limpa o silenciamento). Ver
`engine.py::Engine.set_silenced`. Mecanismo DIFERENTE do experimento de lesão
da F4 (que zera `light`, não silencia neurônio nenhum) — aqui a saída
sináptica do grupo é removida da rede de verdade. Só o `Engine` principal
(ocelar) aceita `mute`/`stimulate` — mesmo escopo de sempre, RN-08 curou
`motor.groups` só pra ele.

Campo `stimulate` (F5, estimulação dirigida): injeta corrente extra num grupo
nomeado, SOMADA ao estímulo de luz dos fotorreceptores (não substitui). Mesma
semântica de "ausente = sem mudança" do `mute`. `{"group": null}` ou
`{"group": "", "amplitude": 0}` limpa o estímulo dirigido. Ver
`engine.py::Engine.set_directed_stimulus`.

RN-06 — cada circuito roda a dt=1 ms em PROCESSO próprio (F16, 30/09/2026 —
ver docs/03-roadmap-fases.md), desacoplado do tick do jogo E dos outros
circuitos. O jogo nunca espera o simulador terminar passos extras: cada linha
de sensor recebida atualiza o estímulo compartilhado e recebe de volta, na
hora, o ÚLTIMO vetor motor já computado por cada processo — nunca um vetor
calculado sob demanda.

**F16 — por que multiprocessing, não threading.** Medido (29/09/2026): os 7
circuitos somados num laço único (thread) já usavam 99,6% do orçamento de
1ms/tick — quase zero de sobra pra um circuito novo (ver F16 em
docs/03-roadmap-fases.md). Threading Python deu só 1,22x de aceleração com 2
engines (GIL trava a maior parte da execução, só o trecho dentro de
numpy/scipy solta) — não resolve. Multiprocessing (processos de verdade,
sem GIL) mediu 0,407ms/tick pro mais lento dos 7 rodando em paralelo — 59,3%
de sobra — DESDE QUE as threads internas de BLAS (OpenBLAS/MKL, que cada
processo abriria por conta própria) sejam limitadas a 1 por processo (ver
`os.environ` logo abaixo); sem isso, multiprocessing dá 0,666ms/tick — pior
que threading, melhor que nada, mas deixando a maior parte do ganho na mesa.
**Armadilha registrada em CONVENCOES.md:** presumir paralelismo grátis com
numpy/scipy sem controlar threads de BLAS pode piorar as coisas.

Cada `Engine`/`MotorDecoder` (F5-F12) vive inteiramente dentro do seu próprio
processo — nenhum outro processo (nem o coordenador/`SimulationServer`) toca
nesse estado depois de criado. Coordenação entre processos usa só dois
mecanismos, os dois deliberadamente pequenos e de baixa frequência:
(1) um `multiprocessing.Array` compartilhado com os valores de estímulo
crus (`light`, `touch`, `raining`, ...), lido por cada processo a cada tick
seu (1ms) — não é fila, não bloqueia, é leitura de memória compartilhada; e
(2) uma fila de saída por circuito (`maxsize=1`, sempre substitui o valor
antigo em vez de acumular) onde cada processo publica o canal decodificado a
cada janela de 50ms (`MOTOR_WINDOW_MS`) — o coordenador drena tudo que
chegou e guarda só o mais recente de cada circuito, nunca espera. `mute`/
`stimulate` (F5, só o `Engine` principal) usam uma fila de controle própria,
com a mesma semântica "só o mais recente pendente vale" que a versão de
thread única já tinha.

Canais do vetor motor: RN-08 segue em aberto (ver motor.py) — os nomes de
canal são os grupos provisórios por prefixo de `cell_type` (DNp, DNg, ...),
não "forward"/"yaw"/"lift" como o contrato aspiracional de
`docs/02-arquitetura.md` descreve. Reconciliar quando RN-08 tiver curadoria.

Mapeamento sensor -> estímulo (`light` -> corrente nos fotorreceptores) é
provisório — ver `SENSOR_LIGHT_GAIN` em config.py.

Se o plugin cair, o simulador continua rodando — a ciência não depende do
jogo. Sem handshake, sem estado de sessão: reconexão é reinício da conexão,
não do simulador.
"""
from __future__ import annotations

import os

# F16 (29-30/09/2026) — CRÍTICO, precisa rodar antes de QUALQUER import que
# traga numpy/scipy (graph, engine, motor, numpy em si logo abaixo). Sem
# isto, cada processo worker abre várias threads internas de BLAS por conta
# própria, e N processos disputando os mesmos núcleos entre si deixa
# multiprocessing PIOR que sequencial, não melhor (medido, ver docstring do
# módulo e docs/03-roadmap-fases.md F16: 0,666ms/tick sem isto, 0,407ms/tick
# com). `setdefault` — respeita override explícito do operador (ex.:
# container com núcleos de sobra reservados só pra isto), não força.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")

import json
import multiprocessing as mp
import queue as queue_module
import socketserver
import threading
import time
from collections.abc import Callable
from typing import Any, Self

import numpy as np

from . import config as C
from . import graph
from .bristle_motor import BristleMotorDecoder
from .engine import Engine
from .escape_motor import EscapeMotorDecoder
from .graph import Connectome
from .hygro_motor import HygroMotorDecoder
from .johnston_motor import JohnstonMotorDecoder
from .motor import MotorDecoder, group_by_published_behavior, group_steering_by_side
from .taste_motor import TasteMotorDecoder
from .thermo_motor import ThermoMotorDecoder

# F16 — índices no `multiprocessing.Array` de estímulo compartilhado. Um
# array só, 8 posições, em vez de um por circuito — todos os sensores crus
# já eram computados juntos em `on_sensor` antes desta mudança, então
# continuam sendo escritos juntos aqui (mesma seção crítica de sempre, só
# que agora em memória compartilhada entre processos em vez de atributos de
# instância lidos por uma thread).
_STIM_LIGHT = 0
_STIM_TOUCH = 1
_STIM_RAINING = 2
_STIM_ALARM = 3
_STIM_LOOMING = 4
_STIM_FOOD = 5
_STIM_THERMO_HEAT = 6
_STIM_THERMO_COLD = 7
_STIM_SIZE = 8

# F16 — cada circuito tem sua própria "receita" de estímulo (quais nids,
# que amplitude) — funções puras de nível de módulo (picklable, não
# closures) em vez de método de classe, porque rodam DENTRO do processo
# worker, chamadas por `_engine_worker`. `heating_nids`/`cold_nids` só têm
# uso real na receita do `thermo`; as outras ignoram (mesma assinatura pra
# manter o laço do worker genérico).
StimulusFn = Callable[[Any, Connectome, np.ndarray, np.ndarray], tuple[np.ndarray, float]]


def _stim_main(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), vals[_STIM_LIGHT] * C.SENSOR_LIGHT_GAIN


def _stim_bristle(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_TOUCH_AMPLITUDE if vals[_STIM_TOUCH] else 0.0)


def _stim_hygro(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_RAIN_AMPLITUDE if vals[_STIM_RAINING] else 0.0)


def _stim_johnston(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_ALARM_AMPLITUDE if vals[_STIM_ALARM] else 0.0)


def _stim_escape(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_LOOMING_AMPLITUDE if vals[_STIM_LOOMING] else 0.0)


def _stim_taste(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_TASTE_AMPLITUDE if vals[_STIM_FOOD] else 0.0)


def _stim_thermo(vals: Any, cc: Connectome, heat_nids: np.ndarray, cold_nids: np.ndarray) -> tuple[np.ndarray, float]:
    driven = []
    if vals[_STIM_THERMO_HEAT]:
        driven.append(heat_nids)
    if vals[_STIM_THERMO_COLD]:
        driven.append(cold_nids)
    nids = np.concatenate(driven) if driven else np.array([], dtype=np.int64)
    return nids, C.SENSOR_THERMO_AMPLITUDE


# nome -> (DecoderFactory, StimulusFn) dos seis circuitos opcionais. "main"
# (ocelar) é tratado à parte em SimulationServer.__init__ porque é o único
# obrigatório e o único que aceita mute/stimulate.
_OPTIONAL_CIRCUITS: dict[str, tuple[type, StimulusFn]] = {
    "bristle": (BristleMotorDecoder, _stim_bristle),
    "hygro": (HygroMotorDecoder, _stim_hygro),
    "johnston": (JohnstonMotorDecoder, _stim_johnston),
    "escape": (EscapeMotorDecoder, _stim_escape),
    "taste": (TasteMotorDecoder, _stim_taste),
    "thermo": (ThermoMotorDecoder, _stim_thermo),
}


def _engine_worker(
    name: str,
    connectome: Connectome,
    decoder_cls: type,
    stimulus_fn: StimulusFn,
    stim_array: Any,
    output_queue: mp.Queue,
    stop_event: Any,
    control_queue: mp.Queue | None,
) -> None:
    """F16 — laço de um circuito, rodando em PROCESSO próprio a dt=1ms real.

    `control_queue` não-`None` só pro worker do `main` (mute/stimulate, F5)
    — os outros seis sempre recebem `None`. Ver docstring do módulo pro
    desenho geral (array de estímulo compartilhado + fila de saída
    `maxsize=1`).
    """
    engine = Engine(connectome)
    decoder = decoder_cls(connectome)

    # F5 — só o worker principal resolve nome de grupo -> nids (precisa do
    # MotorDecoder.groups DESTE engine específico, RN-08). Construído aqui
    # dentro (não no coordenador) porque agora é o único lugar onde o
    # MotorDecoder do ocelar existe de verdade.
    group_lookup: dict[str, np.ndarray] | None = None
    if control_queue is not None:
        group_lookup = dict(decoder.groups)
        group_lookup.update(group_by_published_behavior(connectome))
        group_lookup["sensory"] = connectome.sensory
        steering_left, steering_right = group_steering_by_side(connectome)
        group_lookup["steering_left"] = steering_left
        group_lookup["steering_right"] = steering_right

    # F12 — só o `thermo` usa isto (as duas sementes separadas por
    # cell_sub_class); as outras receitas de estímulo ignoram.
    sensory_arr = np.asarray(connectome.sensory)
    heating_nids = np.array([], dtype=np.int64)
    cold_nids = np.array([], dtype=np.int64)
    if name == "thermo":
        sub_class = connectome.nodes.loc[sensory_arr, "cell_sub_class"].to_numpy()
        heating_nids = sensory_arr[sub_class == "heating"]
        cold_nids = sensory_arr[sub_class == "cold"]

    period_s = C.DT_MS / 1000.0
    window_steps = max(int(C.MOTOR_WINDOW_MS), 1)
    next_tick = time.monotonic()

    while not stop_event.is_set():
        if control_queue is not None:
            # F5 — drena tudo que chegou desde o último tick, mas só aplica
            # o MAIS RECENTE de cada tipo (mesma semântica "só o pendente
            # mais novo vale" da versão de thread única — não acumula).
            last_mute: list[str] | None = None
            last_stimulate: dict[str, Any] | None = None
            while True:
                try:
                    kind, payload = control_queue.get_nowait()
                except queue_module.Empty:
                    break
                if kind == "mute":
                    last_mute = payload
                else:
                    last_stimulate = payload
            if last_mute is not None:
                nids: list[int] = []
                for gname in last_mute:
                    group = group_lookup.get(gname)  # type: ignore[union-attr]
                    if group is not None:
                        nids.extend(int(n) for n in group)
                engine.set_silenced(np.array(nids, dtype=np.int64))
            if last_stimulate is not None:
                gname = last_stimulate.get("group")
                amplitude = float(last_stimulate.get("amplitude", 0.0))
                group = group_lookup.get(gname) if gname else None  # type: ignore[union-attr]
                if group is None:
                    engine.set_directed_stimulus(np.array([], dtype=np.int64), 0.0)
                else:
                    engine.set_directed_stimulus(np.asarray(group, dtype=np.int64), amplitude)

        vals = stim_array[:]  # cópia rápida da memória compartilhada, sem segurar lock além disso
        nids, amplitude = stimulus_fn(vals, connectome, heating_nids, cold_nids)
        engine.stimulate(nids, amplitude)
        frame = engine.step()
        decoder.push(frame.t_ms, frame.spikes)

        if frame.t_ms % window_steps == 0:
            payload = {
                "t_ms": frame.t_ms,
                "channels": decoder.decode(),
                "active_dn": decoder.active_output_count(),
            }
            # maxsize=1 — sempre substitui o antigo, nunca acumula (RN-06:
            # o coordenador só quer o mais recente, nunca espera).
            try:
                output_queue.get_nowait()
            except queue_module.Empty:
                pass
            try:
                output_queue.put_nowait(payload)
            except queue_module.Full:
                pass

        next_tick += period_s
        sleep_for = next_tick - time.monotonic()
        if sleep_for > 0:
            time.sleep(sleep_for)
        else:
            next_tick = time.monotonic()  # atrasado — não acumula dívida


class _Handler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        bridge: SimulationServer = self.server.bridge  # type: ignore[attr-defined]
        for raw_line in self.rfile:
            line = raw_line.decode("utf-8").strip()
            if not line:
                continue
            try:
                sensor = json.loads(line)
            except json.JSONDecodeError:
                continue
            bridge.on_sensor(sensor)
            response = bridge.latest_frame()
            self.wfile.write((json.dumps(response) + "\n").encode("utf-8"))
            self.wfile.flush()


class _TCPServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


class SimulationServer:
    """Servidor TCP + um processo por circuito, cada um a dt=1ms real (F16).

    Uso:

        cc = graph.load()
        with SimulationServer(cc) as srv:
            ...  # srv.port, roda até sair do bloco

    F7/AD-17 — `bristle_connectome`/`hygro_connectome` opcionais ligam um
    segundo e um terceiro `Engine` independentes (toque, chuva), cada um no
    seu próprio processo (F16), sem misturar estado com o ocelar nem entre
    si. F8 acrescenta `johnston_connectome` (vento/som). F9 acrescenta
    `escape_connectome` (fuga por looming, AD-20). F10 acrescenta
    `taste_connectome` (paladar apetitivo). F12 acrescenta
    `thermo_connectome` (calor/frio). `None` (default, nos seis) preserva o
    comportamento de antes desta mudança — nenhum teste existente ou
    chamada antiga precisa mudar.
    """

    def __init__(
        self,
        connectome: Connectome,
        bristle_connectome: Connectome | None = None,
        hygro_connectome: Connectome | None = None,
        johnston_connectome: Connectome | None = None,
        escape_connectome: Connectome | None = None,
        taste_connectome: Connectome | None = None,
        thermo_connectome: Connectome | None = None,
        host: str = C.BRIDGE_HOST,
        port: int = C.BRIDGE_PORT,
    ) -> None:
        self.connectome = connectome
        self.bristle_connectome = bristle_connectome
        self.hygro_connectome = hygro_connectome
        self.johnston_connectome = johnston_connectome
        self.escape_connectome = escape_connectome
        self.taste_connectome = taste_connectome
        self.thermo_connectome = thermo_connectome

        # F12 — mesma metadata de sempre (7 TRNs de aquecimento, 9 de frio),
        # computada aqui só pra introspecção/testes (ex.: `srv.
        # _thermo_heating_nids` nos testes) — o worker do thermo recalcula a
        # própria cópia de forma independente, a partir do MESMO connectome.
        if thermo_connectome is not None:
            sensory = np.asarray(thermo_connectome.sensory)
            sub_class = thermo_connectome.nodes.loc[sensory, "cell_sub_class"].to_numpy()
            self._thermo_heating_nids = sensory[sub_class == "heating"]
            self._thermo_cold_nids = sensory[sub_class == "cold"]
        else:
            self._thermo_heating_nids = np.array([], dtype=np.int64)
            self._thermo_cold_nids = np.array([], dtype=np.int64)

        # F16 — 'spawn' explícito nos dois SOs (Windows já só tem spawn;
        # Linux/Docker default seria 'fork', mas forkar um processo com
        # threads vivas — o servidor TCP já usa threads — é terreno
        # arriscado no POSIX. 'spawn' sempre recomeça do zero, sem herdar
        # estado de thread nenhum, mesmo comportamento nos dois ambientes.
        self._mp_ctx = mp.get_context("spawn")
        self._stim_array = self._mp_ctx.Array("d", _STIM_SIZE)
        self._stop_event = self._mp_ctx.Event()
        self._control_queue: mp.Queue = self._mp_ctx.Queue()

        # F16 — só protege as duas estruturas coordenador-side que podem
        # ser lidas/escritas por threads handler concorrentes (múltiplas
        # conexões simultâneas, teoricamente — o protocolo assume uma só,
        # mas não custa nada proteger). NÃO protege o `_stim_array` (esse já
        # tem seu próprio lock interno, `multiprocessing.Array` default) nem
        # as filas (essas são thread/processo-safe por natureza).
        self._lock = threading.Lock()
        self._touch = False  # F7/AD-17 — OR de damage/touch_proximity (F9: touch_contact saiu, ver on_sensor)
        self._raining = False  # F7/AD-17 — World#hasStorm(), estímulo da semente do hygro
        self._alarm = False  # F8 — OR de alarm_explosion/alarm_hostile_mob, estímulo da semente do johnston
        self._looming = False  # F9 — looming_threat, estímulo da semente do escape
        self._food = False  # F10 — food_contact, estímulo da semente do taste
        self._thermo_heat = False  # F12 — thermo_heat, estímulo dos TRNs de aquecimento
        self._thermo_cold = False  # F12 — thermo_cold, estímulo dos TRNs de frio
        self._latest: dict[str, Any] = {"t_ms": 0, "motor": {}, "active_dn": 0}
        self._latest_by_circuit: dict[str, dict[str, Any]] = {}

        self._processes: list[Any] = []
        self._output_queues: dict[str, mp.Queue] = {}

        main_output_queue = self._mp_ctx.Queue(maxsize=1)
        self._output_queues["_main"] = main_output_queue
        self._processes.append(self._mp_ctx.Process(
            target=_engine_worker,
            args=("main", connectome, MotorDecoder, _stim_main, self._stim_array,
                  main_output_queue, self._stop_event, self._control_queue),
            daemon=True, name="flywire-engine-main",
        ))

        circuit_connectomes = {
            "bristle": bristle_connectome, "hygro": hygro_connectome,
            "johnston": johnston_connectome, "escape": escape_connectome,
            "taste": taste_connectome, "thermo": thermo_connectome,
        }
        for name, (decoder_cls, stim_fn) in _OPTIONAL_CIRCUITS.items():
            cc = circuit_connectomes[name]
            if cc is None:
                continue
            oq = self._mp_ctx.Queue(maxsize=1)
            self._output_queues[name] = oq
            self._processes.append(self._mp_ctx.Process(
                target=_engine_worker,
                args=(name, cc, decoder_cls, stim_fn, self._stim_array, oq,
                      self._stop_event, None),
                daemon=True, name=f"flywire-engine-{name}",
            ))

        self._tcp = _TCPServer((host, port), _Handler)
        self._tcp.bridge = self  # type: ignore[attr-defined]
        self._serve_thread = threading.Thread(
            target=self._tcp.serve_forever, name="flywire-tcp-serve", daemon=True
        )

    @property
    def port(self) -> int:
        return self._tcp.server_address[1]

    def start(self) -> None:
        # F16 — processos ANTES da thread TCP: evita a combinação
        # fork+threads-vivas mesmo que 'spawn' já torne isso improvável de
        # dar problema — hábito seguro, sem custo.
        for p in self._processes:
            p.start()
        self._serve_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._tcp.shutdown()
        self._tcp.server_close()
        for p in self._processes:
            p.join(timeout=2.0)
            if p.is_alive():
                p.terminate()

    def on_sensor(self, sensor: dict[str, Any]) -> None:
        """Chamado pela thread de conexão a cada linha recebida.

        Só atualiza o estado compartilhado — nunca chama engine.step() nem
        espera nenhum processo de simulação (RN-06).
        """
        light = float(sensor.get("light", 0.0))
        # F7/AD-17 — família de sensores de toque (damage já existia, os
        # outros dois são novos, ver docs/02-arquitetura.md).
        #
        # F9 (25/09/2026, pedido do usuário) — `touch_contact` (esbarrar em
        # BLOCO/parede/chão) saiu do OR: bater numa parede durante o voo não
        # é o mesmo estímulo biológico de algo pousar/tocar o corpo da mosca
        # (mesmo princípio já usado no `AlarmSensor` do johnston — Eberl,
        # Hardy & Kernan 2000: toque com objeto inofensivo não deveria
        # disparar a mesma resposta que contato real). O campo continua
        # chegando no protocolo (telemetria/diagnóstico do lado do plugin,
        # ver `TouchSensor.java`), só não estimula mais a semente do
        # `bristle` aqui. `touch_proximity` (TouchSensor.isNearSomething)
        # também foi restrito nesta mesma sessão pra só contar mob/animal/
        # NPC/jogador, não qualquer entidade — ver `TouchSensor.java`.
        touch = bool(sensor.get("damage", False)) or bool(sensor.get("touch_proximity", False))
        raining = bool(sensor.get("raining", False))
        # F8 — família de sensores de alarme (ver AlarmSensor.java): OR
        # simples, mesma lógica de `touch` acima.
        alarm = bool(sensor.get("alarm_explosion", False)) or bool(sensor.get("alarm_hostile_mob", False)) \
            or bool(sensor.get("sound_music", False))
        # F9 — `damage` (dano real, DamageTracker) também estimula a semente
        # do `escape`, não só `looming_threat` (25/09/2026, pedido do
        # usuário): levar um HIT de verdade é um sinal de ameaça mais forte
        # que taxa de aproximação — deveria escalar pra fuga plena, não só
        # pro toque/grooming. Mesmo `damage` que já estimula `bristle`
        # (linha `touch` acima) — um hit real dispara os dois circuitos,
        # não é exclusivo.
        looming = bool(sensor.get("looming_threat", False)) or bool(sensor.get("damage", False))
        # F10 — contato com bloco/item comestível (ver TasteSensor.java)
        # estimula a semente do `taste`.
        food = bool(sensor.get("food_contact", False))
        # F12 — fonte de calor/frio por perto (ver ThermalSensor.java).
        thermo_heat = bool(sensor.get("thermo_heat", False))
        thermo_cold = bool(sensor.get("thermo_cold", False))
        mute = sensor.get("mute")
        stimulate = sensor.get("stimulate")

        # F16 — grava no array compartilhado (lido por CADA processo a cada
        # tick seu). `get_lock()` protege só a seção crítica de escrita, não
        # segura nada além disso.
        with self._stim_array.get_lock():
            self._stim_array[_STIM_LIGHT] = light
            self._stim_array[_STIM_TOUCH] = 1.0 if touch else 0.0
            self._stim_array[_STIM_RAINING] = 1.0 if raining else 0.0
            self._stim_array[_STIM_ALARM] = 1.0 if alarm else 0.0
            self._stim_array[_STIM_LOOMING] = 1.0 if looming else 0.0
            self._stim_array[_STIM_FOOD] = 1.0 if food else 0.0
            self._stim_array[_STIM_THERMO_HEAT] = 1.0 if thermo_heat else 0.0
            self._stim_array[_STIM_THERMO_COLD] = 1.0 if thermo_cold else 0.0

        with self._lock:
            self._touch = touch
            self._raining = raining
            self._alarm = alarm
            self._looming = looming
            self._food = food
            self._thermo_heat = thermo_heat
            self._thermo_cold = thermo_cold

        # F5 — só manda pra fila de controle (worker principal) quando o
        # campo está PRESENTE — mesma semântica "ausente = sem mudança" de
        # sempre, agora expressa como "não manda mensagem nenhuma" em vez
        # de "não sobrescreve o campo pendente".
        if mute is not None:
            self._control_queue.put(("mute", list(mute)))
        if stimulate is not None:
            self._control_queue.put(("stimulate", dict(stimulate)))

    def latest_frame(self) -> dict[str, Any]:
        """Drena o que chegou de cada processo e monta a resposta — nunca
        bloqueia, nunca espera um processo terminar um passo (RN-06)."""
        with self._lock:
            for name, q in self._output_queues.items():
                payload = None
                try:
                    while True:
                        payload = q.get_nowait()
                except queue_module.Empty:
                    pass
                if payload is not None:
                    self._latest_by_circuit[name] = payload

            main_payload = self._latest_by_circuit.get("_main")
            if main_payload is None:
                return dict(self._latest)  # antes do primeiro frame do main chegar

            result: dict[str, Any] = {
                "t_ms": main_payload["t_ms"],
                "motor": main_payload["channels"],
                "active_dn": main_payload["active_dn"],
            }
            for name in self._output_queues:
                if name == "_main":
                    continue
                cached = self._latest_by_circuit.get(name)
                if cached is not None:
                    result[f"{name}_motor"] = cached["channels"]
                    result[f"{name}_active_dn"] = cached["active_dn"]
            self._latest = result
            return dict(result)

    def __enter__(self) -> Self:
        self.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.stop()


def main() -> None:
    cc = graph.load()
    # F7/AD-17 — carrega o bristle se já foi extraído
    # (`python tools/build_f7_circuits.py`); degrada de volta pro
    # comportamento anterior (só ocelar) se ainda não foi, em vez de falhar.
    bristle_cc: Connectome | None = None
    try:
        bristle_cc = graph.load(C.PROCESSED / "bristle")
        print("bristle: subcircuito de toque carregado (F7/AD-17)", flush=True)
    except FileNotFoundError:
        print(
            "bristle: data/processed/bristle/ não encontrado — rodando só o circuito "
            "ocelar (python tools/build_f7_circuits.py pra gerar)",
            flush=True,
        )
    hygro_cc: Connectome | None = None
    try:
        hygro_cc = graph.load(C.PROCESSED / "hygro")
        print("hygro: subcircuito de chuva carregado (F7/AD-17)", flush=True)
    except FileNotFoundError:
        print(
            "hygro: data/processed/hygro/ não encontrado — rodando sem ele "
            "(python tools/build_f7_circuits.py pra gerar)",
            flush=True,
        )
    johnston_cc: Connectome | None = None
    try:
        johnston_cc = graph.load(C.PROCESSED / "johnston")
        print("johnston: subcircuito de vento/som carregado (F8)", flush=True)
    except FileNotFoundError:
        print(
            "johnston: data/processed/johnston/ não encontrado — rodando sem ele "
            "(python tools/build_f8_circuit.py pra gerar)",
            flush=True,
        )
    escape_cc: Connectome | None = None
    try:
        escape_cc = graph.load(C.PROCESSED / "escape")
        print("escape: subcircuito de fuga/looming carregado (F9)", flush=True)
    except FileNotFoundError:
        print(
            "escape: data/processed/escape/ não encontrado — rodando sem ele "
            "(python tools/build_f9_circuit.py pra gerar)",
            flush=True,
        )
    taste_cc: Connectome | None = None
    try:
        taste_cc = graph.load(C.PROCESSED / "taste")
        print("taste: subcircuito de paladar carregado (F10)", flush=True)
    except FileNotFoundError:
        print(
            "taste: data/processed/taste/ não encontrado — rodando sem ele "
            "(python tools/build_f10_circuit.py pra gerar)",
            flush=True,
        )
    thermo_cc: Connectome | None = None
    try:
        thermo_cc = graph.load(C.PROCESSED / "thermo")
        print("thermo: subcircuito de temperatura carregado (F12)", flush=True)
    except FileNotFoundError:
        print(
            "thermo: data/processed/thermo/ não encontrado — rodando sem ele "
            "(python tools/build_f12_circuit.py pra gerar)",
            flush=True,
        )
    with SimulationServer(
        cc, bristle_cc, hygro_cc, johnston_cc, escape_cc, taste_cc, thermo_cc
    ) as srv:
        print(f"flywire-sim escutando em {C.BRIDGE_HOST}:{srv.port}", flush=True)
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
