"""L5 — ponte TCP com o plugin Minecraft."""
from __future__ import annotations

import os

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")

import json
import multiprocessing as mp
import queue as queue_module
import signal
import socketserver
import threading
import time
from collections.abc import Callable, Collection
from pathlib import Path
from typing import Any, Self

import numpy as np

from . import config as C
from . import graph
from .aggression_motor import AggressionMotorDecoder
from .bristle_motor import BristleMotorDecoder
from .engine import Engine
from .escape_motor import EscapeMotorDecoder
from .graph import Connectome
from .hygro_motor import HygroMotorDecoder
from .johnston_motor import JohnstonMotorDecoder
from .motor import MotorDecoder, group_by_published_behavior, group_steering_by_side
from .olfactory_motor import OlfactoryMotorDecoder
from .path_integration_motor import PathIntegrationMotorDecoder
from .taste_motor import TasteMotorDecoder
from .thermo_motor import ThermoMotorDecoder

# F16
_STIM_LIGHT = 0
_STIM_TOUCH = 1
_STIM_RAINING = 2
_STIM_ALARM = 3
_STIM_LOOMING = 4
_STIM_FOOD = 5
_STIM_THERMO_HEAT = 6
_STIM_THERMO_COLD = 7
# F17, RN-09, F18
_STIM_PHEROMONE = 8
_STIM_AGGRESSION = 9
# F19, F17, F18
_STIM_PATH_INTEGRATION = 10
_STIM_SIZE = 11

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


def _stim_olfactory(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_OLFACTORY_AMPLITUDE if vals[_STIM_PHEROMONE] else 0.0)


