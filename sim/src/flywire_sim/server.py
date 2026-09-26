"""
L5 — ponte TCP com o plugin Minecraft.

Protocolo: JSON-lines sobre TCP em BRIDGE_PORT. Ver docs/02-arquitetura.md.

  plugin -> sim : {"t_ms":.., "light":.., "dorsal_light":.., "damage":false,
                    "touch_contact":false, "touch_proximity":false,       # F7/AD-17
                    "raining":false,                                     # F7/AD-17, hygro
                    "alarm_explosion":false, "alarm_hostile_mob":false,   # F8, johnston
                    "sound_music":false,                                 # F8, johnston (24/09/2026)
                    "looming_threat":false,                              # F9, escape (24/09/2026)
                    "mute": ["DNp", "sensory"],               # opcional
                    "stimulate": {"group": "DNg", "amplitude": 3.0}}  # opcional
  sim -> plugin : {"t_ms":.., "motor": {<canal>: valor, ...}, "active_dn":..,
                    "bristle_motor": {<canal>: valor, ...}, "bristle_active_dn":..,  # se bristle_connectome foi passado
                    "hygro_motor": {<canal>: valor, ...}, "hygro_active_dn":..,  # se hygro_connectome foi passado
                    "johnston_motor": {<canal>: valor, ...}, "johnston_active_dn":..,  # se johnston_connectome foi passado
                    "escape_motor": {<canal>: valor, ...}, "escape_active_dn":..}  # se escape_connectome foi passado

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
`SENSOR_LOOMING_AMPLITUDE`. Ausência de estímulo = só a
dinâmica basal (RN-09) roda. Campos `bristle_*`/`hygro_*`/`johnston_*`/
`escape_*` na resposta só aparecem se `SimulationServer` foi construído
com o `Connectome` correspondente (default `None` nos quatro — sem isso,
comportamento idêntico a antes desta mudança, compatível com `main()`
chamado só com o ocelar e com os testes existentes). `hygro_motor`/
`johnston_motor`/`escape_motor` são TELEMETRIA — os canais `hygrotaxis`/
`startle`/`escape_drive` (ver `hygro_motor.py`/`johnston_motor.py`/
`escape_motor.py`) não entram em `MotorMapping.java` ainda, sem lesão em
servidor real validando que afetam comportamento observável (mesma etapa
que `grooming` já passou pro `bristle`, ver docs/03-roadmap-fases.md F7).
`bristle_motor` continua telemetria pelo mesmo motivo nos canais que não
são `grooming` (`conn_*`).

Campo `mute` (F5, ferramenta de lesão por comando): lista de nomes de grupo a
silenciar (os 8 grupos de `motor.groups` + "sensory" = os 273 fotorreceptores).
Quando AUSENTE, o silenciamento atual não muda — só é alterado quando o campo
está presente (mesmo lista vazia, que limpa o silenciamento). Ver
`engine.py::Engine.set_silenced`. Mecanismo DIFERENTE do experimento de lesão
da F4 (que zera `light`, não silencia neurônio nenhum) — aqui a saída
sináptica do grupo é removida da rede de verdade.

Campo `stimulate` (F5, estimulação dirigida): injeta corrente extra num grupo
nomeado, SOMADA ao estímulo de luz dos fotorreceptores (não substitui). Mesma
semântica de "ausente = sem mudança" do `mute`. `{"group": null}` ou
`{"group": "", "amplitude": 0}` limpa o estímulo dirigido. Ver
`engine.py::Engine.set_directed_stimulus`.

RN-06 — o simulador roda em thread própria a dt=1 ms, desacoplado do tick do
jogo. O jogo nunca espera o simulador terminar passos extras: cada linha de
sensor recebida atualiza o estímulo e recebe de volta, na hora, o ÚLTIMO vetor
motor já computado pela thread de simulação — nunca um vetor calculado sob
demanda.

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

import json
import socketserver
import threading
import time
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
    """Servidor TCP + thread de simulação desacoplada (RN-06).

    Uso:

        cc = graph.load()
        with SimulationServer(cc) as srv:
            ...  # srv.port, roda até sair do bloco

    F7/AD-17 — `bristle_connectome`/`hygro_connectome` opcionais ligam um
    segundo e um terceiro `Engine` independentes (toque, chuva), rodando no
    mesmo laço/mesmo dt, sem misturar estado com o ocelar nem entre si. F8
    acrescenta `johnston_connectome` (vento/som), um quarto `Engine`, mesmo
    padrão. F9 acrescenta `escape_connectome` (fuga por looming, AD-20), um
    quinto `Engine`, mesmo padrão. `None` (default, nos quatro) preserva o
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
        host: str = C.BRIDGE_HOST,
        port: int = C.BRIDGE_PORT,
    ) -> None:
        self.connectome = connectome
        self.engine = Engine(connectome)
        self.motor = MotorDecoder(connectome)

        # F7/AD-17 — segundo Engine, só existe se bristle_connectome foi dado.
        self.bristle_connectome = bristle_connectome
        if bristle_connectome is not None:
            self.bristle_engine: Engine | None = Engine(bristle_connectome)
            self.bristle_motor: BristleMotorDecoder | None = BristleMotorDecoder(bristle_connectome)
        else:
            self.bristle_engine = None
            self.bristle_motor = None

        # F7/AD-17 — terceiro Engine, só existe se hygro_connectome foi dado.
        self.hygro_connectome = hygro_connectome
        if hygro_connectome is not None:
            self.hygro_engine: Engine | None = Engine(hygro_connectome)
            self.hygro_motor: HygroMotorDecoder | None = HygroMotorDecoder(hygro_connectome)
        else:
            self.hygro_engine = None
            self.hygro_motor = None

        # F8 — quarto Engine, só existe se johnston_connectome foi dado.
        self.johnston_connectome = johnston_connectome
        if johnston_connectome is not None:
            self.johnston_engine: Engine | None = Engine(johnston_connectome)
            self.johnston_motor: JohnstonMotorDecoder | None = JohnstonMotorDecoder(johnston_connectome)
        else:
            self.johnston_engine = None
            self.johnston_motor = None

        # F9 — quinto Engine, só existe se escape_connectome foi dado.
        self.escape_connectome = escape_connectome
        if escape_connectome is not None:
            self.escape_engine: Engine | None = Engine(escape_connectome)
            self.escape_motor: EscapeMotorDecoder | None = EscapeMotorDecoder(escape_connectome)
        else:
            self.escape_engine = None
            self.escape_motor = None

        self._lock = threading.Lock()
        self._light = 0.0
        self._touch = False  # F7/AD-17 — OR de damage/touch_proximity (F9: touch_contact saiu, ver on_sensor)
        self._raining = False  # F7/AD-17 — World#hasStorm(), estímulo da semente do hygro
        self._alarm = False  # F8 — OR de alarm_explosion/alarm_hostile_mob, estímulo da semente do johnston
        self._looming = False  # F9 — looming_threat, estímulo da semente do escape
        self._pending_mute: list[str] | None = None
        self._pending_stimulate: dict[str, Any] | None = None
        self._latest: dict[str, Any] = {"t_ms": 0, "motor": {}, "active_dn": 0}
        self._stop = threading.Event()

        # F5 — nomes válidos para os campos "mute"/"stimulate": os 8 grupos
        # por prefixo + os grupos de comportamento publicado (RN-08, ver
        # motor.py) + "sensory" (fotorreceptores, fora de motor.groups
        # porque esse dict só cobre descendentes).
        self._group_lookup: dict[str, np.ndarray] = dict(self.motor.groups)
        self._group_lookup.update(group_by_published_behavior(connectome))
        self._group_lookup["sensory"] = connectome.sensory
        # F6/AD-16 — "steering_left"/"steering_right" nomeáveis por stimulate,
        # pra validar o sentido do canal yaw_steering (estimular só um lado
        # do par bilateral e medir se a abelha vira de forma consistente).
        steering_left, steering_right = group_steering_by_side(connectome)
        self._group_lookup["steering_left"] = steering_left
        self._group_lookup["steering_right"] = steering_right

        self._tcp = _TCPServer((host, port), _Handler)
        self._tcp.bridge = self  # type: ignore[attr-defined]
        self._sim_thread = threading.Thread(
            target=self._sim_loop, name="flywire-sim-loop", daemon=True
        )
        self._serve_thread = threading.Thread(
            target=self._tcp.serve_forever, name="flywire-tcp-serve", daemon=True
        )

    @property
    def port(self) -> int:
        return self._tcp.server_address[1]

    def start(self) -> None:
        self._sim_thread.start()
        self._serve_thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._tcp.shutdown()
        self._tcp.server_close()
        self._sim_thread.join(timeout=2.0)

    def on_sensor(self, sensor: dict[str, Any]) -> None:
        """Chamado pela thread de conexão a cada linha recebida.

        Só atualiza o estado compartilhado — nunca chama engine.step() nem
        espera a thread de simulação (RN-06).
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
        mute = sensor.get("mute")
        stimulate = sensor.get("stimulate")
        with self._lock:
            self._light = light
            self._touch = touch
            self._raining = raining
            self._alarm = alarm
            self._looming = looming
            if mute is not None:
                self._pending_mute = list(mute)
            if stimulate is not None:
                self._pending_stimulate = dict(stimulate)

    def latest_frame(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._latest)

    def _apply_mute(self, group_names: list[str]) -> None:
        """Roda só na thread de simulação — única que toca em self.engine."""
        nids: list[int] = []
        for name in group_names:
            group = self._group_lookup.get(name)
            if group is None:
                continue
            nids.extend(int(n) for n in group)
        self.engine.set_silenced(np.array(nids, dtype=np.int64))

    def _apply_stimulate(self, spec: dict[str, Any]) -> None:
        """Roda só na thread de simulação — única que toca em self.engine."""
        name = spec.get("group")
        amplitude = float(spec.get("amplitude", 0.0))
        group = self._group_lookup.get(name) if name else None
        if group is None:
            self.engine.set_directed_stimulus(np.array([], dtype=np.int64), 0.0)
        else:
            self.engine.set_directed_stimulus(np.asarray(group, dtype=np.int64), amplitude)

    def _sim_loop(self) -> None:
        """Roda a dt=1 ms em tempo real, independente de qualquer conexão."""
        period_s = C.DT_MS / 1000.0
        next_tick = time.monotonic()
        window_steps = max(int(C.MOTOR_WINDOW_MS), 1)

        while not self._stop.is_set():
            with self._lock:
                light = self._light
                touch = self._touch
                raining = self._raining
                alarm = self._alarm
                looming = self._looming
                mute = self._pending_mute
                self._pending_mute = None
                stimulate = self._pending_stimulate
                self._pending_stimulate = None
            if mute is not None:
                self._apply_mute(mute)
            if stimulate is not None:
                self._apply_stimulate(stimulate)

            self.engine.stimulate(self.connectome.sensory, light * C.SENSOR_LIGHT_GAIN)
            frame = self.engine.step()
            self.motor.push(frame.t_ms, frame.spikes)

            # F7/AD-17 — segundo Engine, passo próprio, mesmo dt/tempo real
            # do laço principal, estado nunca compartilhado com o ocelar.
            if self.bristle_engine is not None and self.bristle_motor is not None:
                amplitude = C.SENSOR_TOUCH_AMPLITUDE if touch else 0.0
                self.bristle_engine.stimulate(self.bristle_connectome.sensory, amplitude)
                bristle_frame = self.bristle_engine.step()
                self.bristle_motor.push(bristle_frame.t_ms, bristle_frame.spikes)

            # F7/AD-17 — terceiro Engine, mesma mecânica do bristle acima.
            if self.hygro_engine is not None and self.hygro_motor is not None:
                amplitude = C.SENSOR_RAIN_AMPLITUDE if raining else 0.0
                self.hygro_engine.stimulate(self.hygro_connectome.sensory, amplitude)
                hygro_frame = self.hygro_engine.step()
                self.hygro_motor.push(hygro_frame.t_ms, hygro_frame.spikes)

            # F8 — quarto Engine, mesma mecânica do bristle/hygro acima.
            if self.johnston_engine is not None and self.johnston_motor is not None:
                amplitude = C.SENSOR_ALARM_AMPLITUDE if alarm else 0.0
                self.johnston_engine.stimulate(self.johnston_connectome.sensory, amplitude)
                johnston_frame = self.johnston_engine.step()
                self.johnston_motor.push(johnston_frame.t_ms, johnston_frame.spikes)

            # F9 — quinto Engine, mesma mecânica do bristle/hygro/johnston acima.
            if self.escape_engine is not None and self.escape_motor is not None:
                amplitude = C.SENSOR_LOOMING_AMPLITUDE if looming else 0.0
                self.escape_engine.stimulate(self.escape_connectome.sensory, amplitude)
                escape_frame = self.escape_engine.step()
                self.escape_motor.push(escape_frame.t_ms, escape_frame.spikes)

            if frame.t_ms % window_steps == 0:
                with self._lock:
                    latest: dict[str, Any] = {
                        "t_ms": frame.t_ms,
                        "motor": self.motor.decode(),
                        "active_dn": self.motor.active_output_count(),
                    }
                    if self.bristle_motor is not None:
                        latest["bristle_motor"] = self.bristle_motor.decode()
                        latest["bristle_active_dn"] = self.bristle_motor.active_output_count()
                    if self.hygro_motor is not None:
                        latest["hygro_motor"] = self.hygro_motor.decode()
                        latest["hygro_active_dn"] = self.hygro_motor.active_output_count()
                    if self.johnston_motor is not None:
                        latest["johnston_motor"] = self.johnston_motor.decode()
                        latest["johnston_active_dn"] = self.johnston_motor.active_output_count()
                    if self.escape_motor is not None:
                        latest["escape_motor"] = self.escape_motor.decode()
                        latest["escape_active_dn"] = self.escape_motor.active_output_count()
                    self._latest = latest

            next_tick += period_s
            sleep_for = next_tick - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)
            else:
                next_tick = time.monotonic()  # atrasado — não acumula dívida

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
    with SimulationServer(cc, bristle_cc, hygro_cc, johnston_cc, escape_cc) as srv:
        print(f"flywire-sim escutando em {C.BRIDGE_HOST}:{srv.port}", flush=True)
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
