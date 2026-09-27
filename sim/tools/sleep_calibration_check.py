"""
RN-09 pro subcircuito `sleep` (F13, sonolência noturna): estimular as 31
sementes (ER5 + dFB FB6A/H/I/Z) tem efeito mensurável sobre os descendentes,
comparado a não estimular? Também mede a distribuição de (taxa exc - taxa inh)
em Hz, baseline vs estimulado, pra escolher escala ANTES de fixar limiar
(lição do F9/F10/F12) — mesma quantidade que o decoder usa.

Uso:  python tools/sleep_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph, topology
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_SLEEP_AMPLITUDE
STEPS = 50
N_TRIALS = 20
OUT_DIR = C.PROCESSED / "sleep"


def paired(cc, seed, group):
    base_eng = Engine(cc, seed=seed)
    base = sum(int(base_eng.step().spikes[group].sum()) for _ in range(STEPS))
    eng = Engine(cc, seed=seed)
    eng.stimulate(cc.sensory, AMPLITUDE)
    stim = sum(int(eng.step().spikes[group].sum()) for _ in range(STEPS))
    return base, stim


def main() -> None:
    cc = graph.load(OUT_DIR)
    exc, inh = topology.group_outputs_by_predicted_sign(cc, OUT_DIR)
    print(f"n={cc.n} sementes={len(cc.sensory)} saídas={len(cc.output)} exc={len(exc)} inh={len(inh)}")
    for gname, group in (("exc", exc), ("inh", inh)):
        if len(group) == 0:
            continue
        diffs = np.array([b - a for a, b in (paired(cc, s, group) for s in range(N_TRIALS))])
        _t, p = stats.ttest_1samp(diffs, 0)
        print(f"{gname}: diff média={diffs.mean():8.2f} desvio={diffs.std():6.2f} p={p:.5f}")

    window_s = C.MOTOR_WINDOW_MS / 1000.0

    def rates(amp):
        vals = []
        for seed in range(8):
            eng = Engine(cc, seed=seed)
            if amp:
                eng.stimulate(cc.sensory, amp)
            hist = []
            for _ in range(int(C.MOTOR_WINDOW_MS) * 40):
                f = eng.step()
                hist.append((f.t_ms, f.spikes))
                hist[:] = [(t, sp) for t, sp in hist if t >= f.t_ms - C.MOTOR_WINDOW_MS]
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
