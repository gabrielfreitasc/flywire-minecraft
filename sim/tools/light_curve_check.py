"""
Investiga o mecanismo por trás da dose-resposta de luz medida no Minecraft
(F6, `docs/03-roadmap-fases.md`): `phototaxis` (= tanh(taxa_excitatória −
taxa_inibitória)) não é monotônico em luz — tem pico em light=0,25, cai em
light=0,5/1,0, e cai mais ainda em light=0.

Hipótese candidata registrada (não testada até aqui): as duas vias de sinal
da topologia (RN-09 — 29 descendentes com caminho excitatório/desinibição de
2 saltos, 63 com caminho inibitório direto) podem ter curvas de resposta à
INTENSIDADE de luz diferentes uma da outra, não só ao sinal (já sabíamos que
respondem em direções opostas — RN-09). Se uma via saturar ou virar antes da
outra, a diferença (`phototaxis`) pode ficar não-monotônica mesmo que cada
via sozinha tenha uma curva mais simples.

Roda sem Minecraft, mesma filosofia de `calibration_check.py` (que validou
RN-09) — mede a taxa de disparo de cada grupo SEPARADO (não só a diferença)
para os mesmos 4 níveis de luz testados no dose-resposta real
(`LightDoseResponseExperiment.java`), convertendo light->amplitude do jeito
que `server.py` faz de verdade (`light * SENSOR_LIGHT_GAIN`).

Uso:  python tools/light_curve_check.py   (a partir de sim/, com o venv ativo)
"""
from __future__ import annotations

import numpy as np
from scipy import stats

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.engine import Engine
from flywire_sim.topology import group_outputs_by_predicted_sign

LEVELS = (0.0, 0.25, 0.5, 1.0)
STEPS = 200  # 200ms por trial — curto o bastante pra rodar 30 sementes x 4 níveis rápido,
             # longo o bastante pra taxa não ser dominada por quantização de poucos disparos
N_TRIALS = 30  # mesmo N de calibration_check.py (RN-09)


def rate_hz(count: int, n_neurons: int) -> float:
    if n_neurons == 0:
        return 0.0
    window_s = STEPS * C.DT_MS / 1000.0
    return (count / n_neurons) / window_s


def run_level(cc: graph.Connectome, excitatory: np.ndarray, inhibitory: np.ndarray,
              light: float, seed: int) -> tuple[float, float]:
    eng = Engine(cc, seed=seed)
    # Mesma conversão que server.py::_sim_loop faz de verdade a cada tick —
    # sempre chama stimulate(), mesmo com amplitude 0 (light=0.0).
    eng.stimulate(cc.sensory, light * C.SENSOR_LIGHT_GAIN)
    exc_count = 0
    inh_count = 0
    for _ in range(STEPS):
        spikes = eng.step().spikes
        exc_count += int(spikes[excitatory].sum())
        inh_count += int(spikes[inhibitory].sum())
    return rate_hz(exc_count, len(excitatory)), rate_hz(inh_count, len(inhibitory))


def main() -> None:
    cc = graph.load()
    excitatory, inhibitory = group_outputs_by_predicted_sign(cc)
    print(f"grupo excitatório (desinibição esperada): {len(excitatory)} descendentes")
    print(f"grupo inibitório (inibição direta esperada): {len(inhibitory)} descendentes")

    exc_by_level: dict[float, list[float]] = {lvl: [] for lvl in LEVELS}
    inh_by_level: dict[float, list[float]] = {lvl: [] for lvl in LEVELS}
    photo_by_level: dict[float, list[float]] = {lvl: [] for lvl in LEVELS}

    for lvl in LEVELS:
        for seed in range(N_TRIALS):
            exc_rate, inh_rate = run_level(cc, excitatory, inhibitory, lvl, seed)
            exc_by_level[lvl].append(exc_rate)
            inh_by_level[lvl].append(inh_rate)
            photo_by_level[lvl].append(float(np.tanh((exc_rate - inh_rate) / C.MOTOR_RATE_SCALE)))

    print(f"\n{'luz':>5}  {'exc_hz (media+-dp)':>22}  {'inib_hz (media+-dp)':>22}  {'phototaxis (media+-dp)':>24}")
    for lvl in LEVELS:
        e = np.array(exc_by_level[lvl])
        i = np.array(inh_by_level[lvl])
        p = np.array(photo_by_level[lvl])
        print(f"{lvl:5.2f}  {e.mean():10.3f} +- {e.std():6.3f}  "
              f"{i.mean():10.3f} +- {i.std():6.3f}  {p.mean():+10.5f} +- {p.std():.5f}")

    print("\nKruskal-Wallis entre os 4 níveis, por via (existe efeito de luz na via sozinha?):")
    for label, data in (("excitatório", exc_by_level), ("inibitório", inh_by_level)):
        h, p = stats.kruskal(*[data[lvl] for lvl in LEVELS])
        print(f"  {label}: H={h:.3f}  p={p:.5f}")

    print("\nCada nível contra light=1,0 (Mann-Whitney), excitatório e inibitório separados:")
    for lvl in LEVELS:
        if lvl == 1.0:
            continue
        _, pe = stats.mannwhitneyu(exc_by_level[lvl], exc_by_level[1.0], alternative="two-sided")
        _, pi = stats.mannwhitneyu(inh_by_level[lvl], inh_by_level[1.0], alternative="two-sided")
        print(f"  light={lvl:.2f} vs 1,0:  excitatório p={pe:.5f}   inibitório p={pi:.5f}")

    print(
        "\nSe uma via for monotônica em luz e a outra não (ou se saturarem/virarem em "
        "pontos diferentes), isso explica por que a DIFERENÇA (phototaxis) pode ficar "
        "não-monotônica mesmo que cada via sozinha seja mais simples. Se as duas vias "
        "forem monotônicas e concordantes em forma, o formato em 'pico' não vem daqui — "
        "seria preciso olhar outro lugar (ex.: BIAS_CURRENT/NOISE_STD interagindo com o "
        "ganho, ou o próprio LIF perto do limiar). Não inventar explicação além do que "
        "os números acima mostram — ver docs/03-roadmap-fases.md, F6."
    )


if __name__ == "__main__":
    main()
