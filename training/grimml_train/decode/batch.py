from __future__ import annotations

from pathlib import Path

import pandas as pd

from grimml_train.decode.window import decode_file


def decode_directory(recordings_dir: Path) -> dict[str, pd.DataFrame]:
    wins: list[dict] = []
    frames: list[dict] = []
    events: list[dict] = []
    markers: list[dict] = []
    for path in sorted(recordings_dir.glob("**/*.gmlml")):
        try:
            d = decode_file(path)
        except Exception as e:
            raise RuntimeError(f"decode failed {path}: {e}") from e
        wins.append(d["window"])
        frames.extend(d["frames"])
        events.extend(d["events"])
        markers.extend(d["markers"])
    return {
        "windows": pd.DataFrame(wins) if wins else pd.DataFrame(),
        "frames": pd.DataFrame(frames) if frames else pd.DataFrame(),
        "events": pd.DataFrame(events) if events else pd.DataFrame(),
        "markers": pd.DataFrame(markers) if markers else pd.DataFrame(),
    }


def write_parquet(tables: dict[str, pd.DataFrame], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, df in tables.items():
        if df.empty:
            continue
        df.to_parquet(out_dir / f"{name}.parquet", index=False)
