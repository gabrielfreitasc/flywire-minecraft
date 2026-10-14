"""RN-09 no circuito olfactory (F17, feromônio): estimular a semente muda os descendentes? Mede também a escala do canal.

Uso:  python tools/olfactory_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph, topology
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_OLFACTORY_AMPLITUDE
STEPS = 50
N_TRIALS = 20
OUT_DIR = C.PROCESSED / "olfactory"


def paired(cc: graph.Connectome, seed: int, group: np.ndarray) -> tuple[int, int]:
    base_eng = Engine(cc, seed=seed)
    base = sum(int(base_eng.step().spikes[group].sum()) for _ in range(STEPS))
    stim_eng = Engine(cc, seed=seed)
    stim_eng.stimulate(cc.sensory, AMPLITUDE)
    stim = sum(int(stim_eng.step().spikes[group].sum()) for _ in range(STEPS))
    return base, stim


def main() -> None:
    cc = graph.load(OUT_DIR)
    exc, inh = topology.group_outputs_by_predicted_sign(cc, OUT_DIR)
    print(f"n={cc.n} sementes={len(cc.sensory)} saídas={len(cc.output)} exc={len(exc)} inh={len(inh)}")

    for label, group in (("exc", exc), ("inh", inh)):
        if len(group) == 0:
            print(f"\ngrupo {label}: vazio, pulando")
            continue
        diffs = np.array([b - a for a, b in (paired(cc, s, group) for s in range(N_TRIALS))])
        _t, p = stats.ttest_1samp(diffs, 0)
        print(f"{label:4s} (n={len(group)}): diff média={diffs.mean():8.2f} desvio={diffs.std():6.2f} p={p:.5f}")

    window_s = C.MOTOR_WINDOW_MS / 1000.0

    def rates(amp: float) -> np.ndarray:
        vals = []
        for seed in range(8):
            eng = Engine(cc, seed=seed)
            if amp:
                eng.stimulate(cc.sensory, amp)
            hist = []
            for _ in range(int(C.MOTOR_WINDOW_MS) * 40):
                f = eng.step()
                hist.append((f.t_ms, f.spikes))
                hist[:] = [(t2, sp) for t2, sp in hist if t2 >= f.t_ms - C.MOTOR_WINDOW_MS]
                if f.t_ms % int(C.MOTOR_WINDOW_MS) == 0:
                    e = sum(int(sp[exc].sum()) for _, sp in hist) / max(len(exc), 1) / window_s
                    i = sum(int(sp[inh].sum()) for _, sp in hist) / max(len(inh), 1) / window_s
                    vals.append(e - i)
        return np.array(vals)

    b, s = rates(0.0), rates(AMPLITUDE)
    print(f"(exc-inh) Hz baseline: média={b.mean():.1f} p95={np.percentile(b,95):.1f} | "
          f"estimulado: média={s.mean():.1f} p05={np.percentile(s,5):.1f}")
    for scale in (30, 60, 100, 150):
        tb, ts = np.tanh(b / scale), np.tanh(s / scale)
        print(f"scale={scale}: baseline tanh média={tb.mean():.3f} p95={np.percentile(tb,95):.3f} | "
              f"estimulado média={ts.mean():.3f} p05={np.percentile(ts,5):.3f}")


if __name__ == "__main__":
    main()
