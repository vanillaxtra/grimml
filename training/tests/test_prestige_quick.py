import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prestige_quick as q


def test_quick_pipeline(tmp_path):
    args = q.parse_args(["--min-secs","10"])
    ids = q.id_table(args)
    print(ids.groupby(["label","exclude_reason"]).size())
    rng = np.random.default_rng(0)
    rows=[]
    def add(mid, n, shift, t0, step=9000):
        for i in range(n):
            rows.append(dict(window_key=f"{mid}_{i}", mark_id=mid, path=f"{mid}_{i}", epoch_ms=t0+i*step,
              window_start_nanos=0, window_end_nanos=7e9, role=1, ping_ms=rng.integers(20,60),
              jitter_ms=rng.random(), frame_count=140, attack_count=rng.integers(0,10),
              yaw_vel_mean=rng.normal(shift,1), yaw_vel_std=rng.normal(0,1), yaw_vel_abs_mean=rng.normal(shift,1),
              cps=rng.normal(8+shift,2), speed_mean=rng.normal(0,1), on_ground_frac=rng.random(),
              **{c:False for c in q.HARD_INVALID}, valid_partial=False, valid_lag=False))
    cheat = ids[(ids.label==q.CHEAT)&(ids.exclude_reason=="")&(ids.client=="prestige")&(ids.module=="Aim Assist")]
    for r in cheat.itertuples(): add(r.mark_id, 25, 2.0, r.start_ms+4000)
    leg = ids[(ids.label==q.LEGIT)&(ids.exclude_reason=="")]
    print(len(cheat), "cheat segs;", list(leg.mark_id))
    for r in leg.itertuples(): add(r.mark_id, 140, 0.0, r.start_ms+4000)
    add("mdeadbeef", 3, 0, 0)
    raw = pd.DataFrame(rows)
    w = q.label_windows(raw, ids, args)
    print(w["reason"].replace("", "used").value_counts())
    d,drop = q.assign_blocks(w[w.use], 4, 7); print("gap dropped",drop, d[d.label==q.LEGIT].block.nunique(),"chunks")
    sets = q.feature_sets(d); print({k:len(v) for k,v in sets.items()})
    legit=d[d.label==q.LEGIT]; c=d[d.label==q.CHEAT]
    r = q.run_module("Aim Assist", c, legit, ids, sets, 1)
    text = q.write_report(tmp_path, {"x":1}, [r], ["n"])
    assert "Aim Assist" in text
    assert r["core"]["auc"] > 0.8
    assert r["shortcut"]["auc"] < 0.75
    assert (w.loc[w.mark_id == "mdeadbeef", "reason"] != "").all()
