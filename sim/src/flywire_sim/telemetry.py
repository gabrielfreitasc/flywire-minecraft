"""Telemetria em DuckDB.

Uso:
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


def db_path_for(circuit: str, base: Path = C.DUCKDB_FILE) -> Path:
    """Arquivo DuckDB de telemetria de um circuito (um arquivo por circuito)."""
    if circuit == "main":
        return base
    return base.with_name(f"{base.stem}_{circuit}{base.suffix}")


def processed_dir_for(circuit: str) -> Path:
    """Pasta do subcircuito em data/processed/ (`main` é a raiz)."""
    return C.PROCESSED if circuit == "main" else C.PROCESSED / circuit


def _as_posix(path: Path) -> str:
    return str(path).replace("\\", "/")


class Recorder:
    """Grava um run de simulação em DuckDB."""

    def __init__(
        self,
        db_path: Path = C.DUCKDB_FILE,
        config_json: str = "{}",
        flush_every: int = 50_000,
        processed_dir: Path = C.PROCESSED,
    ) -> None:
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self.con = duckdb.connect(str(db_path))
        self.con.execute(SCHEMA)
        self.run_id = str(uuid.uuid4())
        self.flush_every = flush_every

        manifest_path = processed_dir / "manifest.json"
        manifest_sha = ""
        if manifest_path.exists():
            manifest_sha = json.dumps(json.loads(manifest_path.read_text())["source_checksums"])

        self.con.execute(
            "INSERT INTO runs VALUES (?, ?, ?, ?)",
            [self.run_id, datetime.now(UTC), manifest_sha, config_json],
        )

        self._spike_chunks: list[tuple[int, NDArray[np.int64]]] = []
        self._spike_buffered = 0
        self._stim_chunks: list[tuple[int, NDArray[np.integer], float]] = []
        self._stim_buffered = 0
        self._last_stim: tuple[NDArray[np.integer], float] | None = None
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
        if len(nids) == 0 or not amplitude:
            self._last_stim = None
            return
        last = self._last_stim
        if last is not None and last[1] == amplitude and np.array_equal(last[0], nids):
            return
        self._last_stim = (nids, float(amplitude))
        self._stim_chunks.append((t_ms, nids, float(amplitude)))
        self._stim_buffered += len(nids)
        if self._stim_buffered >= self.flush_every:
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
        self._append_numeric("spikes", pd.DataFrame({"t_ms": t_arr, "nid": nid_arr}))
        self._spike_chunks.clear()
        self._spike_buffered = 0

    def _flush_stimuli(self) -> None:
        if not self._stim_chunks:
            return
        t_arr = np.concatenate(
            [np.full(len(nids), t, dtype=np.int64) for t, nids, _ in self._stim_chunks]
        )
        nid_arr = np.concatenate([np.asarray(nids, dtype=np.int64) for _, nids, _ in self._stim_chunks])
        amp_arr = np.concatenate(
            [np.full(len(nids), a, dtype=np.float64) for _, nids, a in self._stim_chunks]
        )
        self._append_numeric("stimuli", pd.DataFrame({"t_ms": t_arr, "nid": nid_arr, "amplitude": amp_arr}))
        self._stim_chunks.clear()
        self._stim_buffered = 0

    def _flush_motor(self) -> None:
        if self._motor_buf:
            df = pd.DataFrame(self._motor_buf, columns=["run_id", "t_ms", "channel", "value"])
            self.con.append("motor_frames", df)
            self._motor_buf.clear()

    def _append_numeric(self, table: str, df: pd.DataFrame) -> None:
        cols = ", ".join(df.columns)
        self.con.register("_flush_buf", df)
        try:
            self.con.execute(
                f"INSERT INTO {table} (run_id, {cols}) SELECT ?, {cols} FROM _flush_buf", [self.run_id]
            )
        finally:
            self.con.unregister("_flush_buf")

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
    processed_dir: Path = C.PROCESSED,
) -> pd.DataFrame:
    """Consulta de exemplo da F2: quais tipos celulares mais dispararam num run."""
    con = duckdb.connect(str(db_path))
    try:
        nodes_path = _as_posix(processed_dir / "nodes.parquet")
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
