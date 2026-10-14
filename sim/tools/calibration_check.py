"""RN-09 no circuito ocelar (main): estimular os fotorreceptores muda os descendentes?

Uso:  python tools/calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from scipy import stats

sys.path.insert(0, str(Path(__file__).resolve().parent))

from signal_topology import group_outputs_by_predicted_sign

from flywire_sim import graph
from flywire_sim.engine import Engine

AMPLITUDE = 2.0
STEPS = 50
N_TRIALS = 30


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
    cc = graph.load()
    excitatory, inhibitory = group_outputs_by_predicted_sign(cc)
    print(f"grupo excitatório (desinibição esperada): {len(excitatory)} descendentes")
    print(f"grupo inibitório (inibição direta esperada): {len(inhibitory)} descendentes")

    for label, group in (("excitatório", excitatory), ("inibitório", inhibitory)):
        diffs = []
        for seed in range(N_TRIALS):
            base, stim = paired_trial(cc, seed, group)
            diffs.append(stim - base)
        diffs = np.array(diffs)
        t, p = stats.ttest_1samp(diffs, 0)
        print(
            f"\nGRUPO {label.upper()}: diff média={diffs.mean():.2f} "
            f"desvio={diffs.std():.2f} t={t:.3f} p={p:.5f}"
        )


if __name__ == "__main__":
    main()
