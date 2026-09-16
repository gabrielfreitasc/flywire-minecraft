"""
Telemetria em DuckDB.

Esquema:

  runs(run_id, started_at, manifest_sha, config_json)
  spikes(run_id, t_ms, nid)                    -- append-only, em lote
  stimuli(run_id, t_ms, nid, amplitude)
  motor_frames(run_id, t_ms, channel, value)

DuckDB lê data/processed/nodes.parquet nativamente, então dá para cruzar
spikes com cell_type sem ETL — ver `top_cell_types()` abaixo, que é a
consulta de exemplo exigida pelo critério de saída da F2.

Uso:

    with Recorder() as rec:
        for _ in range(10_000):
            frame = engine.step()
            rec.log_spikes(frame.t_ms, frame.spikes)
        run_id = rec.run_id

    top_cell_types(run_id).head()
"""
from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Self

import duckdb
import numpy as np
import pandas as pd
from numpy.typing import NDArray

from . import config as C

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
    run_id VARCHAR PRIMARY KEY,
    started_at TIMESTAMP,
    manifest_sha VARCHAR,
    config_json VARCHAR
);
CREATE TABLE IF NOT EXISTS spikes (
    run_id VARCHAR,
    t_ms INTEGER,
    nid INTEGER
);
CREATE TABLE IF NOT EXISTS stimuli (
    run_id VARCHAR,
    t_ms INTEGER,
    nid INTEGER,
    amplitude DOUBLE
);
CREATE TABLE IF NOT EXISTS motor_frames (
    run_id VARCHAR,
    t_ms INTEGER,
    channel VARCHAR,
    value DOUBLE
);
"""


def _as_posix(path: Path) -> str:
    """Caminho seguro para embutir em SQL (read_parquet), inclusive no Windows."""
    return str(path).replace("\\", "/")


class Recorder:
    """Grava um run de simulação em DuckDB. Uma instância por run.

    Bufferiza em memória e grava em lote (RN-06: nunca deve travar o passo do
    engine esperando I/O de disco a cada milissegundo).
    """

    def __init__(
        self,
        db_path: Path = C.DUCKDB_FILE,
        config_json: str = "{}",
        flush_every: int = 50_000,
    ) -> None:
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self.con = duckdb.connect(str(db_path))
        self.con.execute(SCHEMA)
        self.run_id = str(uuid.uuid4())
        self.flush_every = flush_every

        manifest_path = C.PROCESSED / "manifest.json"
        manifest_sha = ""
        if manifest_path.exists():
            manifest_sha = json.dumps(json.loads(manifest_path.read_text())["source_checksums"])

        self.con.execute(
            "INSERT INTO runs VALUES (?, ?, ?, ?)",
            [self.run_id, datetime.now(UTC), manifest_sha, config_json],
        )

        # Cada item é (t_ms, array de nids/valores) — concatenados em bloco no
        # flush. Um run de 10s pode gerar ~1M disparos: um append() Python por
        # disparo (ou um executemany linha a linha no DuckDB) é ordens de
        # magnitude mais lento que inserção colunar em lote.
        self._spike_chunks: list[tuple[int, NDArray[np.int64]]] = []
        self._spike_buffered = 0
        self._stim_buf: list[tuple[str, int, int, float]] = []
        self._motor_buf: list[tuple[str, int, str, float]] = []

    def log_spikes(self, t_ms: int, spikes: NDArray[np.bool_]) -> None:
        nids = np.nonzero(spikes)[0]
        if len(nids) == 0:
            return
        self._spike_chunks.append((t_ms, nids))
        self._spike_buffered += len(nids)
        if self._spike_buffered >= self.flush_every:
            self._flush_spikes()

    def log_stimulus(self, t_ms: int, nids: NDArray[np.integer], amplitude: float) -> None:
        for nid in nids:
            self._stim_buf.append((self.run_id, t_ms, int(nid), float(amplitude)))
        if len(self._stim_buf) >= self.flush_every:
            self._flush_stimuli()

    def log_motor_frame(self, t_ms: int, vec: dict[str, float]) -> None:
        for channel, value in vec.items():
            self._motor_buf.append((self.run_id, t_ms, channel, float(value)))
        if len(self._motor_buf) >= self.flush_every:
            self._flush_motor()

    def _flush_spikes(self) -> None:
        if not self._spike_chunks:
            return
        t_arr = np.concatenate(
            [np.full(len(nids), t, dtype=np.int64) for t, nids in self._spike_chunks]
        )
        nid_arr = np.concatenate([nids for _, nids in self._spike_chunks])
        df = pd.DataFrame({"run_id": self.run_id, "t_ms": t_arr, "nid": nid_arr})
        self.con.append("spikes", df)
        self._spike_chunks.clear()
        self._spike_buffered = 0

    def _flush_stimuli(self) -> None:
        if self._stim_buf:
            df = pd.DataFrame(self._stim_buf, columns=["run_id", "t_ms", "nid", "amplitude"])
            self.con.append("stimuli", df)
            self._stim_buf.clear()

    def _flush_motor(self) -> None:
        if self._motor_buf:
            df = pd.DataFrame(self._motor_buf, columns=["run_id", "t_ms", "channel", "value"])
            self.con.append("motor_frames", df)
            self._motor_buf.clear()

    def flush(self) -> None:
        self._flush_spikes()
        self._flush_stimuli()
        self._flush_motor()

    def close(self) -> None:
        self.flush()
        self.con.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


def top_cell_types(
    run_id: str,
    db_path: Path = C.DUCKDB_FILE,
    limit: int = 20,
    stimulus_nid: int | None = None,
) -> pd.DataFrame:
    """Consulta de exemplo da F2: quais tipos celulares mais dispararam num run.

    Com `stimulus_nid`, restringe aos disparos a partir do instante em que
    aquele nid foi estimulado (junta com `stimuli`) — responde "sob estímulo
    X" sem script ad-hoc, só SQL. Assume estímulo mantido (sem evento de
    "fim") até o fim do run gravado — suficiente para o demo da F2; um
    estímulo pulsado/repetido exigiria janelas por par de eventos on/off,
    revisitar quando a ponte (F3) permitir isso.

    Descarta `cell_type` vazio: os 273 fotorreceptores ocelares não têm
    `cell_type` atribuído na fonte (dado, não bug — ver
    `docs/01-camada-de-dados.md`). Sem esse filtro, estimular o sensorial e
    perguntar "quais tipos mais dispararam" responde trivialmente "os que eu
    acabei de estimular", que é ele mesmo, sem revelar nada sobre o circuito.
    """
    con = duckdb.connect(str(db_path))
    try:
        nodes_path = _as_posix(C.PROCESSED / "nodes.parquet")
        if stimulus_nid is None:
            query = f"""
                SELECT n.cell_type, count(*) AS disparos
                FROM spikes s
                JOIN read_parquet('{nodes_path}') n USING (nid)
                WHERE s.run_id = ? AND n.cell_type != ''
                GROUP BY 1 ORDER BY 2 DESC
                LIMIT ?
            """
            params = [run_id, limit]
        else:
            query = f"""
                SELECT n.cell_type, count(*) AS disparos
                FROM spikes s
                JOIN read_parquet('{nodes_path}') n USING (nid)
                WHERE s.run_id = ? AND n.cell_type != ''
                  AND s.t_ms >= (SELECT min(t_ms) FROM stimuli WHERE run_id = ? AND nid = ?)
                GROUP BY 1 ORDER BY 2 DESC
                LIMIT ?
            """
            params = [run_id, run_id, stimulus_nid, limit]
        return con.execute(query, params).df()
    finally:
        con.close()
