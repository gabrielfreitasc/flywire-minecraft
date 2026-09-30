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


def _send(sock_file, sensor: dict) -> dict:
    sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
    sock_file.flush()
    return json.loads(sock_file.readline().decode("utf-8"))


def _wait_until(sock_file, sensor: dict, ready, timeout: float = 10.0) -> dict:
    """F16 (30/09/2026) — repete `sensor` (t_ms crescente) até `ready(payload)`
    ser verdadeiro, ou desiste após `timeout`s. Substitui esperas fixas
    curtas (`time.sleep(0.1)` + N tentativas): spawn de processo (cada
    circuito agora é um processo próprio, não mais uma thread) é mais lento
    e mais variável que criar thread — sob a suíte inteira rodando em
    sequência (várias SimulationServer sendo criadas/destruídas), uma
    espera fixa curta é frágil. `ready` recebe o payload inteiro."""
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


# F16 (30/09/2026) — test_bridge_history_stays_bounded_by_window movido pra
# test_motor_telemetry.py::test_motor_history_stays_bounded_by_window. Depois
# da reforma pra multiprocessing, `srv.motor` não existe mais como atributo
# do processo de teste (o MotorDecoder do ocelar vive dentro do processo
# worker) — testar isso direto no MotorDecoder é mais rápido E mais preciso
# do que tentar inferir pelo protocolo.


