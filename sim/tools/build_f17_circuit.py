"""
F17 (30/09/2026) — extrai o subcircuito olfativo de feromônio: mesma
mecânica de F7-F13 (`ingest.select_seed`/`ingest.build`, AD-17).

**Semente (curadoria por identidade celular, literatura — ver revisão
prévia à extração, `docs/03-roadmap-fases.md` F17):** 429 ORNs
(`cell_class == "olfactory"`, `cell_sub_class == "pheromone"`) — os cinco
glomérulos de feromônio clássicos de *Drosophila* adulta: `ORN_DA1`
(Or67d, cVA), `ORN_VA1d`/`ORN_VA1v`, `ORN_DL3`, `ORN_DC3`. Conectam com
agressão/corte via Or67d/DA1 (Kurtovic et al. 2007; Wang & Anderson 2010) —
não é combinação artificial de "olfato" com "corte/agressividade" do
checklist do usuário, é uma via biológica real documentada.

**Nomenclatura adulta confirmada contra a literatura certa** (achado real,
26-30/09/2026): os dois artigos que o usuário mandou de início (Berck et
al. 2016 eLife 14859, "The Wiring Diagram of a Glomerular Olfactory
System"; thetransmitter.org sobre evolução cross-species) descrevem o
sistema olfativo LARVAL (21 glomérulos) — o FlyWire 783 é o cérebro
ADULTO (~50 glomérulos, nomenclatura `ORN_DA1`/`VA1d`/etc., confirmada nos
dados). Referência certa: Bates et al. 2020, Current Biology, "Complete
Connectomic Reconstruction of Olfactory Projection Neurons in the Fly
Brain" (hemibrain, adulto) — primeiro inventário completo de PNs olfativos
adultos, mesma nomenclatura ALPN/ALLN que aparece nos nossos dados.

**Alcance de salto e exclusão do corpo cogumelo (achado real, medido):**
1 salto alcança só `ALPN`/`ALLN`/`ALON` (540 nós), zero descendentes — não
dá pra validar por lesão. 2 saltos normais dão 6.370 nós, mas 1.806 são
`Kenyon_Cell` (corpo cogumelo — via de memória/aprendizado associativo).
Medido: EXCLUIR `Kenyon_Cell` do salto (`ingest.build(exclude_cell_classes=
{"Kenyon_Cell"})`, AD-24) corta pra 4.564 nós SEM perder nenhum dos 74
descendentes alcançados — confirma que o sinal chega no comportamento pela
via INATA (corpo cogumelo lateral, `LHLN`/`LHCENT`), não pela de memória,
pelo menos neste subcircuito. Curadoria real, não corte arbitrário por
conveniência.

**`sensory_only_seed=True` (AD-23)** — 2 saltos arrastam 1.409 ORNs de
OUTRAS modalidades (glomérulos não-feromônio) junto; só os 429 da semente
recebem estímulo real.

**Escreve em `data/processed/olfactory/`** — nunca em `data/processed/`
direto.

Uso:  python tools/build_f17_circuit.py   (a partir de sim/, venv ativo)
"""
from __future__ import annotations

import json

from flywire_sim import config as C
from flywire_sim import ingest

SEED_PATTERN = r"^pheromone$"


def build() -> dict:
    return ingest.build(
        pattern=SEED_PATTERN,
        hops=2,
        out_dir=C.PROCESSED / "olfactory",
        circuit="olfactory",
        sensory_only_seed=True,
        exclude_cell_classes={"Kenyon_Cell"},
    )


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
