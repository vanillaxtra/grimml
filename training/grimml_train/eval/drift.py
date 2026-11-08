from __future__ import annotations

import numpy as np
import pandas as pd

PSI_WARN = 0.1
PSI_ALERT = 0.2


def psi(reference: np.ndarray, current: np.ndarray, bins: int = 10) -> float:
    """Population stability index on reference deciles; NaN share is its own bin."""
    ref = reference[np.isfinite(reference)]
    cur_all = current
    if ref.size < bins or cur_all.size == 0:
        return float("nan")
    edges = np.unique(np.quantile(ref, np.linspace(0, 1, bins + 1)))
    if edges.size < 2:
        return float("nan")
    edges[0], edges[-1] = -np.inf, np.inf

    def shares(x: np.ndarray) -> np.ndarray:
        fin = x[np.isfinite(x)]
        counts = np.histogram(fin, edges)[0].astype(float)
        counts = np.append(counts, float(x.size - fin.size))
        return np.clip(counts / max(1, x.size), 1e-6, None)

    r = shares(reference)
    c = shares(cur_all)
    return float(np.sum((c - r) * np.log(c / r)))


def drift_report(reference: pd.DataFrame, current: pd.DataFrame, columns: list[str]) -> dict:
    out = {}
    for col in columns:
        if col not in reference.columns or col not in current.columns:
            continue
        v = psi(pd.to_numeric(reference[col], errors="coerce").to_numpy(dtype=float),
                pd.to_numeric(current[col], errors="coerce").to_numpy(dtype=float))
        out[col] = {"psi": v, "status": "alert" if v >= PSI_ALERT else ("warn" if v >= PSI_WARN else "ok")}
    flagged = sorted((k for k, v in out.items() if v["status"] != "ok"), key=lambda k: -out[k]["psi"])
    return {"features": out, "flagged": flagged, "n_reference": int(len(reference)), "n_current": int(len(current))}
