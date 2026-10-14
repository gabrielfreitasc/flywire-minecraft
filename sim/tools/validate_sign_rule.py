"""DT-2/AD-29: valida o sinal final contra o ground truth de literatura, separando dado de treino.

Uso:  python sim/tools/validate_sign_rule.py [--list]
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "sim" / "src"))

from flywire_sim import config as C
from flywire_sim import graph

RAW = ROOT / "data" / "raw"
PROCESSED = ROOT / "data" / "processed"
NT_COLUMNS = [
    "acetylcholine", "glutamate", "gaba", "glycine", "dopamine",
    "serotonin", "octopamine", "tyramine", "histamine", "nitric_oxide",
]
# F13
CIRCUITS = ["main", "bristle", "hygro", "johnston", "escape", "taste", "thermo",
            "olfactory", "aggression", "path_integration"]


def nt_to_sign(nt: str) -> int | None:
    """Mesma tabela da RN-01 (config.py); None = transmissor sem sinal na regra."""
    if nt in C.EXCITATORY_NT:
        return 1
    if nt in C.INHIBITORY_NT:
        return -1
    return None


def reference_signs(gt: pd.DataFrame) -> pd.DataFrame:
    """Um sinal de referência por tipo: +1, -1, 'sem_sinal' ou 'conflito'."""
    rows = []
    for cell_type, grp in gt.groupby("cell_type"):
        signs: set[int] = set()
        unsigned: set[str] = set()
        nts: set[str] = set()
        for _, r in grp.iterrows():
            for nt in NT_COLUMNS:
                if r[nt] == 1:
                    nts.add(nt)
                    s = nt_to_sign(nt)
                    if s is None:
                        unsigned.add(nt)
                    else:
                        signs.add(s)
        if not nts:
            continue
        if len(signs) > 1:
            ref = "conflito"
        elif signs:
            ref = next(iter(signs))
        else:
            ref = "sem_sinal"
        rows.append({
            "cell_type": cell_type, "ref": ref, "ref_nts": ",".join(sorted(nts)),
            "source": "; ".join(sorted(set(grp.neurotransmitter_verified_source.astype(str))))[:120],
        })
    return pd.DataFrame(rows)


def predicted_signs() -> pd.DataFrame:
    """Sinal final do modelo por neurônio (cérebro inteiro), agregado por tipo."""
    cols = ["root_id", "super_class", "cell_class", "cell_sub_class", "cell_type",
            "hemibrain_type", "top_nt", "known_nt", "nerve"]
    ann = pd.read_csv(RAW / "Supplemental_file1_neuron_annotations.tsv", sep="\t",
                      usecols=cols, dtype={"root_id": "int64"}, low_memory=False)
    ann["sign"] = graph.final_sign(ann)
    return ann


def type_majority(ann: pd.DataFrame, key: str) -> pd.DataFrame:
    sub = ann[ann[key].notna() & (ann[key] != "")]
    agg = sub.groupby(key).sign.agg(
        n="size",
        pred=lambda s: int(np.sign(s.mode().iloc[0])) if len(s.mode()) == 1 else 0,
        frac=lambda s: float((s == s.mode().iloc[0]).mean()),
    ).reset_index().rename(columns={key: "cell_type"})
    return agg


def circuit_types() -> set[str]:
    types: set[str] = set()
    for name in CIRCUITS:
        d = PROCESSED if name == "main" else PROCESSED / name
        p = d / "nodes.parquet"
        if p.exists():
            types |= set(pd.read_parquet(p, columns=["cell_type"]).cell_type.dropna())
    types.discard("")
    return types


def summarize(df: pd.DataFrame, label: str) -> None:
    print(f"\n=== {label} ===")
    for indep in ("independente", "treino"):
        d = df[df.origin == indep]
        signed = d[d.ref.isin([1, -1])]
        agree = int((signed.pred == signed.ref).sum())
        wrong = signed[(signed.pred != signed.ref) & (signed.pred != 0)]
        silent = signed[signed.pred == 0]
        print(f"  [{indep}] tipos com referência: {len(d)}  |  com sinal ±1: {len(signed)}")
        if len(signed):
            print(f"      concordam: {agree}/{len(signed)} ({agree / len(signed):.1%})"
                  f"  | sinal INVERTIDO: {len(wrong)}  | modelo sem sinal (0): {len(silent)}")
        print(f"      referência sem sinal na regra (5-HT/histamina/...): {int((d.ref == 'sem_sinal').sum())}"
              f"  | conflito na literatura/co-transmissão: {int((d.ref == 'conflito').sum())}")


def main() -> None:
    gt = pd.read_csv(RAW / "gt_data.csv")
    gt = gt[gt.species == "adult_drosophila_melanogaster"]
    training_types = set(pd.read_csv(RAW / "202405-starting_gt_data.csv").cell_type.dropna())

    ref = reference_signs(gt)
    ann = predicted_signs()
    by_type = type_majority(ann, "cell_type")
    by_hb = type_majority(ann, "hemibrain_type")

    m = ref.merge(by_type, on="cell_type", how="left")
    miss = m.pred.isna()
    m.loc[miss, ["n", "pred", "frac"]] = (
        ref[miss].merge(by_hb, on="cell_type", how="left")[["n", "pred", "frac"]].to_numpy()
    )
    m = m[m.pred.notna()].copy()
    m["pred"] = m.pred.astype(int)
    m["origin"] = np.where(m.cell_type.isin(training_types), "treino", "independente")

    print(f"gt_data.csv: {ref.shape[0]} tipos com transmissor identificado; "
          f"{len(m)} casam com tipos do FlyWire (cell_type ou hemibrain_type)")
    summarize(m, "CÉREBRO INTEIRO")
    in_circuits = circuit_types()
    mc = m[m.cell_type.isin(in_circuits)]
    summarize(mc, f"SÓ TIPOS DOS 10 SUBCIRCUITOS DO SIMULADOR ({len(in_circuits)} tipos)")

    if "--list" in sys.argv:
        print("\n=== DIVERGÊNCIAS (sinal ±1 de referência, modelo diferente) ===")
        bad = m[m.ref.isin([1, -1]) & (m.pred != m.ref)].copy()
        bad["no_simulador"] = bad.cell_type.isin(in_circuits)
        bad = bad.sort_values(["no_simulador", "origin"], ascending=[False, True])
        for _, r in bad.iterrows():
            flag = "SIM" if r.no_simulador else "   "
            print(f"  [{flag}] [{r.origin:12s}] {r.cell_type:20s} modelo={r.pred:+d} ref={int(r.ref):+d} "
                  f"({r.ref_nts}) n={int(r.n)} — {r.source}")


if __name__ == "__main__":
    main()
