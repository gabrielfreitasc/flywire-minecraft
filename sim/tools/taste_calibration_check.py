"""
Versão do critério de saída da F1 (RN-09) para o subcircuito `taste` (F10,
candidato a paladar apetitivo): estimular a semente de GRNs de açúcar/água
tem efeito estatisticamente mensurável sobre a atividade dos descendentes,
comparado a não estimular.

Pré-requisito resolvido antes de rodar isto: RN-01a/AD-21 (override dos
27/129 neurônios "serotonin" da semente que eram artefato de classificador
— ver docs/04-regras-de-negocio.md).

Mesmo desenho de `hygro_calibration_check.py`/`bristle_calibration_check.py`:
comparação pareada (mesma semente de ruído em baseline e estimulado, N
sementes), separando por topologia de sinal prevista
(`topology.group_outputs_by_predicted_sign`) — sem curadoria por
identidade celular disponível pros 14 descendentes (diferente do `escape`,
que tem DNp01/DNp02 documentados na literatura; aqui não há fonte
identificando quais DNs são especificamente ligadas a alimentação).

Uso:  python tools/taste_calibration_check.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph, topology
from flywire_sim.engine import Engine

AMPLITUDE = C.SENSOR_TASTE_AMPLITUDE
STEPS = 50
N_TRIALS = 30

OUT_DIR = C.PROCESSED / "taste"


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
