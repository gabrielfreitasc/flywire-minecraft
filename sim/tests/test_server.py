"""Testes do bridge (server.py)."""
from __future__ import annotations

import json
import socket
import time

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.server import SimulationServer


def _send(sock_file, sensor: dict) -> dict:
    sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
    sock_file.flush()
    return json.loads(sock_file.readline().decode("utf-8"))


def _wait_until(sock_file, sensor: dict, ready, timeout: float = 10.0) -> dict:
    deadline = time.monotonic() + timeout
    t_ms = sensor.get("t_ms", 0)
    payload: dict = {}
    while time.monotonic() < deadline:
        payload = _send(sock_file, {**sensor, "t_ms": t_ms})
        if ready(payload):
            return payload
        t_ms += 50
        time.sleep(0.02)
    raise AssertionError(f"condição não satisfeita em {timeout}s — última resposta: {payload}")


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


def test_bridge_mute_field_silences_neurons():
    """F5 — campo 'mute' silencia o grupo nomeado, e omiti-lo não desfaz isso."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            _send(sock_file, {"t_ms": 0, "light": 0.5, "dorsal_light": 0.0, "damage": False, "mute": ["DNp"]})
            payload = _wait_until(
                sock_file, {"t_ms": 50, "light": 0.5, "dorsal_light": 0.0, "damage": False},
                lambda p: p.get("motor", {}).get("DNp") == 0.0,
            )
            assert payload["motor"]["DNp"] == 0.0

            payload = _send(sock_file, {"t_ms": 100, "light": 0.5, "dorsal_light": 0.0, "damage": False})
            assert payload["motor"]["DNp"] == 0.0
        finally:
            sock.close()


def test_bridge_without_bristle_omits_bristle_fields():
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
    cc = graph.load()
    bristle_cc = graph.load(C.PROCESSED / "bristle")
    with SimulationServer(cc, bristle_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {
                "light": 0.0, "dorsal_light": 0.0,
                "damage": False, "touch_contact": True, "touch_proximity": True,
            }
            payload = _wait_until(sock_file, sensor, lambda p: "bristle_motor" in p)
            assert "grooming" in payload["bristle_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["bristle_motor"].values())
            assert isinstance(payload["bristle_active_dn"], int)
        finally:
            sock.close()


def test_touch_contact_alone_does_not_stimulate_bristle():
    cc = graph.load()
    srv = SimulationServer(cc, host="127.0.0.1", port=0)
    srv.on_sensor({"t_ms": 0, "light": 0.0, "touch_contact": True, "touch_proximity": False, "damage": False})
    assert srv._touch is False
    srv.on_sensor({"t_ms": 0, "light": 0.0, "touch_contact": False, "touch_proximity": True, "damage": False})
    assert srv._touch is True


def test_damage_alone_stimulates_escape():
    cc = graph.load()
    srv = SimulationServer(cc, host="127.0.0.1", port=0)
    srv.on_sensor({"t_ms": 0, "light": 0.0, "damage": False, "looming_threat": False})
    assert srv._looming is False
    srv.on_sensor({"t_ms": 0, "light": 0.0, "damage": True, "looming_threat": False})
    assert srv._looming is True


def test_bridge_without_hygro_omits_hygro_fields():
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
            assert "hygro_motor" not in payload
            assert "hygro_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_hygro_exposes_hygrotaxis():
    cc = graph.load()
    hygro_cc = graph.load(C.PROCESSED / "hygro")
    with SimulationServer(cc, hygro_connectome=hygro_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "raining": True}
            payload = _wait_until(sock_file, sensor, lambda p: "hygro_motor" in p)
            assert "hygrotaxis" in payload["hygro_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["hygro_motor"].values())
            assert isinstance(payload["hygro_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_johnston_omits_johnston_fields():
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
            assert "johnston_motor" not in payload
            assert "johnston_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_johnston_exposes_startle():
    cc = graph.load()
    johnston_cc = graph.load(C.PROCESSED / "johnston")
    with SimulationServer(cc, johnston_connectome=johnston_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "alarm_hostile_mob": True}
            payload = _wait_until(sock_file, sensor, lambda p: "johnston_motor" in p)
            assert "startle" in payload["johnston_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["johnston_motor"].values())
            assert isinstance(payload["johnston_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_escape_omits_escape_fields():
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
            assert "escape_motor" not in payload
            assert "escape_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_escape_exposes_escape_drive():
    cc = graph.load()
    escape_cc = graph.load(C.PROCESSED / "escape")
    with SimulationServer(cc, escape_connectome=escape_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "looming_threat": True}
            payload = _wait_until(sock_file, sensor, lambda p: "escape_motor" in p)
            assert "escape_drive" in payload["escape_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["escape_motor"].values())
            assert isinstance(payload["escape_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_taste_omits_taste_fields():
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
            assert "taste_motor" not in payload
            assert "taste_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_taste_exposes_appetite():
    cc = graph.load()
    taste_cc = graph.load(C.PROCESSED / "taste")
    with SimulationServer(cc, taste_connectome=taste_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "food_contact": True}
            payload = _wait_until(sock_file, sensor, lambda p: "taste_motor" in p)
            assert "appetite" in payload["taste_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["taste_motor"].values())
            assert isinstance(payload["taste_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_thermo_omits_thermo_fields():
    """F12 — sem thermo_connectome (default None), resposta idêntica a antes."""
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
            assert "thermo_motor" not in payload
            assert "thermo_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_thermo_exposes_thermal():
    cc = graph.load()
    thermo_cc = graph.load(C.PROCESSED / "thermo")
    with SimulationServer(cc, thermo_connectome=thermo_cc, host="127.0.0.1", port=0) as srv:
        assert len(srv._thermo_heating_nids) == 7
        assert len(srv._thermo_cold_nids) == 9
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "thermo_heat": True}
            payload = _wait_until(sock_file, sensor, lambda p: "thermo_motor" in p)
            assert "thermal" in payload["thermo_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["thermo_motor"].values())
            assert isinstance(payload["thermo_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_olfactory_omits_olfactory_fields():
    """F17 — sem olfactory_connectome (default None), resposta idêntica a antes."""
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
            assert "olfactory_motor" not in payload
            assert "olfactory_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_olfactory_exposes_pheromone():
    cc = graph.load()
    olfactory_cc = graph.load(C.PROCESSED / "olfactory")
    with SimulationServer(cc, olfactory_connectome=olfactory_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "pheromone_contact": True}
            payload = _wait_until(sock_file, sensor, lambda p: "olfactory_motor" in p)
            assert "pheromone" in payload["olfactory_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["olfactory_motor"].values())
            assert isinstance(payload["olfactory_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_aggression_omits_aggression_fields():
    """F18 — sem aggression_connectome (default None), resposta idêntica a antes."""
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
            assert "aggression_motor" not in payload
            assert "aggression_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_aggression_exposes_aggression_channel():
    cc = graph.load()
    aggression_cc = graph.load(C.PROCESSED / "aggression")
    with SimulationServer(cc, aggression_connectome=aggression_cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "aggression_contact": True}
            payload = _wait_until(sock_file, sensor, lambda p: "aggression_motor" in p)
            assert "aggression" in payload["aggression_motor"]
            assert all(-1.0 < v < 1.0 for v in payload["aggression_motor"].values())
            assert isinstance(payload["aggression_active_dn"], int)
        finally:
            sock.close()


def test_bridge_without_path_integration_omits_path_integration_fields():
    """F19 — sem path_integration_connectome (default None), resposta idêntica a antes."""
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
            assert "path_integration_motor" not in payload
            assert "path_integration_active_dn" not in payload
        finally:
            sock.close()


def test_bridge_with_path_integration_exposes_path_integration_channel():
    cc = graph.load()
    path_integration_cc = graph.load(C.PROCESSED / "path_integration")
    with SimulationServer(
        cc, path_integration_connectome=path_integration_cc, host="127.0.0.1", port=0
    ) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 0.0, "dorsal_light": 0.0, "damage": False, "path_integration_contact": True}
            payload = _wait_until(sock_file, sensor, lambda p: "path_integration_motor" in p)
            motor = payload["path_integration_motor"]
            assert "path_integration" in motor
            assert -1.0 < motor["path_integration"] < 1.0
            assert isinstance(motor["path_integration_dna02_hz"], float)
            assert motor["path_integration_dna02_hz"] >= 0.0
            assert isinstance(payload["path_integration_active_dn"], int)
        finally:
            sock.close()


def test_bridge_record_telemetry_writes_duckdb(tmp_path):
    import duckdb

    cc = graph.load()
    db_path = tmp_path / "test_record.duckdb"
    with SimulationServer(
        cc, host="127.0.0.1", port=0, record_telemetry=True, telemetry_db_path=db_path
    ) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 1.0, "dorsal_light": 0.0, "damage": False}
            _wait_until(sock_file, sensor, lambda p: "phototaxis" in p.get("motor", {}))
        finally:
            sock.close()
        time.sleep(0.3)

    assert db_path.exists()
    con = duckdb.connect(str(db_path))
    try:
        assert con.execute("SELECT count(*) FROM runs").fetchone()[0] >= 1
        assert con.execute("SELECT count(*) FROM spikes").fetchone()[0] > 0
        assert con.execute("SELECT count(*) FROM motor_frames").fetchone()[0] > 0
    finally:
        con.close()


def test_bridge_record_telemetry_one_file_per_circuit(tmp_path):
    import duckdb

    cc = graph.load()
    bristle_cc = graph.load(C.PROCESSED / "bristle")
    hygro_cc = graph.load(C.PROCESSED / "hygro")
    db_path = tmp_path / "runs.duckdb"
    with SimulationServer(
        cc, bristle_cc, hygro_cc, host="127.0.0.1", port=0,
        record_telemetry={"main", "bristle"}, telemetry_db_path=db_path,
    ) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            sensor = {"light": 1.0, "dorsal_light": 0.0, "damage": True, "touch_proximity": True}
            _wait_until(sock_file, sensor, lambda p: "bristle_motor" in p and "phototaxis" in p.get("motor", {}))
        finally:
            sock.close()
        time.sleep(0.3)

    for path in (db_path, tmp_path / "runs_bristle.duckdb"):
        assert path.exists(), path
        con = duckdb.connect(str(path))
        try:
            assert con.execute("SELECT count(*) FROM runs").fetchone()[0] == 1
            assert con.execute("SELECT count(*) FROM spikes").fetchone()[0] > 0
            assert con.execute("SELECT count(*) FROM motor_frames").fetchone()[0] > 0
        finally:
            con.close()
    assert not (tmp_path / "runs_hygro.duckdb").exists()


def test_parse_record_telemetry():
    """F20 — leitura de FLYWIRE_RECORD_TELEMETRY."""
    from flywire_sim.server import _OPTIONAL_CIRCUITS, parse_record_telemetry

    assert parse_record_telemetry("") == frozenset()
    assert parse_record_telemetry("1") == frozenset({"main"})
    assert parse_record_telemetry("TRUE") == frozenset({"main"})
    assert parse_record_telemetry("all") == frozenset({"main", *_OPTIONAL_CIRCUITS})
    assert parse_record_telemetry(" main, taste ") == frozenset({"main", "taste"})
    assert parse_record_telemetry("main,tastee") == frozenset({"main"})


def test_bridge_survives_client_disconnect():
    """Se o plugin cair, o simulador continua rodando (não deve travar/crashar)."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock.close()

        time.sleep(0.5)
        assert all(p.is_alive() for p in srv._processes)
