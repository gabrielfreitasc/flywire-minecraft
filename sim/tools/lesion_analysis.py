"""Analisa o CSV de qualquer experimento de lesão do plugin (Welch e Mann-Whitney por coluna).

Uso:  python tools/lesion_analysis.py [caminho/do/lesion_experiment.csv]
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd
from scipy import stats

DEFAULT_PATH = (
    Path(__file__).resolve().parents[2] / "mc-server" / "plugins" / "FlywireBee" / "lesion_experiment.csv"
)


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    if not path.exists():
        print(f"Não encontrei {path}. Rode /flywirebee lesion no servidor primeiro.")
        sys.exit(1)

    df = pd.read_csv(path)
    df["lesioned"] = df["lesioned"].astype(str).str.strip().str.lower() == "true"

    normal = df.loc[~df.lesioned]
    lesioned = df.loc[df.lesioned]
    print(f"trials normais: {len(normal)}   trials lesionados: {len(lesioned)}")

    if len(normal) < 2 or len(lesioned) < 2:
        print("Poucos trials por grupo para estatística confiável — rode mais (/flywirebee lesion).")

    numeric_cols = [
        c for c in df.columns
        if c not in ("trial", "lesioned") and pd.api.types.is_numeric_dtype(df[c])
    ]
    for col in numeric_cols:
        n = normal[col].to_numpy()
        l = lesioned[col].to_numpy()
        print(f"\n{col}:")
        print(f"  normal:    média={n.mean():.4f}  desvio={n.std():.4f}")
        print(f"  lesionado: média={l.mean():.4f}  desvio={l.std():.4f}")

        t, p_t = stats.ttest_ind(n, l, equal_var=False)
        u, p_u = stats.mannwhitneyu(n, l, alternative="two-sided")
        print(f"  Welch t-test:   t={t:.3f}  p={p_t:.5f}")
        print(f"  Mann-Whitney U: U={u:.1f}  p={p_u:.5f}")

    print(
        "\nCritério de saída da F4 (docs/00-visao-geral.md): diferença "
        "estatisticamente mensurável entre normal e lesionado. p < 0,05 em "
        "algum teste acima apoia que a simulação está de fato acoplada ao "
        "comportamento — não é só movimento gerado à toa."
    )


if __name__ == "__main__":
    main()
