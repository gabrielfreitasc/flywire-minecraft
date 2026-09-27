"""
RN-09 pro subcircuito `thermo` (F12, calor/frio): estimular os TRNs de
aquecimento (`heating`, 7) e de frio (`cold`, 9) — separados e juntos — tem
efeito mensurável sobre os descendentes, comparado a não estimular. Também
mede a distribuição de baseline/estimulado em Hz dos grupos de saída, pra
escolher escala de normalização ANTES de fixar limiar (lição do F9/F10).

Uso:  python tools/thermo_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph, topology
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_THERMO_AMPLITUDE
STEPS = 50
N_TRIALS = 20
OUT_DIR = C.PROCESSED / "thermo"


def paired(cc, seed, seeds, group):
    base_eng = Engine(cc, seed=seed)
    base = sum(int(base_eng.step().spikes[group].sum()) for _ in range(STEPS))
    eng = Engine(cc, seed=seed)
    eng.stimulate(seeds, AMPLITUDE)
    stim = sum(int(eng.step().spikes[group].sum()) for _ in range(STEPS))
    return base, stim


def main() -> None:
    cc = graph.load(OUT_DIR)
    heating = cc.nodes.index[(cc.nodes.role == "sensory") & (cc.nodes.cell_sub_class == "heating")].to_numpy()
    cold = cc.nodes.index[(cc.nodes.role == "sensory") & (cc.nodes.cell_sub_class == "cold")].to_numpy()
    exc, inh = topology.group_outputs_by_predicted_sign(cc, OUT_DIR)
    print(f"n={cc.n} heating={len(heating)} cold={len(cold)} saídas={len(cc.output)} exc={len(exc)} inh={len(inh)}")
    for label, seeds in (("heating", heating), ("cold", cold), ("ambos", np.concatenate([heating, cold]))):
        for gname, group in (("exc", exc), ("inh", inh)):
            if len(group) == 0:
                continue
            diffs = np.array([b - a for a, b in (paired(cc, s, seeds, group) for s in range(N_TRIALS))])
            _t, p = stats.ttest_1samp(diffs, 0)
            print(f"{label:8s} {gname}: diff média={diffs.mean():8.2f} desvio={diffs.std():6.2f} p={p:.5f}")

    # distribuição de taxa (Hz) do grupo excitatório, baseline vs estimulado (ambos)
    seeds = np.concatenate([heating, cold])
    window_s = C.MOTOR_WINDOW_MS / 1000.0
    def rates(amp):
        vals = []
        for seed in range(8):
            eng = Engine(cc, seed=seed)
            if amp:
                eng.stimulate(seeds, amp)
            hist = []
            for _ in range(int(C.MOTOR_WINDOW_MS) * 40):
                f = eng.step()
                hist.append((f.t_ms, f.spikes))
                hist[:] = [(t, sp) for t, sp in hist if t >= f.t_ms - C.MOTOR_WINDOW_MS]
                if f.t_ms % int(C.MOTOR_WINDOW_MS) == 0:
                    vals.append(sum(int(sp[exc].sum()) for _, sp in hist) / len(exc) / window_s)
        return np.array(vals)
    b, s = rates(0.0), rates(AMPLITUDE)
    print(f"baseline exc: média={b.mean():.1f}Hz p95={np.percentile(b,95):.1f} | estimulado: média={s.mean():.1f}Hz p05={np.percentile(s,5):.1f}")
    for scale in (30, 60, 100, 150):
        tb, ts = np.tanh(b / scale), np.tanh(s / scale)
        print(f"scale={scale}: baseline tanh média={tb.mean():.3f} p95={np.percentile(tb,95):.3f} | estimulado média={ts.mean():.3f} p05={np.percentile(ts,5):.3f}")


if __name__ == "__main__":
    main()