def _stim_aggression(vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (C.SENSOR_AGGRESSION_AMPLITUDE if vals[_STIM_AGGRESSION] else 0.0)


def _stim_path_integration(
    vals: Any, cc: Connectome, _heat: np.ndarray, _cold: np.ndarray
) -> tuple[np.ndarray, float]:
    return np.asarray(cc.sensory), (
        C.SENSOR_PATH_INTEGRATION_AMPLITUDE if vals[_STIM_PATH_INTEGRATION] else 0.0
    )


_OPTIONAL_CIRCUITS: dict[str, tuple[type, StimulusFn]] = {
    "bristle": (BristleMotorDecoder, _stim_bristle),
    "hygro": (HygroMotorDecoder, _stim_hygro),
    "johnston": (JohnstonMotorDecoder, _stim_johnston),
    "escape": (EscapeMotorDecoder, _stim_escape),
    "taste": (TasteMotorDecoder, _stim_taste),
    "thermo": (ThermoMotorDecoder, _stim_thermo),
    "olfactory": (OlfactoryMotorDecoder, _stim_olfactory),
    "aggression": (AggressionMotorDecoder, _stim_aggression),
    "path_integration": (PathIntegrationMotorDecoder, _stim_path_integration),
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
    record: bool = False,
    record_db_path: Any = None,
) -> None:
    engine = Engine(connectome)
    decoder = decoder_cls(connectome)

    recorder = None
    if record:
        from . import telemetry

        recorder = telemetry.Recorder(
            db_path=record_db_path if record_db_path is not None else telemetry.db_path_for(name),
            processed_dir=telemetry.processed_dir_for(name),
        )

    group_lookup: dict[str, np.ndarray] | None = None
    if control_queue is not None:
        group_lookup = dict(decoder.groups)
        group_lookup.update(group_by_published_behavior(connectome))
        group_lookup["sensory"] = connectome.sensory
        steering_left, steering_right = group_steering_by_side(connectome)
        group_lookup["steering_left"] = steering_left
        group_lookup["steering_right"] = steering_right

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

    signal.signal(signal.SIGINT, signal.SIG_IGN)

    try:
        while not stop_event.is_set():
            if control_queue is not None:
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

            vals = stim_array[:]
            nids, amplitude = stimulus_fn(vals, connectome, heating_nids, cold_nids)
            engine.stimulate(nids, amplitude)
            frame = engine.step()
            decoder.push(frame.t_ms, frame.spikes)
            if recorder is not None:
                recorder.log_stimulus(frame.t_ms, nids, amplitude)
                recorder.log_spikes(frame.t_ms, frame.spikes)

            if frame.t_ms % window_steps == 0:
                channels = decoder.decode()
                if recorder is not None:
                    recorder.log_motor_frame(frame.t_ms, channels)
                payload = {
                    "t_ms": frame.t_ms,
                    "channels": channels,
                    "active_dn": decoder.active_output_count(),
                }
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
                next_tick = time.monotonic()
    finally:
        if recorder is not None:
            recorder.close()


def parse_record_telemetry(value: str) -> frozenset[str]:
    """Lê FLYWIRE_RECORD_TELEMETRY: '1' (só main), lista 'main,taste' ou 'all'."""
    known = {"main", *_OPTIONAL_CIRCUITS}
    v = value.strip().lower()
    if not v:
        return frozenset()
    if v in ("1", "true", "yes"):
        return frozenset({"main"})
    if v == "all":
        return frozenset(known)
    names = {n.strip() for n in v.split(",") if n.strip()}
    unknown = names - known
    if unknown:
        print(
            f"telemetria: circuito(s) desconhecido(s) ignorado(s): {', '.join(sorted(unknown))} "
            f"(válidos: {', '.join(sorted(known))})",
            flush=True,
        )
    return frozenset(names & known)


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
    """Servidor TCP + um processo por circuito, cada um a dt=1ms real (F16)."""

    def __init__(
        self,
        connectome: Connectome,
        bristle_connectome: Connectome | None = None,
        hygro_connectome: Connectome | None = None,
        johnston_connectome: Connectome | None = None,
        escape_connectome: Connectome | None = None,
        taste_connectome: Connectome | None = None,
        thermo_connectome: Connectome | None = None,
        olfactory_connectome: Connectome | None = None,
        aggression_connectome: Connectome | None = None,
        path_integration_connectome: Connectome | None = None,
        host: str = C.BRIDGE_HOST,
        port: int = C.BRIDGE_PORT,
        record_telemetry: bool | Collection[str] = False,
        telemetry_db_path: Any = None,
    ) -> None:
        self.connectome = connectome
        self.bristle_connectome = bristle_connectome
        self.hygro_connectome = hygro_connectome
        self.johnston_connectome = johnston_connectome
        self.escape_connectome = escape_connectome
        self.taste_connectome = taste_connectome
        self.thermo_connectome = thermo_connectome
        self.olfactory_connectome = olfactory_connectome
        self.aggression_connectome = aggression_connectome
        self.path_integration_connectome = path_integration_connectome

        if thermo_connectome is not None:
            sensory = np.asarray(thermo_connectome.sensory)
            sub_class = thermo_connectome.nodes.loc[sensory, "cell_sub_class"].to_numpy()
            self._thermo_heating_nids = sensory[sub_class == "heating"]
            self._thermo_cold_nids = sensory[sub_class == "cold"]
        else:
            self._thermo_heating_nids = np.array([], dtype=np.int64)
            self._thermo_cold_nids = np.array([], dtype=np.int64)

        self._mp_ctx = mp.get_context("spawn")
        self._stim_array = self._mp_ctx.Array("d", _STIM_SIZE)
        self._stop_event = self._mp_ctx.Event()
        self._control_queue: mp.Queue = self._mp_ctx.Queue()

        self._lock = threading.Lock()
        self._touch = False
        self._raining = False
        self._alarm = False
        self._looming = False
        self._food = False
        self._thermo_heat = False
        self._thermo_cold = False
        self._pheromone = False
        self._aggression = False
        self._path_integration = False
        self._latest: dict[str, Any] = {"t_ms": 0, "motor": {}, "active_dn": 0}
        self._latest_by_circuit: dict[str, dict[str, Any]] = {}

        self._processes: list[Any] = []
        self._output_queues: dict[str, mp.Queue] = {}

        if record_telemetry is True:
            recorded: frozenset[str] = frozenset({"main"})
        elif not record_telemetry:
            recorded = frozenset()
        else:
            recorded = frozenset(record_telemetry)

        def _db_path(name: str) -> Any:
            if telemetry_db_path is None:
                return None
            from .telemetry import db_path_for

            return db_path_for(name, Path(telemetry_db_path))

        main_output_queue = self._mp_ctx.Queue(maxsize=1)
        self._output_queues["_main"] = main_output_queue
        self._processes.append(self._mp_ctx.Process(
            target=_engine_worker,
            args=("main", connectome, MotorDecoder, _stim_main, self._stim_array,
                  main_output_queue, self._stop_event, self._control_queue, "main" in recorded,
                  _db_path("main")),
            daemon=True, name="flywire-engine-main",
        ))

        circuit_connectomes = {
            "bristle": bristle_connectome, "hygro": hygro_connectome,
            "johnston": johnston_connectome, "escape": escape_connectome,
            "taste": taste_connectome, "thermo": thermo_connectome,
            "olfactory": olfactory_connectome, "aggression": aggression_connectome,
            "path_integration": path_integration_connectome,
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
                      self._stop_event, None, name in recorded, _db_path(name)),
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
        """Chamado pela thread de conexão a cada linha recebida."""
        light = float(sensor.get("light", 0.0))
        touch = bool(sensor.get("damage", False)) or bool(sensor.get("touch_proximity", False))
        raining = bool(sensor.get("raining", False))
        alarm = bool(sensor.get("alarm_explosion", False)) or bool(sensor.get("alarm_hostile_mob", False)) \
            or bool(sensor.get("sound_music", False))
        looming = bool(sensor.get("looming_threat", False)) or bool(sensor.get("damage", False))
        food = bool(sensor.get("food_contact", False))
        thermo_heat = bool(sensor.get("thermo_heat", False))
        thermo_cold = bool(sensor.get("thermo_cold", False))
        pheromone = bool(sensor.get("pheromone_contact", False))
        aggression = bool(sensor.get("aggression_contact", False))
        path_integration = bool(sensor.get("path_integration_contact", False))
        mute = sensor.get("mute")
        stimulate = sensor.get("stimulate")

        with self._stim_array.get_lock():
            self._stim_array[_STIM_LIGHT] = light
            self._stim_array[_STIM_TOUCH] = 1.0 if touch else 0.0
            self._stim_array[_STIM_RAINING] = 1.0 if raining else 0.0
            self._stim_array[_STIM_ALARM] = 1.0 if alarm else 0.0
            self._stim_array[_STIM_LOOMING] = 1.0 if looming else 0.0
            self._stim_array[_STIM_FOOD] = 1.0 if food else 0.0
            self._stim_array[_STIM_THERMO_HEAT] = 1.0 if thermo_heat else 0.0
            self._stim_array[_STIM_THERMO_COLD] = 1.0 if thermo_cold else 0.0
            self._stim_array[_STIM_PHEROMONE] = 1.0 if pheromone else 0.0
            self._stim_array[_STIM_AGGRESSION] = 1.0 if aggression else 0.0
            self._stim_array[_STIM_PATH_INTEGRATION] = 1.0 if path_integration else 0.0

        with self._lock:
            self._touch = touch
            self._raining = raining
            self._alarm = alarm
            self._looming = looming
            self._food = food
            self._thermo_heat = thermo_heat
            self._thermo_cold = thermo_cold
            self._pheromone = pheromone
            self._aggression = aggression
            self._path_integration = path_integration

        if mute is not None:
            self._control_queue.put(("mute", list(mute)))
        if stimulate is not None:
            self._control_queue.put(("stimulate", dict(stimulate)))

    def latest_frame(self) -> dict[str, Any]:
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
                return dict(self._latest)

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
    """Sobe a ponte TCP com todos os circuitos extraídos em data/processed."""
    cc = graph.load()
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
    olfactory_cc: Connectome | None = None
    try:
        olfactory_cc = graph.load(C.PROCESSED / "olfactory")
        print("olfactory: subcircuito de feromônio carregado (F17)", flush=True)
    except FileNotFoundError:
        print(
            "olfactory: data/processed/olfactory/ não encontrado — rodando sem ele "
            "(python tools/build_f17_circuit.py pra gerar)",
            flush=True,
        )
    aggression_cc: Connectome | None = None
    try:
        aggression_cc = graph.load(C.PROCESSED / "aggression")
        print("aggression: subcircuito de corte/agressividade carregado (F18)", flush=True)
    except FileNotFoundError:
        print(
            "aggression: data/processed/aggression/ não encontrado — rodando sem ele "
            "(python tools/build_f18_circuit.py pra gerar)",
            flush=True,
        )
    path_integration_cc: Connectome | None = None
    try:
        path_integration_cc = graph.load(C.PROCESSED / "path_integration")
        print("path_integration: subcircuito de integração de trajetórias carregado (F19)", flush=True)
    except FileNotFoundError:
        print(
            "path_integration: data/processed/path_integration/ não encontrado — rodando sem ele "
            "(python tools/build_f19_circuit.py pra gerar)",
            flush=True,
        )
    record_telemetry = parse_record_telemetry(os.environ.get("FLYWIRE_RECORD_TELEMETRY", ""))
    if record_telemetry:
        from .telemetry import db_path_for

        for name in sorted(record_telemetry):
            print(f"telemetria: gravando {name} em {db_path_for(name)}", flush=True)
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    with SimulationServer(
        cc, bristle_cc, hygro_cc, johnston_cc, escape_cc, taste_cc, thermo_cc, olfactory_cc, aggression_cc,
        path_integration_cc, record_telemetry=record_telemetry
    ) as srv:
        print(f"flywire-sim escutando em {C.BRIDGE_HOST}:{srv.port}", flush=True)
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
