"""
Analisa o CSV do experimento de validação de yaw_steering (gravado pelo
plugin Java, SteeringValidationExperiment.java) — F6/AD-16, ver
docs/04-regras-de-negocio.md RN-08.

Pergunta: o canal yaw_steering (RN-08/AD-16 — par bilateral dos 4 tipos
"steering") corresponde a virar pra um lado real do mundo, ou não significa
nada direcional? Testa estimulando só o neurônio esquerdo (`left`) ou só o
direito (`right`) do par bilateral, e medindo o ângulo de giro LÍQUIDO
acumulado (não distância) — se `left` e `right` girarem em sentidos opostos,
e diferentes de `baseline` (sem estímulo), o canal controla direção de
verdade, e o sinal de cada condição diz qual sentido é qual.

Uso:  python tools/steering_validation_analysis.py [caminho/do/csv]
Sem argumento, procura em
mc-server/plugins/FlywireBee/steering_validation_experiment.csv.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

DEFAULT_PATH = (
    Path(__file__).resolve().parents[2] / "mc-server" / "plugins" / "FlywireBee"
    / "steering_validation_experiment.csv"
)
CONDITIONS = ("left", "right", "baseline")


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    if not path.exists():
        print(f"Não encontrei {path}. Rode /flywirebee validateyaw no servidor primeiro.")
        sys.exit(1)

    df = pd.read_csv(path)
    groups = {c: df.loc[df.condition == c, "net_turn_rad"].to_numpy() for c in CONDITIONS}

    print("n por condição e giro líquido médio ± desvio (radianos, positivo/negativo = sentido no plano XZ):")
    for c in CONDITIONS:
        g = groups[c]
        if len(g) == 0:
            print(f"  {c:8s}: nenhum trial")
        else:
            deg = np.degrees(g.mean())
            print(f"  {c:8s}: n={len(g):2d}  média={g.mean():+.4f} rad ({deg:+6.1f}°)  desvio={g.std():.4f}")

    non_empty = [g for g in groups.values() if len(g) >= 2]
    if len(non_empty) < 2:
        print("\nPoucos trials por condição para estatística confiável — rode mais (/flywirebee validateyaw).")
        return

    print("\nKruskal-Wallis (omnibus, as 3 condições juntas):")
    h, p = stats.kruskal(*non_empty)
    print(f"  H={h:.3f}  p={p:.5f}")

    left, right = groups["left"], groups["right"]
    baseline = groups["baseline"]
    print("\nComparações par a par (Mann-Whitney U):")
    if len(left) >= 2 and len(right) >= 2:
        u, p_lr = stats.mannwhitneyu(left, right, alternative="two-sided")
        direction = "MESMO sentido (suspeito)" if (left.mean() * right.mean() > 0) else "sentidos OPOSTOS"
        print(f"  left vs right:     U={u:.1f}  p={p_lr:.5f}  ({direction})")
    if len(left) >= 2 and len(baseline) >= 2:
        u, p_lb = stats.mannwhitneyu(left, baseline, alternative="two-sided")
        print(f"  left vs baseline:  U={u:.1f}  p={p_lb:.5f}")
    if len(right) >= 2 and len(baseline) >= 2:
        u, p_rb = stats.mannwhitneyu(right, baseline, alternative="two-sided")
        print(f"  right vs baseline: U={u:.1f}  p={p_rb:.5f}")

    print(
        "\nInterpretação: se left×right der p<0,05 E os sinais das médias forem opostos, "
        "yaw_steering controla direção de verdade — o sinal da condição left (positivo ou "
        "negativo) é o sentido real de giro que 'esquerda do par bilateral' produz no mundo. "
        "Se left e right não diferirem de baseline, ou girarem pro mesmo lado, o canal não "
        "está fazendo o que a hipótese (RN-08/AD-16) previa. Não inventar interpretação além "
        "do que os números mostram — ver docs/04-regras-de-negocio.md RN-08."
    )


if __name__ == "__main__":
    main()
