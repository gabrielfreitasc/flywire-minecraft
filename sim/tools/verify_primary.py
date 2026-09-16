"""
Verifica os arquivos primários da Zenodo (10.5281/zenodo.10676866) e confronta
o espelho GitHub aresta por aresta. Fecha a DT-1 e a DT-2 parcialmente.

Uso:  python3 sim/tools/verify_primary.py [--skip-md5]

RESTRIÇÃO: a VM tem ~3,8 GB de RAM. Nada aqui carrega arquivo inteiro —
feather é lido com memory_map e seleção de colunas. Não mudar para
read_feather() sem colunas: o de 9,5 GB derruba o processo.
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

RAW = Path(__file__).resolve().parents[2] / "data" / "raw"

EXPECTED_MD5 = {
    "flywire_synapses_783.feather":            "f8f1b97c9d4b0ea9b4c8b287f6b99091",
    "per_neuron_neuropil_count_post_783.feather": "bb5999f10920ade803d9f37097a43a56",
    "per_neuron_neuropil_count_pre_783.feather":  "90fcdb42c1ba05ed92820840fa1e6ba0",
    "proofread_connections_783.feather":       "f48f972d262323a102aed49af1396b8a",
    "proofread_root_ids_783.npy":              "e0e6c19732fd8c7a4e39a2d170105421",
}

# Dorkenwald et al. 2024
EXPECTED_TOTAL_SYNAPSES = 54_492_922


def md5(path: Path, chunk: int = 8 << 20) -> str:
    h = hashlib.md5()
    with path.open("rb") as fh:
        while block := fh.read(chunk):
            h.update(block)
    return h.hexdigest()


def check_md5() -> None:
    print("=== MD5 ===")
    for name, expected in EXPECTED_MD5.items():
        p = RAW / name
        if not p.exists():
            print(f"  [ausente] {name}")
            continue
        got = md5(p)
        mark = "OK" if got == expected else "*** DIVERGE ***"
        print(f"  [{mark}] {name}")
        if got != expected:
            print(f"      esperado {expected}\n      obtido   {got}")


def schema_of(path: Path):
    from pyarrow import feather
    return feather.read_table(path, memory_map=True).schema


def compare_edges() -> None:
    """Confronta o espelho (Connectivity_783.parquet) com o primário."""
    import pandas as pd
    from pyarrow import feather

    primary = RAW / "proofread_connections_783.feather"
    mirror = RAW / "Connectivity_783.parquet"
    if not primary.exists() or not mirror.exists():
        print("\n=== COMPARAÇÃO === pulada (falta arquivo)")
        return

    print("\n=== ESQUEMA DO PRIMÁRIO ===")
    sch = schema_of(primary)
    print("  " + ", ".join(sch.names))

    # Descobre os nomes das colunas sem assumir
    def pick(*candidates):
        for c in candidates:
            if c in sch.names:
                return c
        return None

    c_pre = pick("pre_pt_root_id", "pre_root_id", "pre")
    c_post = pick("post_pt_root_id", "post_root_id", "post")
    c_syn = pick("syn_count", "count", "n_syn", "syn")
    if not all((c_pre, c_post, c_syn)):
        print("  !! não reconheci as colunas — ajuste pick() com os nomes acima")
        return
    print(f"  usando: {c_pre}, {c_post}, {c_syn}")

    print("\n=== COMPARAÇÃO ARESTA POR ARESTA ===")
    tbl = feather.read_table(primary, columns=[c_pre, c_post, c_syn], memory_map=True)
    prim = tbl.to_pandas()
    del tbl
    prim.columns = ["pre", "post", "syn"]
    total = int(prim.syn.sum())
    print(f"  sinapses no primário: {total:,}"
          f"  ({'confere' if total == EXPECTED_TOTAL_SYNAPSES else 'DIVERGE'} com o artigo)")

    # o primário é quebrado por neurópilo -> agregar por par
    prim = prim.groupby(["pre", "post"], sort=False, as_index=False).syn.sum()
    print(f"  pares únicos no primário: {len(prim):,}")

    mir = pd.read_parquet(mirror, columns=["Presynaptic_ID", "Postsynaptic_ID", "Connectivity"])
    mir.columns = ["pre", "post", "syn"]
    print(f"  pares no espelho:         {len(mir):,}")

    merged = prim.merge(mir, on=["pre", "post"], how="outer",
                        suffixes=("_prim", "_mir"), indicator=True)
    only_prim = int((merged._merge == "left_only").sum())
    only_mir = int((merged._merge == "right_only").sum())
    both = merged[merged._merge == "both"]
    mismatch = int((both.syn_prim != both.syn_mir).sum())

    print(f"\n  só no primário: {only_prim:,}")
    print(f"  só no espelho:  {only_mir:,}")
    print(f"  em ambos:       {len(both):,}")
    print(f"  peso divergente:{mismatch:,}")

    if only_prim == only_mir == mismatch == 0:
        print("\n  >>> ESPELHO IDÊNTICO AO PRIMÁRIO. DT-1 fechada.")
    else:
        print("\n  >>> DIVERGÊNCIA. NÃO usar o espelho até entender a causa.")


if __name__ == "__main__":
    if not RAW.exists():
        sys.exit(f"data/raw não encontrado em {RAW}")
    if "--skip-md5" not in sys.argv:
        check_md5()
    compare_edges()
