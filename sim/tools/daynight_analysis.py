"""
Analisa o CSV do experimento dia/noite (gravado pelo plugin Java,
DayNightExperiment.java) — F6, ver docs/03-roadmap-fases.md.

Compara path_length e avg_speed entre trials de dia (meio-dia, day=True) e de
noite (meia-noite, day=False). Mesmo critério de falsificação do experimento
de lesão da F4 (docs/00-visao-geral.md), aplicado a um eixo diferente: em vez
de silenciar fotorreceptores, varia a hora do mundo. `light`
(`Block.getLightLevel()`) é quem alimenta os fotorreceptores e já é sensível
a dia/noite — `dorsal_light` só foi usado como filtro de confundidor (abelha
ao ar livre) na hora de rodar o experimento, não entra nesta análise. Ver
CONVENCOES.md, "Armadilhas conhecidas".

Uso:  python tools/daynight_analysis.py [caminho/do/daynight_experiment.csv]
Sem argumento, procura em mc-server/plugins/FlywireBee/daynight_experiment.csv
(caminho padrão onde o plugin grava, ver plugin/README.md).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd
from scipy import stats

DEFAULT_PATH = (
    Path(__file__).resolve().parents[2] / "mc-server" / "plugins" / "FlywireBee" / "daynight_experiment.csv"
)


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    if not path.exists():
        print(f"Não encontrei {path}. Rode /flywirebee daynight no servidor primeiro.")
        sys.exit(1)

    df = pd.read_csv(path)
    df["day"] = df["day"].astype(str).str.strip().str.lower() == "true"

    day = df.loc[df.day]
    night = df.loc[~df.day]
    print(f"trials de dia: {len(day)}   trials de noite: {len(night)}")

    if len(day) < 2 or len(night) < 2:
        print("Poucos trials por grupo para estatística confiável — rode mais (/flywirebee daynight).")

    for col in ("path_length", "avg_speed"):
        d = day[col].to_numpy()
        n = night[col].to_numpy()
        print(f"\n{col}:")
        print(f"  dia:   média={d.mean():.4f}  desvio={d.std():.4f}")
        print(f"  noite: média={n.mean():.4f}  desvio={n.std():.4f}")

        t, p_t = stats.ttest_ind(d, n, equal_var=False)
        u, p_u = stats.mannwhitneyu(d, n, alternative="two-sided")
        print(f"  Welch t-test:   t={t:.3f}  p={p_t:.5f}")
        print(f"  Mann-Whitney U: U={u:.1f}  p={p_u:.5f}")

    print(
        "\nCritério de saída da F6 (docs/03-roadmap-fases.md): diferença "
        "estatisticamente mensurável entre dia e noite. p < 0,05 em algum "
        "teste acima apoia que o circuito ocelar responde ao ciclo dia/noite "
        "do mundo, não só a luz pontual — mas não prova 'orientação' ou "
        "'estabilização' específica (mesma ressalva do experimento de lesão "
        "da F4, ver docs/03-roadmap-fases.md)."
    )


if __name__ == "__main__":
    main()
