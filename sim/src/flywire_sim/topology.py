"""
L2 — topologia de sinal do grafo: efeito esperado (excitatório/inibitório) de
estimular os fotorreceptores sobre cada neurônio do subcircuito, via um
modelo de taxa simplificado (produto de sinais ao longo do caminho mais
curto a partir dos fotorreceptores).

Promovido de sim/tools/signal_topology.py na F4: `motor.py` passou a
precisar disso em RUNTIME, dentro do container (canal de fototaxia) — não é
mais só uma análise manual de validação da F1. `tools/signal_topology.py`
virou um wrapper fino em cima daqui, para uso interativo/exploratório.

Para um caminho fotorreceptor -> n1 -> n2 -> ... -> alvo, o sinal do efeito
esperado em `alvo` é o produto dos sinais de todos os nós ANTES dele no
caminho (RN-01: sinal é propriedade do neurônio pré-sináptico). Como todo
fotorreceptor tem sinal -1 (RN-02), um número ÍMPAR de neurônios inibitórios
no caminho (contando o fotorreceptor) dá efeito líquido negativo; um número
PAR dá efeito líquido positivo — desinibição.

Isso é a base de RN-09: agregar todos os descendentes juntos cancela o
sinal de luz (29 respondem excitatório, 63 inibitório); é preciso separar
por esse produto de sinal para o efeito aparecer. Ver
docs/04-regras-de-negocio.md.
"""
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
    """BFS multi-fonte a partir de `sensory`. Retorna (dist, path_sign) por nid.

    `path_sign[v]` é o produto dos sinais de todos os nós no caminho mais
    curto de alguma fonte até v, EXCLUINDO v. Fontes começam com path_sign=1
    (identidade — nenhuma aresta percorrida ainda).
    """
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
    adj: dict[int, list[int]] = {}
    for pre, post in zip(edges.pre_nid, edges.post_nid):
        adj.setdefault(int(pre), []).append(int(post))
    return adj


def group_outputs_by_predicted_sign(
    cc: graph.Connectome | None = None,
    out_dir: Path | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Separa os nids de saída (descendentes) em (excitatorios, inibitorios)
    conforme o sinal do caminho mais curto a partir da camada sensorial.

    Usado por `motor.py` (canal de fototaxia, runtime) e por
    `tools/calibration_check.py` (validação estatística de RN-09, manual).
    `out_dir` default é o circuito ocelar (`C.PROCESSED`); outros circuitos
    (AD-17, F7) passam o próprio diretório — mesmo mecanismo, generalizado
    para qualquer distribuição de sinal na camada sensorial (não assume
    sinal único como o ocelar, RN-02).
    """
    out_dir = C.PROCESSED if out_dir is None else out_dir
    cc = cc or graph.load(out_dir)
    edges = pd.read_parquet(out_dir / "edges.parquet")
    sign = graph.apply_serotonin_artifact_overrides(
        cc.nodes, graph.apply_nt_overrides(cc.nodes, graph.assign_sign(cc.nodes))
    )
    sign_by_nid = dict(zip(cc.nodes.nid, sign))

    adj = build_adjacency(edges)
    _, path_sign = signed_bfs(set(cc.sensory.tolist()), adj, sign_by_nid)

    output = set(cc.output.tolist())
    excitatory = np.array(sorted(n for n in output if path_sign.get(n, 0) > 0))
    inhibitory = np.array(sorted(n for n in output if path_sign.get(n, 0) < 0))
    return excitatory, inhibitory
