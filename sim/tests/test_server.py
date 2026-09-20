"""
Testes do bridge (server.py). Ver docs/02-arquitetura.md e RN-06.

Versão rápida do critério de saída da F3 (o teste completo, 60s a 20Hz, é
tools/fake_minecraft_client.py — grande demais para rodar em toda execução
da suíte).
"""
from __future__ import annotations

import json
import socket
import time

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.server import SimulationServer


def test_bridge_request_response_no_frame_loss():
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            for i in range(20):
                sensor = {"t_ms": i * 50, "light": 0.7, "dorsal_light": 0.3, "damage": False}
                sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
                sock_file.flush()

                line = sock_file.readline()
                assert line, "sem resposta do bridge — perda de frame"
                payload = json.loads(line.decode("utf-8"))
                assert {"t_ms", "motor", "active_dn"} <= set(payload)

                time.sleep(0.05)
        finally:
            sock.close()


def test_bridge_history_stays_bounded_by_window():
    """A estrutura que mais cresceria num vazamento é motor._history."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.5)
        # janela MOTOR_WINDOW_MS=50, dt=1ms -> no máximo ~50-60 frames retidos
        assert len(srv.motor._history) <= 60


def test_bridge_mute_field_silences_neurons():
    """F5 — campo 'mute' silencia o grupo nomeado, e omiti-lo não desfaz isso."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"t_ms": 0, "light": 0.5, "dorsal_light": 0.0, "damage": False, "mute": ["sensory"]}
            sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
            sock_file.flush()
            sock_file.readline()
            time.sleep(0.1)  # dá tempo do loop de simulação aplicar

            assert srv.engine._silenced[cc.sensory].all()

            # sem "mute" no campo, o silenciamento anterior deve persistir
            sensor2 = {"t_ms": 50, "light": 0.5, "dorsal_light": 0.0, "damage": False}
            sock_file.write((json.dumps(sensor2) + "\n").encode("utf-8"))
            sock_file.flush()
            sock_file.readline()
            time.sleep(0.1)

            assert srv.engine._silenced[cc.sensory].all()
        finally:
            sock.close()


def test_bridge_without_bristle_omits_bristle_fields():
    """F7/AD-17 — sem bristle_connectome (default None), resposta idêntica a
    antes desta mudança: sem 'bristle_motor'/'bristle_active_dn'."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"t_ms": 0, "light": 0.5, "dorsal_light": 0.0, "damage": False}
            sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
            sock_file.flush()
            payload = json.loads(sock_file.readline().decode("utf-8"))
            assert "bristle_motor" not in payload
            assert "bristle_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_bristle_exposes_bristle_telemetry():
    """F7/AD-17 — com bristle_connectome, a resposta ganha 'bristle_motor'
    (telemetria só, RN-08 equivalente não validado o bastante pra virar
    controle) e 'bristle_active_dn'. touch_contact/touch_proximity/damage
    disparam a semente do bristle via SENSOR_TOUCH_AMPLITUDE (OR simples)."""
    cc = graph.load()
    bristle_cc = graph.load(C.PROCESSED / "bristle")
    with SimulationServer(cc, bristle_cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            for i in range(5):
                sensor = {
                    "t_ms": i * 50, "light": 0.0, "dorsal_light": 0.0,
                    "damage": False, "touch_contact": True, "touch_proximity": False,
                }
                sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
                sock_file.flush()
                payload = json.loads(sock_file.readline().decode("utf-8"))
                time.sleep(0.05)
            assert "bristle_motor" in payload
            assert "grooming" in payload["bristle_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["bristle_motor"].values())
            assert isinstance(payload["bristle_active_dn"], int)
        finally:
            sock.close()


def test_bridge_survives_client_disconnect():
    """Se o plugin cair, o simulador continua rodando (não deve travar/crashar)."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock.close()  # desconecta abruptamente, sem handshake de saída

        time.sleep(0.2)
        assert srv._sim_thread.is_alive()
