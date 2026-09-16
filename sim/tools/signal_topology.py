"""
Análise interativa/exploratória da topologia de sinal — imprime a contagem de
descendentes excitatórios/inibitórios por salto a partir dos fotorreceptores.

A lógica reusável (BFS de sinal, agrupamento por sinal previsto) mora em
`flywire_sim.topology` — usada em runtime por `motor.py` desde a F4. Este
script é só o relatório de texto para inspeção manual (F1/RN-09).

Uso:  python tools/signal_topology.py   (a partir de sim/, com o venv ativo)
"""
from __future__ import annotations

import pandas as pd

from flywire_sim import config as C
from flywire_sim import graph
from flywire_sim.topology import build_adjacency, group_outputs_by_predicted_sign, signed_bfs

# Reexportado para compatibilidade com scripts existentes (tools/calibration_check.py).
__all__ = ["build_adjacency", "group_outputs_by_predicted_sign", "main", "signed_bfs"]


def main() -> None:
    cc = graph.load()
    edges = pd.read_parquet(C.PROCESSED / "edges.parquet")
    sign = graph.apply_nt_overrides(cc.nodes, graph.assign_sign(cc.nodes))
    sign_by_nid = dict(zip(cc.nodes.nid, sign))
    adj = build_adjacency(edges)

    sensory = set(cc.sensory.tolist())
    output = set(cc.output.tolist())
    dist, path_sign = signed_bfs(sensory, adj, sign_by_nid)

    reached_outputs = {n: (dist[n], path_sign[n]) for n in output if n in dist}
    positive = {n: d for n, d in reached_outputs.items() if d[1] > 0}
    negative = {n: d for n, d in reached_outputs.items() if d[1] < 0}

    print(f"descendentes alcançáveis: {len(reached_outputs)} / {len(output)}")
    print(f"  caminho líquido excitatório (desinibição): {len(positive)}")
    print(f"  caminho líquido inibitório (direto):        {len(negative)}")

    by_hop_pos: dict[int, int] = {}
    by_hop_neg: dict[int, int] = {}
    for n, d in dist.items():
        if n in sensory:
            continue
        s = path_sign[n]
        bucket = by_hop_pos if s > 0 else by_hop_neg if s < 0 else None
        if bucket is not None:
            bucket[d] = bucket.get(d, 0) + 1

    print("\nnós alcançáveis por salto (excluindo sensoriais):")
    for h in range(1, max(dist.values()) + 1):
        print(f"  hop={h}: excitatórios={by_hop_pos.get(h, 0)} inibitórios={by_hop_neg.get(h, 0)}")


if __name__ == "__main__":
    main()
