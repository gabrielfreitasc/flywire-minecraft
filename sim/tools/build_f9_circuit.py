"""
F9 (24/09/2026) — extrai o subcircuito candidato ao circuito de fuga por
looming: mesma mecânica de F7/F8 (`ingest.select_seed`/`ingest.build`,
AD-17), mas com uma diferença estrutural — ver `sensory_cell_types` abaixo.

**Semente:** 318 neurônios `cell_type` em {"LC4", "LPLC2", "DNp01", "DNp02"}
(literatura clássica de fuga visual em *Drosophila*, ligada ao Schiff et al.
1962 sobre resposta a sombra em expansão):

  - `LC4` (104) e `LPLC2` (210) — detectores de looming no lobo óptico
    (`super_class == "visual_projection"`), duas vias PARALELAS e
    independentes (Ache et al. 2019; de Vries & Clandinin 2012).
  - `DNp01` (2, 1 por hemisfério) — a **Giant Fiber**, o neurônio de fuga
    mais estudado da mosca (dispara salto+voo direto no músculo).
  - `DNp02` (2, 1 por hemisfério) — via de fuga paralela, não-GF, também
    evocada por looming (von Reyn et al. 2017).

Confirmado neste subcircuito extraído (não assumido): há aresta sináptica
DIRETA `LC4/LPLC2 -> DNp01/DNp02` com syn forte (até 962 sinapses agregadas,
89-99 conexões por par), batendo com a identificação de Ache et al. 2019 via
conectômica EM. Todos os 4 tipos são colinérgicos (`top_nt == "acetylcholine"`)
— sem artefato de serotonina tipo RN-01a/AD-18/AD-19 aqui.

**`sensory_cell_types={"LC4", "LPLC2"}` — por quê:** diferente de
bristle/hygro/johnston (semente em `super_class == "sensory"`, primeiro
estágio sensorial "cru"), aqui a semente é `visual_projection` +
`descending`. Nenhum nó `super_class == "sensory"` aparece no grafo de 1
salto a partir dela — `Connectome.sensory` ficaria vazio e `Engine.stimulate()`
não teria onde injetar corrente (ver `server.py`). LC4/LPLC2 são o primeiro
estágio DESTE subcircuito extraído e o ponto biologicamente correto para
injetar o estímulo de ameaça (elas são os detectores de movimento/looming;
DNp01/DNp02 são a SAÍDA, não a entrada). Ver `ingest.build` para o mecanismo
genérico (AD-20).

**Alcance de salto (24/09/2026):** 1 salto dá 768 nós, 10.861 arestas, 31
descendentes (DNp01/DNp02 + outras 15 DNs que LC4/LPLC2 também alcançam em 1
salto — vias paralelas conhecidas na literatura de DNs, Namiki et al. 2018).
O canal motor usa só DNp01+DNp02 (ver `escape_motor.py`) — agregar as 31 sem
checar identidade repetiria o erro de diluição já documentado em
CLAUDE.md ("Armadilhas conhecidas").

**Escreve em `data/processed/escape/`** — nunca em `data/processed/` direto
(mesma disciplina de F7/F8).

Uso:  python tools/build_f9_circuit.py   (a partir de sim/, venv ativo)

Isto é só L0->L1 (extração + validação de contagem). L2/L3 (calibração de
bias/ruído RN-09) é o próximo passo, feito em `tools/escape_calibration_check.py`.
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^(lc4|lplc2|dnp01|dnp02)$"
SENSORY_CELL_TYPES = {"LC4", "LPLC2"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=1,
        out_dir=C.PROCESSED / "escape",
        circuit="escape",
        sensory_cell_types=SENSORY_CELL_TYPES,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
