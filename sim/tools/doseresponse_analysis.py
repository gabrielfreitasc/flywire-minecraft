"""
Analisa o CSV do experimento de dose-resposta de luz (gravado pelo plugin
Java, LightDoseResponseExperiment.java) — F6, ver docs/03-roadmap-fases.md.

Pergunta em aberto que motivou este experimento: o experimento dia/noite deu
efeito real (com `goals off`), mas na direção invertida — luz 0,25 (noite)
produziu MAIS distância que luz 1,0 (dia). Luz 0 (F4, lesão) deu a MENOR
distância das três condições já medidas, só que em experimentos diferentes,
não comparáveis diretamente. Este experimento mede 4 níveis (0 / 0,25 / 0,5 /
1,0) sorteados trial a trial NA MESMA rodada, mesma origem — testa se a
resposta à luz é não-monotônica (satura ou inverte em algum ponto) ou se os
resultados anteriores eram artefato de comparar rodadas diferentes.

Kruskal-Wallis testa se existe QUALQUER diferença entre os 4 níveis (omnibus,
não-paramétrico — mesma família do Mann-Whitney já usado no resto do
projeto). Comparações par a par contra luz=1,0 (a condição já validada como
baseline em F4/F6) ajudam a localizar ONDE a diferença está, se existir.

Uso:  python tools/doseresponse_analysis.py [caminho/do/doseresponse_experiment.csv]
Sem argumento, procura em
mc-server/plugins/FlywireBee/doseresponse_experiment.csv (caminho padrão onde
o plugin grava, ver plugin/README.md).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd
from scipy import stats

DEFAULT_PATH = (
    Path(__file__).resolve().parents[2] / "mc-server" / "plugins" / "FlywireBee" / "doseresponse_experiment.csv"
)
LEVELS = (0.0, 0.25, 0.5, 1.0)


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    if not path.exists():
        print(f"Não encontrei {path}. Rode /flywirebee doseresponse no servidor primeiro.")
        sys.exit(1)

    df = pd.read_csv(path)
    groups = {lvl: df.loc[(df.light_level - lvl).abs() < 1e-6, "path_length"].to_numpy() for lvl in LEVELS}

    print("n por nível e média ± desvio:")
    for lvl in LEVELS:
        g = groups[lvl]
        if len(g) == 0:
            print(f"  light={lvl:.2f}: nenhum trial")
        else:
            print(f"  light={lvl:.2f}: n={len(g):2d}  média={g.mean():.4f}  desvio={g.std():.4f}")

    non_empty = [g for g in groups.values() if len(g) >= 2]
    if len(non_empty) < 2:
        print("\nPoucos níveis com >=2 trials para estatística confiável — rode mais (/flywirebee doseresponse).")
        return

    print("\nKruskal-Wallis (omnibus, os 4 níveis juntos):")
    h, p_h = stats.kruskal(*non_empty)
    print(f"  H={h:.3f}  p={p_h:.5f}")

    baseline = groups[1.0]
    if len(baseline) >= 2:
        print("\nCada nível contra light=1,0 (baseline já validado em F4/F6), Mann-Whitney U:")
        for lvl in LEVELS:
            if lvl == 1.0 or len(groups[lvl]) < 2:
                continue
            u, p_u = stats.mannwhitneyu(groups[lvl], baseline, alternative="two-sided")
            direction = "menor" if groups[lvl].mean() < baseline.mean() else "maior"
            print(f"  light={lvl:.2f} vs 1,0: U={u:.1f}  p={p_u:.5f}  (light={lvl:.2f} foi {direction})")

    print(
        "\nSe Kruskal-Wallis der p<0,05, existe diferença real entre pelo menos dois "
        "níveis — as comparações par a par acima ajudam a ver o formato da curva "
        "(monotônica, saturante, ou não-monotônica). Não inventar explicação "
        "biológica além do que os números mostram — ver docs/03-roadmap-fases.md, F6."
    )


if __name__ == "__main__":
    main()
