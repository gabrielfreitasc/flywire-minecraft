"""
L5 — ponte TCP com o plugin Minecraft.

Protocolo: JSON-lines sobre TCP em BRIDGE_PORT. Ver docs/02-arquitetura.md.

  plugin -> sim : {"t_ms":.., "light":.., "dorsal_light":.., "damage":false}
  sim -> plugin : {"t_ms":.., "motor": {<canal>: valor, ...}, "active_dn":..}

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

from . import config as C
from . import graph
from .engine import Engine
from .graph import Connectome
from .motor import MotorDecoder


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
        self._latest: dict[str, Any] = {"t_ms": 0, "motor": {}, "active_dn": 0}
        self._stop = threading.Event()

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
        with self._lock:
            self._light = light

    def latest_frame(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._latest)

    def _sim_loop(self) -> None:
        """Roda a dt=1 ms em tempo real, independente de qualquer conexão."""
        period_s = C.DT_MS / 1000.0
        next_tick = time.monotonic()
        window_steps = max(int(C.MOTOR_WINDOW_MS), 1)

        while not self._stop.is_set():
            with self._lock:
                light = self._light
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
