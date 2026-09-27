"""
F13 (26/09/2026) — extrai o subcircuito de sono (sonolência noturna):
mesma mecânica de F7-F12 (`ingest.select_seed`/`ingest.build`, AD-17).

**Semente (curadoria por identidade celular, literatura):** neurônios `ER5`
(anéis do elipsoide, = "R5", sensor de pressão de sono/homeostase — Liu et al.
2016) e os dFB (corpo fan-shaped dorsal) `FB6A`/`FB6H`/`FB6I`/`FB6Z`, o
conjunto que a literatura associa às células que promovem sono (Donlea et al.
2011/2014, Pimentel et al. 2016; linhagem "23E10"). 31 neurônios. A
correspondência nome-de-tipo ↔ linhagem vem da literatura, NÃO foi verificada
contra o conectoma aqui — ver docs/03-roadmap-fases.md F13.

**Por que não `super_class == "sensory"`:** igual ao `escape` (AD-20), a
semente é o primeiro estágio DESTE subcircuito, não um receptor — passa por
`sensory_cell_types`. `sensory_only_seed=True` (AD-23) evita que sensoriais
alcançados por salto virem entrada.

**Alcance de salto:** 1 salto não tem nenhum descendente; 2 saltos dão 10;
3 saltos dão 19.130 nós (grande demais pro engine em tempo real). Usa 2.

**RESULTADO NEGATIVO (26/09/2026) — este circuito NÃO é usado no jogo.**
`tools/sleep_calibration_check.py` (RN-09, 20 trials pareados, hops=2):
estimular as 31 sementes NÃO muda os descendentes — excitatórios diff média
-0,15 spikes/50 ms (p=0,083), inibitórios 0,00 (p=1,0). O sinal é o esperado
(todas as sementes são inibitórias: ER5 = GABA, FB6* = glutamato, inibitório em
*Drosophila*), mas o efeito é desprezível. Com 3 saltos (293 descendentes, 19.130
nós; medido fora do repositório) a redução é de só ~3% da taxa basal e o passo do
engine custa ~1,12 ms — mais que o tempo real (dt = 1 ms) permite. Sono em
mosca é modulação de ESTADO (homeostase, neuromodulação), não silenciamento
sináptico direto dos descendentes. Por isso o estado de sono no jogo é proxy de
engenharia (`SleepState.java`), com os gatilhos de DESPERTAR vindos de circuitos
reais (johnston/escape/dano) — ver docs/03-roadmap-fases.md F13. Mantido aqui
como registro reproduzível do resultado, não como circuito ativo.

**Escreve em `data/processed/sleep/`** — nunca em `data/processed/` direto.

Uso:  python tools/build_f13_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^(er5|fb6a|fb6h|fb6i|fb6z)$"
SEED_CELL_TYPES = {"ER5", "FB6A", "FB6H", "FB6I", "FB6Z"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "sleep",
        circuit="sleep",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
