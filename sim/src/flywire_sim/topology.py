"""Topologia de sinal: caminho com sinal da semente até cada descendente (RN-09)."""
from __future__ import annotations

from collections import deque
from pathlib import Path

import numpy as np
import pandas as pd

from . import config as C
from . import graph


def signed_bfs(
    sensory: set[int], adj: dict[int, list[int]], sign_by_nid: dict[int, int]
) -> tuple[dict[int, int], dict[int, int]]:
    """BFS multi-fonte a partir de `sensory`."""
    dist: dict[int, int] = {}
    path_sign: dict[int, int] = {}
    q: deque[int] = deque()
    for s in sensory:
        dist[s] = 0
        path_sign[s] = 1
        q.append(s)

    while q:
        u = q.popleft()
        for v in adj.get(u, ()):
            if v in dist:
                continue
            dist[v] = dist[u] + 1
            path_sign[v] = path_sign[u] * sign_by_nid.get(u, 0)
            q.append(v)

    return dist, path_sign


def build_adjacency(edges: pd.DataFrame) -> dict[int, list[int]]:
    """Lista de adjacência pré → pós por `nid`."""
    adj: dict[int, list[int]] = {}
    for pre, post in zip(edges.pre_nid, edges.post_nid):
        adj.setdefault(int(pre), []).append(int(post))
    return adj


def group_outputs_by_predicted_sign(
    cc: graph.Connectome | None = None,
    out_dir: Path | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """RN-09: separa os descendentes em excitatórios/inibitórios pelo sinal do caminho desde a semente."""
    out_dir = C.PROCESSED if out_dir is None else out_dir
    cc = cc or graph.load(out_dir)
    edges = pd.read_parquet(out_dir / "edges.parquet")
    sign = graph.final_sign(cc.nodes)
    sign_by_nid = dict(zip(cc.nodes.nid, sign))

    adj = build_adjacency(edges)
    _, path_sign = signed_bfs(set(cc.sensory.tolist()), adj, sign_by_nid)

    output = set(cc.output.tolist())
    excitatory = np.array(sorted(n for n in output if path_sign.get(n, 0) > 0))
    inhibitory = np.array(sorted(n for n in output if path_sign.get(n, 0) < 0))
    return excitatory, inhibitory
