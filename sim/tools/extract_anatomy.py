"""
Extrai anatomia real (posição 3D + neurópilo dominante) pros 625 neurônios do
subcircuito v1, a partir da fonte primária da Zenodo (AD-11). "Anatomia real"
era trabalho futuro registrado desde a F0 (AD-05) — nunca bloqueante pra v1
(topologia pura bastava), motivado agora pelo usuário.

RN-05 — `root_id` não sobe de L1. Este script lê `root_id` dos arquivos
primários (é a chave deles), mas escreve `nid` no resultado. Não expor
`root_id` além daqui.

AD-12 — RAM da máquina é limitada; `flywire_synapses_783.feather` tem 9,5 GB.
NUNCA lido inteiro: iteração por record batch (1985 batches de ~65536 linhas
cada, confirmado via `pyarrow.ipc`), filtrando pros 625 neurônios do
subcircuito a cada batch e descartando o resto. Memória fica limitada ao
tamanho do resultado filtrado (pequeno — 625 de ~139k neurônios), não ao
arquivo inteiro.

Duas fontes, custo bem diferente:
- Neurópilo: `per_neuron_neuropil_count_{pre,post}_783.feather` (241 MB juntos)
  — já é contagem por neurônio, não precisa do arquivo de sinapse individual.
- Posição 3D: só existe em `flywire_synapses_783.feather` (coordenada por
  SINAPSE, não por neurônio) — centroide calculado aqui, média de todas as
  posições de sinapse em que o neurônio participa (pré ou pós).

Coordenadas ficam em unidades brutas do dataset (espaço de voxel do FlyWire),
não metros nem blocos do Minecraft — nenhuma conversão de escala feita aqui.

Uso:  python tools/extract_anatomy.py   (a partir de sim/, venv ativo)
Escreve: data/processed/anatomy_783.parquet
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.compute as pc
from pyarrow import ipc

RAW = Path(__file__).resolve().parents[2] / "data" / "raw"
PROCESSED = Path(__file__).resolve().parents[2] / "data" / "processed"

SYNAPSE_FILE = RAW / "flywire_synapses_783.feather"
NEUROPIL_PRE_FILE = RAW / "per_neuron_neuropil_count_pre_783.feather"
NEUROPIL_POST_FILE = RAW / "per_neuron_neuropil_count_post_783.feather"

POSITION_COLUMNS = [
    "pre_pt_root_id", "post_pt_root_id",
    "pre_pt_position_x", "pre_pt_position_y", "pre_pt_position_z",
    "post_pt_position_x", "post_pt_position_y", "post_pt_position_z",
]


def load_our_root_ids() -> pd.DataFrame:
    nodes = pd.read_parquet(PROCESSED / "nodes.parquet", columns=["nid", "root_id", "cell_type", "side"])
    return nodes


def extract_neuropil(root_ids: set[int]) -> pd.DataFrame:
    """Neurópilo dominante por neurônio (pré + pós somados). Arquivos pequenos,
    lidos inteiros é seguro — não precisa de batch aqui."""
    frames = []
    for path, col in ((NEUROPIL_PRE_FILE, "pre_pt_root_id"), (NEUROPIL_POST_FILE, "post_pt_root_id")):
        df = pd.read_feather(path, columns=[col, "neuropil", "count"])
        df = df[df[col].isin(root_ids)].rename(columns={col: "root_id"})
        frames.append(df)
    all_counts = pd.concat(frames, ignore_index=True)
    agg = all_counts.groupby(["root_id", "neuropil"], as_index=False)["count"].sum()
    dominant = agg.loc[agg.groupby("root_id")["count"].idxmax()][["root_id", "neuropil", "count"]]
    dominant = dominant.rename(columns={"neuropil": "neuropil_dominant", "count": "neuropil_dominant_synapses"})
    return dominant


def extract_positions(root_ids: set[int]) -> pd.DataFrame:
    """Centroide de posição por neurônio, streaming por record batch — nunca
    materializa o arquivo de 9,5GB inteiro (AD-12)."""
    root_ids_arr = pa.array(sorted(root_ids), type=pa.int64())
    reader = ipc.open_file(SYNAPSE_FILE)
    n_batches = reader.num_record_batches
    print(f"streaming {n_batches} batches de {SYNAPSE_FILE.name}...", flush=True)

    kept: list[pa.Table] = []
    t0 = time.monotonic()
    for i in range(n_batches):
        batch = reader.get_batch(i).select(POSITION_COLUMNS)
        mask = pc.or_(
            pc.is_in(batch.column("pre_pt_root_id"), value_set=root_ids_arr),
            pc.is_in(batch.column("post_pt_root_id"), value_set=root_ids_arr),
        )
        filtered = batch.filter(mask)
        if filtered.num_rows:
            kept.append(pa.Table.from_batches([filtered]))
        if (i + 1) % 200 == 0:
            elapsed = time.monotonic() - t0
            print(f"  {i + 1}/{n_batches} batches ({elapsed:.0f}s), "
                  f"{sum(t.num_rows for t in kept)} sinapses casadas até aqui", flush=True)

    matched = pa.concat_tables(kept).to_pandas() if kept else pd.DataFrame(columns=POSITION_COLUMNS)
    print(f"total: {len(matched)} sinapses envolvendo o subcircuito, "
          f"{time.monotonic() - t0:.0f}s", flush=True)

    # cada linha contribui uma amostra de posição pro neurônio PRÉ (posição pré-sináptica)
    # e uma pro neurônio PÓS (posição pós-sináptica) — um neurônio que é pré numa sinapse
    # e pós em outra entra nas duas listas, cada amostra na posição certa pro seu papel.
    pre_samples = matched[["pre_pt_root_id", "pre_pt_position_x", "pre_pt_position_y", "pre_pt_position_z"]]
    pre_samples.columns = ["root_id", "x", "y", "z"]
    post_samples = matched[["post_pt_root_id", "post_pt_position_x", "post_pt_position_y", "post_pt_position_z"]]
    post_samples.columns = ["root_id", "x", "y", "z"]
    all_samples = pd.concat([pre_samples, post_samples], ignore_index=True)
    all_samples = all_samples[all_samples.root_id.isin(root_ids)]

    centroid = all_samples.groupby("root_id", as_index=False).agg(
        pos_x=("x", "mean"), pos_y=("y", "mean"), pos_z=("z", "mean"),
        n_synapses_sampled=("x", "size"),
    )
    return centroid


def main() -> None:
    if not SYNAPSE_FILE.exists():
        sys.exit(f"Não encontrei {SYNAPSE_FILE} — baixar da Zenodo primeiro (ver docs/01-camada-de-dados.md, AD-11).")

    nodes = load_our_root_ids()
    root_ids = set(nodes.root_id.tolist())
    print(f"subcircuito: {len(root_ids)} neurônios (nid/root_id de data/processed/nodes.parquet)")

    neuropil = extract_neuropil(root_ids)
    positions = extract_positions(root_ids)

    result = nodes.merge(neuropil, on="root_id", how="left").merge(positions, on="root_id", how="left")

    missing_neuropil = result.neuropil_dominant.isna().sum()
    missing_position = result.pos_x.isna().sum()
    print(f"\nneurópilo: {len(result) - missing_neuropil}/{len(result)} neurônios com dado "
          f"({missing_neuropil} sem — provavelmente sem sinapse >=1 no primário)")
    print(f"posição:   {len(result) - missing_position}/{len(result)} neurônios com dado "
          f"({missing_position} sem)")

    out_cols = ["nid", "root_id", "cell_type", "side", "neuropil_dominant",
                "neuropil_dominant_synapses", "pos_x", "pos_y", "pos_z", "n_synapses_sampled"]
    result = result[out_cols].sort_values("nid").reset_index(drop=True)

    out_path = PROCESSED / "anatomy_783.parquet"
    result.to_parquet(out_path, index=False)
    print(f"\nescrito: {out_path} ({len(result)} linhas)")

    print("\ndistribuição de neurópilo dominante:")
    print(result.neuropil_dominant.value_counts())


if __name__ == "__main__":
    main()
