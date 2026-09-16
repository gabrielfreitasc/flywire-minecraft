"""
Valida RN-09: estimular os fotorreceptores tem efeito estatisticamente
mensurável sobre a atividade dos descendentes, comparado a não estimular.

Comparação pareada — mesma semente de ruído em baseline e estimulado, para
que a diferença observada só possa vir do estímulo, não de sorte no ruído.
Os descendentes são separados em dois grupos pela topologia de sinal
(tools/signal_topology.py), porque somar todos juntos cancela o efeito
(ver docs/04-regras-de-negocio.md, RN-09).

Reproduz os números citados na RN-09 (bias=0,045 · noise=0,05 · gain=0,01 ·
amp=2,0 · N=30 sementes): grupo inibitório p≈0,003, grupo excitatório p≈0,25.
Usa os valores atuais de config.py — se os parâmetros de RN-09 mudarem lá, os
números daqui mudam junto (é o comportamento esperado: sempre reflete o que
está de fato configurado, não um valor congelado).

Uso:  python tools/calibration_check.py   (a partir de sim/, com o venv ativo)
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
    """Roda baseline e estimulado com o mesmo seed. Retorna (base, stim) —
    contagem de disparos de `output_nids` em STEPS passos."""
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
