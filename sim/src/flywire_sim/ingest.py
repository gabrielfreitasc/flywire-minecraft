"""L0 → L1 — lê data/raw/, valida contra o artigo, extrai o subcircuito ocelar.

Uso:  python -m flywire_sim.ingest
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pandas as pd

from . import config as C


class SourceValidationError(RuntimeError):
    """Os dados de origem não batem com o artigo."""


def _sha256(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        while block := fh.read(chunk):
            h.update(block)
    return h.hexdigest()


def load_annotations() -> pd.DataFrame:
    """Lê a anotação oficial e confere o total de linhas com o artigo."""
    ann = pd.read_csv(C.ANNOTATIONS_FILE, sep="\t", dtype=str).fillna("")
    if len(ann) != C.EXPECTED_ANNOTATION_ROWS:
        raise SourceValidationError(
            f"anotações: {len(ann)} linhas, esperado {C.EXPECTED_ANNOTATION_ROWS}"
        )
    ann["root_id"] = ann["root_id"].astype("int64")
    return ann


def load_connectivity() -> pd.DataFrame:
    """Lê a conectividade e confere o total de sinapses com o artigo."""
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
    """Semente = tudo cujo rótulo casa com `pattern` (RN-04 define a fronteira, não a semente)."""
    pattern = C.SEED_PATTERN if pattern is None else pattern
    cols = ["super_class", "cell_class", "cell_sub_class", "supertype", "cell_type"]
    hit = ann[cols].apply(
        lambda c: c.str.lower().str.contains(pattern), axis=0
    ).any(axis=1)
    return set(ann.loc[hit, "root_id"])


def expand(
    edges: pd.DataFrame,
    seed: set[int],
    descending: set[int],
    hops: int | None = None,
    exclude: set[int] | None = None,
) -> set[int]:
    """BFS a jusante."""
    hops = C.HOPS if hops is None else hops
    exclude = set() if exclude is None else exclude
    keep, frontier = set(seed), set(seed)
    for _ in range(hops):
        nxt = set(edges.loc[edges.pre.isin(frontier - descending), "post"]) - keep - exclude
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
    sensory_only_seed: bool = False,
    exclude_cell_classes: set[str] | None = None,
) -> dict:
    """Extrai um subcircuito (semente + saltos até os descendentes) e grava nodes/edges/manifest."""
    out_dir = C.PROCESSED if out_dir is None else out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    ann = load_annotations()
    edges = load_connectivity()
    edges = edges[edges.syn >= C.SYN_THRESHOLD]

    seed = select_seed(ann, pattern)
    descending = set(ann.loc[ann.super_class == "descending", "root_id"])
    exclude = (
        set(ann.loc[ann.cell_class.isin(exclude_cell_classes), "root_id"])
        if exclude_cell_classes else None
    )
    keep = expand(edges, seed, descending, hops, exclude=exclude)
    unannotated = keep - set(ann.root_id)
    keep -= unannotated

    sub = edges[edges.pre.isin(keep) & edges.post.isin(keep)].copy()

    nodes = ann[ann.root_id.isin(keep)][
        ["root_id", "super_class", "cell_class", "cell_sub_class", "cell_type",
         "side", "top_nt", "top_nt_conf", "known_nt", "nerve"]
    ].copy()
    nodes["is_seed"] = nodes.root_id.isin(seed)
    nodes["role"] = "interneuron"
    nodes.loc[nodes.super_class == "sensory", "role"] = "sensory"
    if sensory_cell_types is not None:
        nodes.loc[nodes.cell_type.isin(sensory_cell_types), "role"] = "sensory"
    if sensory_only_seed:
        nodes.loc[(nodes.role == "sensory") & ~nodes.is_seed, "role"] = "interneuron"
    nodes.loc[nodes.super_class == "descending", "role"] = "output"

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
        "sensory_only_seed": sensory_only_seed,
        "exclude_cell_classes": sorted(exclude_cell_classes) if exclude_cell_classes else None,
        "unannotated_dropped": len(unannotated),
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