def test_bridge_mute_field_silences_neurons():
    """F5 — campo 'mute' silencia o grupo nomeado, e omiti-lo não desfaz isso.

    F16 (30/09/2026) — `Engine.set_silenced` em si já é testado a fundo em
    test_engine.py (test_set_silenced_blocks_spikes/replaces_not_accumulates);
    depois da reforma pra multiprocessing esse `Engine` vive dentro do
    processo worker, sem acesso direto (`srv.engine` não existe mais). O que
    este teste precisa validar é só a FIAÇÃO do protocolo: que o campo
    "mute" chega no worker certo e tem efeito observável.

    Sinal escolhido — mutar o próprio grupo de saída ("DNp", um dos 8
    canais por prefixo de `motor.groups`) e checar O CANAL DELE, não
    `phototaxis` (tentativa anterior, descartada: `phototaxis` responde
    pouco e de forma não-monotônica à luz — achado real da F6 — então
    "sensory" mutado vs. livre não separa de forma confiável em poucas
    amostras). Silenciar "DNp" faz esses neurônios NUNCA aparecerem no
    SpikeFrame — a taxa do grupo fica em ZERO de verdade (não é ruído
    baixo, é ausência), então `tanh(0/escala)=0.0` exato, sem depender de
    quanta variância basal (RN-09) esse grupo tem.
    """
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")
        try:
            # A mensagem de mute fica na fila de controle até o worker
            # nascer e aplicar — não importa se o processo ainda nem
            # começou a rodar quando isto é enviado.
            _send(sock_file, {"t_ms": 0, "light": 0.5, "dorsal_light": 0.0, "damage": False, "mute": ["DNp"]})
            payload = _wait_until(
                sock_file, {"t_ms": 50, "light": 0.5, "dorsal_light": 0.0, "damage": False},
                lambda p: p.get("motor", {}).get("DNp") == 0.0,
            )
            assert payload["motor"]["DNp"] == 0.0

            # sem "mute" no campo, o silenciamento anterior deve persistir
            payload = _send(sock_file, {"t_ms": 100, "light": 0.5, "dorsal_light": 0.0, "damage": False})
            assert payload["motor"]["DNp"] == 0.0
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
    controle) e 'bristle_active_dn'. touch_proximity/damage disparam a
    semente do bristle via SENSOR_TOUCH_AMPLITUDE (OR simples) — F9
    (25/09/2026): touch_contact saiu do OR (esbarrar em bloco não é o mesmo
    estímulo biológico de tocar algo vivo, ver server.py::on_sensor)."""
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
    """F9 (25/09/2026) — bug real achado pelo usuário: esbarrar em bloco
    (touch_contact) não deveria estimular grooming, só toque/proximidade de
    algo vivo (touch_proximity) ou dano (damage). Teste direto de
    on_sensor(), sem precisar de socket."""
    cc = graph.load()
    srv = SimulationServer(cc, host="127.0.0.1", port=0)
    srv.on_sensor({"t_ms": 0, "light": 0.0, "touch_contact": True, "touch_proximity": False, "damage": False})
    assert srv._touch is False
    srv.on_sensor({"t_ms": 0, "light": 0.0, "touch_contact": False, "touch_proximity": True, "damage": False})
    assert srv._touch is True


def test_damage_alone_stimulates_escape():
    """F9 (25/09/2026, pedido do usuário) — levar um hit de verdade (damage)
    também estimula a semente do `escape`, não só looming_threat — um hit é
    sinal de ameaça mais forte que taxa de aproximação, deveria escalar pra
    fuga plena."""
    cc = graph.load()
    srv = SimulationServer(cc, host="127.0.0.1", port=0)
    srv.on_sensor({"t_ms": 0, "light": 0.0, "damage": False, "looming_threat": False})
    assert srv._looming is False
    srv.on_sensor({"t_ms": 0, "light": 0.0, "damage": True, "looming_threat": False})
    assert srv._looming is True


def test_bridge_without_hygro_omits_hygro_fields():
    """F7/AD-17 — sem hygro_connectome (default None), resposta idêntica a
    antes desta mudança: sem 'hygro_motor'/'hygro_active_dn'."""
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
    """F7/AD-17 — com hygro_connectome, a resposta ganha 'hygro_motor'
    (telemetria só, canal 'hygrotaxis' de topologia de sinal, RN-09
    validado mas sem lesão em servidor real) e 'hygro_active_dn'. 'raining'
    dispara a semente do hygro via SENSOR_RAIN_AMPLITUDE."""
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
    """F8 — sem johnston_connectome (default None), resposta idêntica a
    antes desta mudança: sem 'johnston_motor'/'johnston_active_dn'."""
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
    """F8 — com johnston_connectome, a resposta ganha 'johnston_motor'
    (telemetria só, canal 'startle' de topologia de sinal, RN-09 validado
    mas sem lesão em servidor real) e 'johnston_active_dn'.
    'alarm_hostile_mob'/'alarm_explosion' disparam a semente do johnston
    via SENSOR_ALARM_AMPLITUDE (OR simples)."""
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
    """F9 — sem escape_connectome (default None), resposta idêntica a antes
    desta mudança: sem 'escape_motor'/'escape_active_dn'."""
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
    """F9 — com escape_connectome, a resposta ganha 'escape_motor'
    (telemetria só, canal 'escape_drive' curado por identidade de tipo
    celular — DNp01+DNp02, RN-09 validado mas sem lesão em servidor real) e
    'escape_active_dn'. 'looming_threat' dispara a semente do escape via
    SENSOR_LOOMING_AMPLITUDE."""
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
    """F10 — sem taste_connectome (default None), resposta idêntica a antes
    desta mudança: sem 'taste_motor'/'taste_active_dn'."""
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
    """F10 — com taste_connectome, a resposta ganha 'taste_motor'
    (telemetria só, canal 'appetite' de topologia de sinal, RN-09 validado
    mas sem lesão em servidor real) e 'taste_active_dn'. 'food_contact'
    dispara a semente do taste via SENSOR_TASTE_AMPLITUDE."""
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
    """F12 — com thermo_connectome, a resposta ganha 'thermo_motor' (canal
    'thermal', telemetria) e 'thermo_active_dn'. As duas sementes (7 TRNs de
    aquecimento, 9 de frio) ficam separadas por `cell_sub_class`."""
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


def test_bridge_survives_client_disconnect():
    """Se o plugin cair, o simulador continua rodando (não deve travar/crashar)."""
    cc = graph.load()
    with SimulationServer(cc, host="127.0.0.1", port=0) as srv:
        sock = socket.create_connection(("127.0.0.1", srv.port), timeout=5.0)
        sock.close()  # desconecta abruptamente, sem handshake de saída

        time.sleep(0.5)
        # F16 — não é mais uma thread só, é um processo por circuito ativo.
        assert all(p.is_alive() for p in srv._processes)
