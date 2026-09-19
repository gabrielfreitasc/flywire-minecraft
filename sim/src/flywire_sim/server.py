"""
L5 — ponte TCP com o plugin Minecraft.

Protocolo: JSON-lines sobre TCP em BRIDGE_PORT. Ver docs/02-arquitetura.md.

  plugin -> sim : {"t_ms":.., "light":.., "dorsal_light":.., "damage":false,
                    "mute": ["DNp", "sensory"],               # opcional
                    "stimulate": {"group": "DNg", "amplitude": 3.0}}  # opcional
  sim -> plugin : {"t_ms":.., "motor": {<canal>: valor, ...}, "active_dn":..}

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
from .engine import Engine
from .graph import Connectome
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
    """

    def __init__(
        self,
        connectome: Connectome,
        host: str = C.BRIDGE_HOST,
        port: int = C.BRIDGE_PORT,
    ) -> None:
        self.connectome = connectome
        self.engine = Engine(connectome)
        self.motor = MotorDecoder(connectome)

        self._lock = threading.Lock()
        self._light = 0.0
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
        mute = sensor.get("mute")
        stimulate = sensor.get("stimulate")
        with self._lock:
            self._light = light
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

            if frame.t_ms % window_steps == 0:
                with self._lock:
                    self._latest = {
                        "t_ms": frame.t_ms,
                        "motor": self.motor.decode(),
                        "active_dn": self.motor.active_output_count(),
                    }

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
    with SimulationServer(cc) as srv:
        print(f"flywire-sim escutando em {C.BRIDGE_HOST}:{srv.port}", flush=True)
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
