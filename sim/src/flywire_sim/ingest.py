"""
L0 → L1 — lê data/raw/, valida contra o artigo, extrai o subcircuito ocelar.

Escreve data/processed/{nodes,edges}.parquet + manifest.json.

Uso:  python -m flywire_sim.ingest
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pandas as pd

from . import config as C


class SourceValidationError(RuntimeError):
    """Os dados de origem não batem com o artigo. Não contornar — ver RN e README."""


def _sha256(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        while block := fh.read(chunk):
            h.update(block)
    return h.hexdigest()


def load_annotations() -> pd.DataFrame:
    ann = pd.read_csv(C.ANNOTATIONS_FILE, sep="\t", dtype=str).fillna("")
    if len(ann) != C.EXPECTED_ANNOTATION_ROWS:
        raise SourceValidationError(
            f"anotações: {len(ann)} linhas, esperado {C.EXPECTED_ANNOTATION_ROWS}"
        )
    ann["root_id"] = ann["root_id"].astype("int64")
    return ann


def load_connectivity() -> pd.DataFrame:
    edges = pd.read_parquet(C.CONNECTIVITY_FILE)
    edges = edges.rename(
        columns={
            "Presynaptic_ID": "pre",
            "Postsynaptic_ID": "post",
            "Connectivity": "syn",
            "Excitatory": "sign_source",
        }
    )[["pre", "post", "syn", "sign_source"]]

    total = int(edges.syn.sum())
    if total != C.EXPECTED_TOTAL_SYNAPSES:
        raise SourceValidationError(
            f"sinapses: {total:,}, esperado {C.EXPECTED_TOTAL_SYNAPSES:,}"
        )

    above = int((edges.syn >= C.SYN_THRESHOLD).sum())
    if above != C.EXPECTED_EDGES_ABOVE_THRESHOLD:
        raise SourceValidationError(
            f"conexões ≥{C.SYN_THRESHOLD}: {above:,}, "
            f"esperado {C.EXPECTED_EDGES_ABOVE_THRESHOLD:,}"
        )
    return edges


def select_seed(ann: pd.DataFrame, pattern: str | None = None) -> set[int]:
    """Semente = tudo cujo rótulo casa com `pattern` (RN-04 define a fronteira,
    não a semente). `pattern` default é o circuito ocelar (AD-06); outros
    circuitos (AD-17) passam seu próprio padrão — mesma mecânica, semente
    diferente."""
    pattern = C.SEED_PATTERN if pattern is None else pattern
    cols = ["super_class", "cell_class", "cell_sub_class", "supertype", "cell_type"]
    hit = ann[cols].apply(
        lambda c: c.str.lower().str.contains(pattern), axis=0
    ).any(axis=1)
    return set(ann.loc[hit, "root_id"])


def expand(
    edges: pd.DataFrame, seed: set[int], descending: set[int], hops: int | None = None
) -> set[int]:
    """BFS a jusante. Não atravessa neurônios descendentes — RN-04."""
    hops = C.HOPS if hops is None else hops
    keep, frontier = set(seed), set(seed)
    for _ in range(hops):
        nxt = set(edges.loc[edges.pre.isin(frontier - descending), "post"]) - keep
        keep |= nxt
        frontier = nxt
    return keep


def build(
    *,
    pattern: str | None = None,
    hops: int | None = None,
    out_dir: Path | None = None,
    circuit: str = "ocellar",
    sensory_cell_types: set[str] | None = None,
) -> dict:
    """Constrói um subcircuito e grava nodes/edges/manifest em `out_dir`
    (default: `data/processed/`, o circuito ocelar da v1 — AD-06). Outros
    circuitos (AD-17, F7) passam `out_dir` próprio para não colidir com o
    ocelar; nunca compartilham arquivo.

    `sensory_cell_types` (F9/AD-20, `escape`) — role="sensory" por padrão é
    `super_class == "sensory"` (fotorreceptores, hygrosensory, órgão de
    Johnston: todos primeiro estágio sensorial "cru"). O circuito `escape`
    tem semente em LC4/LPLC2, `super_class == "visual_projection"` — já
    alguns sinapses adiante do olho composto na ontologia do FlyWire, mas
    ainda assim o primeiro estágio DESTE subcircuito extraído (nenhum nó
    `super_class == "sensory"` aparece no grafo de 1 hop a partir deles) e
    o ponto onde o estímulo de ameaça/looming é injetado — por isso passam
    aqui, não porque a ontologia mudou. Sem isso, `Connectome.sensory` fica
    vazio e `Engine.stimulate()` não tem onde injetar corrente (ver
    `server.py`). `None` (default) preserva o comportamento de todos os
    outros circuitos."""
    out_dir = C.PROCESSED if out_dir is None else out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    ann = load_annotations()
    edges = load_connectivity()
    edges = edges[edges.syn >= C.SYN_THRESHOLD]  # RN-03

    seed = select_seed(ann, pattern)
    descending = set(ann.loc[ann.super_class == "descending", "root_id"])
    keep = expand(edges, seed, descending, hops)

    sub = edges[edges.pre.isin(keep) & edges.post.isin(keep)].copy()

    nodes = ann[ann.root_id.isin(keep)][
        ["root_id", "super_class", "cell_class", "cell_sub_class", "cell_type",
         "side", "top_nt", "top_nt_conf", "nerve"]
    ].copy()
    nodes["is_seed"] = nodes.root_id.isin(seed)
    nodes["role"] = "interneuron"
    nodes.loc[nodes.super_class == "sensory", "role"] = "sensory"
    if sensory_cell_types is not None:
        nodes.loc[nodes.cell_type.isin(sensory_cell_types), "role"] = "sensory"
    nodes.loc[nodes.super_class == "descending", "role"] = "output"  # RN-04

    # RN-05 — nid determinístico por root_id ordenado
    nodes = nodes.sort_values("root_id").reset_index(drop=True)
    nodes.insert(0, "nid", nodes.index.astype("int32"))

    idx = dict(zip(nodes.root_id, nodes.nid))
    sub["pre_nid"] = sub.pre.map(idx).astype("int32")
    sub["post_nid"] = sub.post.map(idx).astype("int32")

    nodes.to_parquet(out_dir / "nodes.parquet", index=False)
    sub[["pre_nid", "post_nid", "syn", "sign_source"]].to_parquet(
        out_dir / "edges.parquet", index=False
    )

    manifest = {
        "circuit": circuit,
        "seed_pattern": C.SEED_PATTERN if pattern is None else pattern,
        "materialization": C.MATERIALIZATION,
        "syn_threshold": C.SYN_THRESHOLD,
        "hops": C.HOPS if hops is None else hops,
        "sensory_cell_types": sorted(sensory_cell_types) if sensory_cell_types else None,
        "seed_neurons": len(seed),
        "nodes": len(nodes),
        "edges": len(sub),
        "synapses": int(sub.syn.sum()),
        "roles": nodes.role.value_counts().to_dict(),
        "source_checksums": {
            C.CONNECTIVITY_FILE.name: _sha256(C.CONNECTIVITY_FILE),
            C.ANNOTATIONS_FILE.name: _sha256(C.ANNOTATIONS_FILE),
        },
        "license": "CC-BY-4.0",
        "cite": [
            "Dorkenwald et al. 2024, Nature 634:124",
            "Schlegel et al. 2024",
            "Shiu et al. 2024",
        ],
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    return manifest


if __name__ == "__main__":
    print(json.dumps(build(), indent=2, ensure_ascii=False))
