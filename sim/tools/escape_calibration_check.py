"""RN-09 no circuito escape (F9, looming): estimular a semente muda os descendentes? Mede também a escala do canal.

Uso:  python tools/escape_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_LOOMING_AMPLITUDE
STEPS = 50
N_TRIALS = 30

OUT_DIR = C.PROCESSED / "escape"
ESCAPE_DN_TYPES = ("DNp01", "DNp02")


def paired_trial(cc: graph.Connectome, seed: int, output_nids: np.ndarray) -> tuple[int, int]:
    """Roda baseline e estimulado com o mesmo seed."""
    eng_base = Engine(cc, seed=seed)
    base = 0
    for _ in range(STEPS):
        base += int(eng_base.step().spikes[output_nids].sum())

    eng_stim = Engine(cc, seed=seed)
    eng_stim.stimulate(cc.sensory, AMPLITUDE)
    stim = 0
    for _ in range(STEPS):
        stim += int(eng_stim.step().spikes[output_nids].sum())

    return base, stim


def main() -> None:
    cc = graph.load(OUT_DIR)
    escape_dn = cc.nodes.index[cc.nodes.cell_type.isin(ESCAPE_DN_TYPES)].to_numpy()
    print(f"n={cc.n}, sensoriais(LC4+LPLC2)={len(cc.sensory)}, descendentes(total)={len(cc.output)}")
    print(f"grupo escape (DNp01+DNp02): {len(escape_dn)} neurônios")

    diffs = []
    for seed in range(N_TRIALS):
        base, stim = paired_trial(cc, seed, escape_dn)
        diffs.append(stim - base)
    diffs = np.array(diffs)
    t, p_t = stats.ttest_1samp(diffs, 0)
    try:
        w, p_w = stats.wilcoxon(diffs)
    except ValueError:
        w, p_w = float("nan"), float("nan")
    print(
        f"\nGRUPO ESCAPE (n={len(escape_dn)}): diff média={diffs.mean():.2f} "
        f"desvio={diffs.std():.2f}\n"
        f"  t-test pareado: t={t:.3f} p={p_t:.5f}\n"
        f"  Wilcoxon (signed-rank, pareado): W={w:.1f} p={p_w:.5f}"
    )


if __name__ == "__main__":
    main()
