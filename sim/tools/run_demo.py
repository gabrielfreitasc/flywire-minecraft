"""Run de 10 s gravado em DuckDB e consulta dos tipos que mais dispararam (critério da F2).

Uso:  python tools/run_demo.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.engine import Engine
from flywire_sim.motor import MotorDecoder
from flywire_sim.telemetry import Recorder, top_cell_types

DURATION_MS = 10_000
AMPLITUDE = 2.0


def main() -> None:
    cc = graph.load()
    engine = Engine(cc)
    motor = MotorDecoder(cc)

    engine.stimulate(cc.sensory, AMPLITUDE)

    with Recorder() as rec:
        rec.log_stimulus(0, cc.sensory, AMPLITUDE)
        for _ in range(DURATION_MS):
            frame = engine.step()
            rec.log_spikes(frame.t_ms, frame.spikes)
            motor.push(frame.t_ms, frame.spikes)
            if frame.t_ms % 50 == 0:
                rec.log_motor_frame(frame.t_ms, motor.decode())
        run_id = rec.run_id

    print(f"run gravado: {run_id} ({DURATION_MS} ms em {C.DUCKDB_FILE})")
    print("\ntop cell_types por disparos, sob o estímulo dos fotorreceptores:")
    df = top_cell_types(run_id, stimulus_nid=int(cc.sensory[0]))
    print(df.to_string(index=False))


if __name__ == "__main__":
    main()
