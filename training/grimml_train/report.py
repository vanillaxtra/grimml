"""Run folder output: report.txt (for people), result.json (for publish) and the model files."""
from __future__ import annotations

import json
from pathlib import Path

from grimml_train.evaluation import FIGHT_MIN_WINDOWS, LEAK_AUC

BANNER = ("Single operator, a few legit recordings: these numbers say whether features and labels work, "
          "not how the model performs live.")


def fmt(x) -> str:
    return "n/a" if x is None or x != x else f"{x:.2f}"


def save_model(folder: Path, slug: str, clf, features: list[str], res: dict, built: str) -> None:
    """<slug>.txt (LightGBM) + <slug>.json (features and thresholds), the files the detector loads."""
    folder.mkdir(parents=True, exist_ok=True)
    clf.booster_.save_model(str(folder / f"{slug}.txt"))
    ops, fights = res.get("core_operating_points", {}), res.get("fights", {})
    meta = {
        "module": res["module"], "features": features,
        "window_threshold_fpr5": ops.get("fpr5", {}).get("thr"),
        "window_threshold_fpr1": ops.get("fpr1", {}).get("thr"),
        "fight_line_share": fights.get("legit_share_p95"), "fight_min_windows": FIGHT_MIN_WINDOWS,
        "held_out_core_auc": res.get("core", {}).get("auc"),
        "cheat_windows": res["cheat_windows"], "cheat_segments": res["cheat_segments"],
        "legit_windows": res["legit_windows"], "legit_chunks": res["legit_chunks"],
        "use": "score each 8 s window; flag a fight when >= fight_line_share of its windows (min fight_min_windows) "
               "score >= window_threshold_fpr5",
        "warning": "Trained on one cheat operator's sessions. Do not ban on this model alone.",
        "built": built,
    }
    (folder / f"{slug}.json").write_text(json.dumps(meta, indent=2, default=str), encoding="utf-8")


def module_lines(r: dict) -> list[str]:
    L = [f"== {r['module']} ==  cheat {r['cheat_windows']} windows / {r['cheat_segments']} segments,"
         f" legit {r['legit_windows']} windows / {r['legit_chunks']} chunks"]
    if "matched" in r:
        L.append(f"   activity-matched (equal counts per {r['matched'].get('cols')} cell, {r['matched'].get('cells')} cells)")
    if "skipped" in r:
        return L + [f"   skipped: {r['skipped']}"]
    for s in ("all", "core", "shortcut"):
        a = r[s]
        L.append(f"   {s:<9} AUC {fmt(a['auc'])}  95% CI [{fmt(a['ci'][0])}, {fmt(a['ci'][1])}]  ({a['n_features']} features)")
    ops = r["core_operating_points"]
    L.append(f"   core recall @5% legit-window FPR {ops['fpr5']['recall']:.0%}   @1% {ops['fpr1']['recall']:.0%}")
    L.append(f"   verdict: {r['verdict']}")
    L.append("   top features: " + ", ".join(k for k, _ in r["top_core_features"]))
    for s in r["segments"]:
        L.append(f"     {s['mark_id']}  {s['windows']:>4} win  @5% {s['recall_at_5pct_fpr']:.0%}  "
                 f"@1% {s['recall_at_1pct_fpr']:.0%}  {s['settings'][:60]}")
    ft = r.get("fights")
    if ft and "skipped" not in ft:
        L.append(f"   per FIGHT (flag share = windows above the 5% line; {ft['legit_units']} legit units, "
                 f"{ft['cheat_units']} cheat segments):")
        L.append(f"     legit fights: median {ft['legit_share_median']:.0%}, 95th pct {ft['legit_share_p95']:.0%}, "
                 f"max {ft['legit_share_max']:.0%}  -> decision line {ft['legit_share_p95']:.0%} (flags ~5% of legit fights)")
        L.append(f"     cheat segments above the line: {ft['cheat_caught']}/{ft['cheat_units']}   "
                 + "  ".join(f"{c['mark_id']}={c['share']:.0%}" for c in ft["cheat"]))
        L.append("     top legit fights: " + "  ".join(f"{c['unit']}={c['share']:.0%} ({c['windows']} win)"
                                                     for c in ft.get("legit_top", [])))
    elif ft:
        L.append(f"   per FIGHT: {ft['skipped']}")
    tr = r.get("transfer")
    if tr:
        L.append(f"   transfer to other clients: AUC {fmt(tr['auc'])}  windows {tr['windows_by_client']}")
        L.append(f"     recall@5% {tr['recall_fpr5']}  @1% {tr['recall_fpr1']}")
    return L


def loco_lines(r: dict) -> list[str]:
    L = [f"== LOCO {r['module']} ==  train on {'+'.join(r['train_clients'])} ({r['train_windows']} windows / "
         f"{r['train_segments']} segments), test on unseen {r['test_client']} ({r['test_windows']} windows / "
         f"{r['test_segments']} segments)"]
    if "matched" in r:
        st = r["matched"]
        L.append(f"   activity-matched on {st.get('cols')}: cheat {st.get('cheat_before')}->{st.get('cheat_after')}, "
                 f"legit {st.get('legit_before')}->{st.get('legit_after')}, {st.get('cells')} cells")
    if "skipped" in r:
        return L + [f"   skipped: {r['skipped']}"]
    for s in ("all", "core", "shortcut"):
        t = r.get(s)
        if not t:
            L.append(f"   {s:<9} n/a")
            continue
        L.append(f"   {s:<9} unseen-client AUC {fmt(t['auc'])}   recall@5% FPR {t['recall_fpr5']}   @1% {t['recall_fpr1']}")
    sh, co = (r.get("shortcut") or {}).get("auc"), (r.get("core") or {}).get("auc")
    if sh is not None and sh == sh and sh >= LEAK_AUC:
        L.append("   verdict: LEAK RISK: shortcut-only already separates, do not trust the core number")
    elif co is not None and co == co and co >= 0.7:
        L.append("   verdict: core separates the unseen client while shortcut-only does not (few segments)")
    else:
        L.append("   verdict: no clear transfer to the unseen client")
    return L


def write_run(out: Path, header: dict, results: list[dict], notes: list[str]) -> str:
    L = [BANNER, ""] + [f"{k}: {v}" for k, v in header.items()] + [""]
    for r in results:
        L += (loco_lines(r) if "test_client" in r else module_lines(r)) + [""]
    if notes:
        L += ["Notes:"] + [f"  - {n}" for n in notes]
    text = "\n".join(L)
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.txt").write_text(text + "\n", encoding="utf-8")
    payload = {"header": header, "results": results, "notes": notes}
    (out / "result.json").write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    return text
