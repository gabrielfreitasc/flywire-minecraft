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


def test_bridge_survives_client_disconnect():
    """Se o plugin cair, o simulador continua rodando (não deve travar/crashar)."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        time.sleep(0.1)
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock.close()  # desconecta abruptamente, sem handshake de saída

        time.sleep(0.2)
        assert srv._sim_thread.is_alive()
