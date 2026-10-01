"""
F19 (01/10/2026) — extrai o subcircuito de integração de trajetórias
(path integration): mesma mecânica de F7-F18 (`ingest.select_seed`/
`ingest.build`, AD-17).

**Revisão de literatura confirma o checklist do usuário tem base real.**
O complexo central (CX) da *Drosophila* tem um circuito de navegação bem
caracterizado (Westeinde et al. 2024, "Transforming a head direction
signal into a goal-oriented steering command"; Lu & Behbahani et al. 2022,
"Transforming representations of movement from body- to world-centric
space"; Lyu et al. eLife 66039, conectoma do CX): `EPG` codifica direção
atual (bússola), **`PFN` integra direção + velocidade ao longo do tempo —
a definição literal de path integration** ("P-FN neurons integrate
heading and speed signals over time to calculate direction and distance"),
`hDelta` repassa pro `PFL3`, que projeta DIRETO pra neurônios descendentes
(`DNa02` especificamente citado na literatura).

**Semente: 422 neurônios `PFNp`/`PFNm`/`PFNa`/`PFNd`** — o estágio que a
literatura identifica como o cálculo de integração em si (não `EPG`, que é
só a bússola sem memória temporal; não `PFL3`, que já é o comando de
saída). `super_class == "central"`, não `"sensory"` — mesmo mecanismo
`sensory_cell_types` do `escape`/`aggression` (AD-20/AD-27).

**Alcance de salto:** 1 salto só alcança 1 descendente. 2 saltos dão 2.572
nós, 49 descendentes (34 excitatórios + 15 inibitórios pela topologia de
sinal) — nenhum `Kenyon_Cell` arrastado (a via de navegação não passa
pelo corpo cogumelo). Custo isolado: 0,26ms/tick.

**Achado real na calibração:** sob estímulo, o sinal líquido
(excitatório − inibitório) é NEGATIVO — o caminho inibitório domina mais
que o excitatório, ao contrário de todos os canais anteriores (que saturam
positivo). `tanh` lida bem com isso, só o SINAL do canal `path_integration`
é invertido em relação aos outros — documentado, não é erro.

**Pendente, antes de qualquer sensor real:** diferente de `olfactory`/
`aggression` (que pelo menos têm um evento externo concebível —
feromônio/contexto social), o estímulo real de `PFN` em *Drosophila* é
self-motion (girar, voar) — não um evento do MUNDO, e sim do PRÓPRIO
movimento da abelha. Isso sugere que o sensor, quando for decidido, deveria
vir de `ControlLoop.heading` (mudança de direção comandada por tick) em
vez de qualquer sensor de ambiente novo — telemetria pura por enquanto.

**Escreve em `data/processed/path_integration/`** — nunca em
`data/processed/` direto.

Uso:  python tools/build_f19_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pfn[pmad]$"
SEED_CELL_TYPES = {"PFNp", "PFNm", "PFNa", "PFNd"}


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "path_integration",
        circuit="path_integration",
        sensory_cell_types=SEED_CELL_TYPES,
        sensory_only_seed=True,
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
