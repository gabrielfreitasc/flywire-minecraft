"""RN-09 no circuito hygro (F7, chuva): estimular a semente muda os descendentes? Mede também a escala do canal.

Uso:  python tools/hygro_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph, topology
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_RAIN_AMPLITUDE
STEPS = 50
N_TRIALS = 30

OUT_DIR = C.PROCESSED / "hygro"


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
    excitatory, inhibitory = topology.group_outputs_by_predicted_sign(cc, OUT_DIR)
    print(f"n={cc.n}, sensoriais={len(cc.sensory)}, descendentes={len(cc.output)}")
    print(f"grupo excitatório (caminho previsto): {len(excitatory)} descendentes")
    print(f"grupo inibitório (caminho previsto):  {len(inhibitory)} descendentes")

    for label, group in (("excitatório", excitatory), ("inibitório", inhibitory)):
        if len(group) == 0:
            print(f"\nGRUPO {label.upper()}: vazio, pulando")
            continue
        diffs = []
        for seed in range(N_TRIALS):
            base, stim = paired_trial(cc, seed, group)
            diffs.append(stim - base)
        diffs = np.array(diffs)
        t, p_t = stats.ttest_1samp(diffs, 0)
        try:
            w, p_w = stats.wilcoxon(diffs)
        except ValueError:
            w, p_w = float("nan"), float("nan")
        print(
            f"\nGRUPO {label.upper()} (n={len(group)}): diff média={diffs.mean():.2f} "
            f"desvio={diffs.std():.2f}\n"
            f"  t-test pareado: t={t:.3f} p={p_t:.5f}\n"
            f"  Wilcoxon (signed-rank, pareado): W={w:.1f} p={p_w:.5f}"
        )


if __name__ == "__main__":
    main()
