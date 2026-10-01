"""
F18 (30/09/2026) — extrai o subcircuito de corte/agressividade: mesma
mecânica de F7-F17 (`ingest.select_seed`/`ingest.build`, AD-17).

**Semente (curadoria por identidade celular, literatura):** 10 neurônios
`pC1a`/`pC1b`/`pC1c`/`pC1d`/`pC1e` (bilaterais, 2 de cada) — os nós
centrais do circuito de agressão de *Drosophila* na literatura: Schretter
et al. 2020 (eLife, "Cell types and neuronal circuitry underlying female
aggression") e Deutsch et al. 2020 identificam `pC1d` (dsx+) e `aIPg`
(fru+) como o núcleo do circuito, com conexões fortes e recorrentes entre
si que disparam comportamento agressivo (cabeçadas, empurrões). `aIPg`
NÃO existe com esse nome nos dados do FlyWire (0 hits) — provavelmente
nomenclatura só do hemibrain, ainda sem correspondência mapeada aqui; os
10 `pC1` batem exato com a literatura (5 subtipos × 2 lados).

**Por que não é extensão do `olfactory` (F17):** medido antes de decidir
— os 74 descendentes alcançados pela semente de feromônio (DA1/Or67d,
F17) em 2 saltos têm ZERO overlap com os 10 `pC1` daqui. `pC1` integra
múltiplas modalidades (feromônio, visão, toque — literatura) e funciona
como um "hub" de comando pra agressão, não como alvo direto a poucos
saltos da via olfativa. Semente própria, não reaproveitamento.

**Por que não `super_class == "sensory"`:** igual ao `escape` (AD-20),
`pC1` é `super_class == "central"` — passa por `sensory_cell_types`
(`ingest.build`), mesmo mecanismo.

**Alcance de salto (achado real, muito mais raso que os outros
circuitos):** 1 salto já dá 384 nós e 25 descendentes — `pC1` já é um nó
de integração relativamente próximo da saída motora, não um receptor
sensorial cru que precisa de vários saltos pra alcançar comportamento.
Custo de passo isolado: 0,096ms — um dos mais baratos de todos os
circuitos.

**Escreve em `data/processed/aggression/`** — nunca em `data/processed/`
direto.

Uso:  python tools/build_f18_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pc1[a-e]$"
SEED_CELL_TYPES = {"pC1a", "pC1b", "pC1c", "pC1d", "pC1e"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=1,
        out_dir=C.PROCESSED / "aggression",
        circuit="aggression",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
