"""
Cliente de teste que finge ser o plugin Minecraft — critério de saída da F3.

Injeta sensores a `--rate` Hz por `--duration` s numa SimulationServer (em
processo, sem precisar do Docker/plugin real) e verifica:

  - nenhuma linha enviada fica sem resposta (sem perda de frame)
  - `motor._history` (estado que mais cresceria num bug) não escapa da janela

RSS do processo só é medido em SO Unix (`resource`, indisponível no Windows).
O ambiente de produção é Linux/Docker (AD-09) — a checagem real de memória
roda lá; aqui documentamos a limitação.

Uso:  python tools/fake_minecraft_client.py [--duration 60] [--rate 20]
"""
from __future__ import annotations

import argparse
import gc
import json
import socket
import time

from flywire_sim import graph
from flywire_sim.server import SimulationServer

try:
    import resource
except ImportError:  # Windows
    resource = None


def run(duration_s: float, rate_hz: float, host: str = "127.0.0.1") -> None:
    cc = graph.load()
    with SimulationServer(cc, host=host, port=0) as srv:
        time.sleep(0.2)  # dá tempo da thread de simulação estabilizar

        sock = socket.create_connection((host, srv.port), timeout=5.0)
        sock_file = sock.makefile("rwb")

        n_expected = int(duration_s * rate_hz)
        period_s = 1.0 / rate_hz

        sent = received = errors = 0

        gc.collect()
        history_len_start = len(srv.motor._history)
        mem_start = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss if resource else None

        t0 = time.monotonic()
        next_tick = t0
        for i in range(n_expected):
            sensor = {
                "t_ms": int(i * period_s * 1000),
                "light": 0.5 + 0.5 * (i % 2),
                "dorsal_light": 0.3,
                "damage": False,
            }
            sock_file.write((json.dumps(sensor) + "\n").encode("utf-8"))
            sock_file.flush()
            sent += 1

            line = sock_file.readline()
            if not line:
                errors += 1
                break
            try:
                json.loads(line.decode("utf-8"))
                received += 1
            except json.JSONDecodeError:
                errors += 1

            next_tick += period_s
            sleep_for = next_tick - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)

        elapsed = time.monotonic() - t0
        gc.collect()
        history_len_end = len(srv.motor._history)
        mem_end = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss if resource else None

        sock.close()

    print(f"duração real: {elapsed:.1f}s (alvo {duration_s}s)")
    print(f"enviados={sent} recebidos={received} erros={errors} esperado={n_expected}")
    print(
        f"motor._history: {history_len_start} -> {history_len_end} "
        "(limitado pela janela, não deve crescer)"
    )
    if mem_start is not None:
        print(f"RSS: {mem_start} KB -> {mem_end} KB (delta {mem_end - mem_start} KB)")
    else:
        print("RSS: indisponível neste SO (resource é Unix-only; produção é Linux/Docker)")

    assert sent == n_expected, f"nem todas as linhas foram enviadas: {sent}/{n_expected}"
    assert received == n_expected, f"perda de frame: {received}/{n_expected} respostas"
    assert errors == 0, f"{errors} respostas malformadas"
    assert history_len_end <= history_len_start + 5, "motor._history cresceu além da janela"
    print("\nOK — critério de saída da F3 atendido (sem perda de frame, sem crescimento de estado).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=float, default=60.0)
    parser.add_argument("--rate", type=float, default=20.0)
    args = parser.parse_args()
    run(args.duration, args.rate)
