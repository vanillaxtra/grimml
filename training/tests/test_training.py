"""The training pipeline on synthetic windows, and the shipped config files."""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

from grimml_train import dataset as ds
from grimml_train.catalog import CHEAT, LEGIT, LabelRules, read_sessions, session_table
from grimml_train.evaluation import evaluate_module
from grimml_train.features.sets import CORE_DROP, feature_sets
from grimml_train.modules import ConfigError, ModuleRegistry
from grimml_train.publish import publish
from grimml_train.report import save_model, write_run


# ---------------------------------------------------------------- shipped config

def test_config_files_load():
    reg = ModuleRegistry.load()
    rules = LabelRules.load()
    sessions = read_sessions()
    assert {"Aim Assist", "Silent Aim", "Kill Aura", "Triggerbot"} <= set(reg.names())
    assert reg.get("instant lunge").name == "Auto Lunge"  # alias
    assert set(sessions["label"]) <= {CHEAT, LEGIT}
    # every excluded / same-fight id is a real session (catches typos in labels.yaml)
    known = set(sessions["mark_id"])
    assert set(rules.exclude) <= known
    assert set(rules.same_fight) | set(rules.same_fight.values()) <= known


def test_schema4_modules_only_see_their_families():
    for name in ModuleRegistry.load().names():
        spec = ModuleRegistry.load().get(name)
        assert not spec.schema4 or spec.families, name


def test_afk_alt_and_unlabeled_never_used():
    rules = LabelRules.load()
    t = session_table(rules, min_secs=60)
    used = set(t.loc[t["exclude_reason"] == "", "mark_id"])
    assert not used & set(rules.exclude)
    assert set(t.loc[t["exclude_reason"] == "", "label"]) <= {CHEAT, LEGIT}


def test_unknown_module_and_bad_override_are_config_errors():
    reg = ModuleRegistry.load()
    with pytest.raises(ConfigError):
        reg.get("No Such Module")
    with pytest.raises(ConfigError):
        reg.get("Aim Assist").with_overrides(families=())  # schema4 without families


# ---------------------------------------------------------------- pipeline on synthetic windows

def _synthetic(sessions: pd.DataFrame, rng) -> pd.DataFrame:
    rows = []

    def add(mid, n, shift, t0, step=9000):
        for i in range(n):
            rows.append(dict(
                window_key=f"{mid}_{i}", mark_id=mid, path=f"{mid}_{i}", epoch_ms=t0 + i * step,
                window_start_nanos=0, window_end_nanos=7e9, role=1, schema_version=3,
                ping_ms=rng.integers(20, 60), jitter_ms=rng.random(), frame_count=140, attack_count=rng.integers(0, 10),
                event_count=rng.integers(10, 50),
                yaw_vel_mean=rng.normal(shift, 1), yaw_vel_std=rng.normal(0, 1), yaw_vel_abs_mean=rng.normal(shift, 1),
                cps=rng.normal(8 + shift, 2), speed_mean=rng.normal(0, 1), on_ground_frac=rng.random(),
                **{c: False for c in ds.HARD_INVALID}, valid_partial=False, valid_lag=False))

    usable = sessions[sessions["exclude_reason"] == ""]
    for r in usable[(usable["label"] == CHEAT) & (usable["module"] == "Aim Assist")].itertuples():
        add(r.mark_id, 25, 2.0, r.start_ms + 4000)
    for r in usable[usable["label"] == LEGIT].head(6).itertuples():
        add(r.mark_id, 140, 0.0, r.start_ms + 4000)
    add("mdeadbeef", 3, 0, 0)  # not in sessions.csv
    return pd.DataFrame(rows)


def test_pipeline_separates_and_writes_a_run(tmp_path):
    rules = LabelRules.load()
    spec = ModuleRegistry.load().get("Aim Assist").with_overrides(schema4=False, min_secs=10)
    sessions = session_table(rules, min_secs=spec.min_secs)
    raw = _synthetic(sessions, np.random.default_rng(0))

    w = ds.label_windows(raw, sessions, trim_s=spec.trim_s, roles=spec.roles, schema4_only=False,
                         validity_fixed_from_ms=None)
    assert (w.loc[w["mark_id"] == "mdeadbeef", "reason"] != "").all()
    d, _ = ds.assign_blocks(w[w["use"]], rules, spec.chunk_min, spec.chunk_gap_s)
    sets = feature_sets(d)
    assert not set(sets["core"]) & CORE_DROP

    cheat, legit = d[d["label"] == CHEAT], d[d["label"] == LEGIT]
    res, clf = evaluate_module("Aim Assist", cheat, legit, sessions, sets, spec.seed)
    assert res["core"]["auc"] > 0.8
    assert res["shortcut"]["auc"] < 0.75

    run = tmp_path / "runs" / "aim_assist" / "20260101_000000"
    save_model(run / "model", "aim_assist", clf, sets["core"], res, built=run.name)
    text = write_run(run, {"module": "Aim Assist"}, [res], ["note"])
    assert "Aim Assist" in text and (run / "result.json").exists()
    meta = json.loads((run / "model" / "aim_assist.json").read_text(encoding="utf-8"))
    assert meta["features"] == sets["core"]

    # publishing one module keeps every other module in the manifest untouched
    models = tmp_path / "models"
    models.mkdir()
    other = {"module": "Kill Aura", "check": "MLKillAura", "model": "kill_aura.txt", "meta": "kill_aura.json",
             "enabled": True, "alert_share": 0.28}
    for f in ("kill_aura.txt", "kill_aura.json"):
        (models / f).write_text("x", encoding="utf-8")
    (models / "manifest.json").write_text(json.dumps({"version": 9, "models": [other]}), encoding="utf-8")
    none = tmp_path / "no-telemetry"
    m = publish([run], enable=True, models_dir=models, telemetry_dir=none)
    assert m["version"] == 10
    assert m["models"][0] == other
    new = m["models"][1]
    assert new["check"] == "MLAimAssist" and new["enabled"] and (models / "aim_assist.txt").exists()
    # republishing without --enable keeps the module's current state
    assert publish([run], models_dir=models, telemetry_dir=none)["models"][1]["enabled"] is True

    # a real server where most players play like the cheat windows: publish refuses unless told otherwise
    rng = np.random.default_rng(1)
    tel = tmp_path / "telemetry"
    lines = [{"type": "header", "schema": 5, "features": sets["core"]}]
    for i in range(12):
        shift = 2.0 if i < 6 else 0.0
        lines.append({"type": "fight", "g": i, "p": f"p{i}", "keep": 1, "band": "low"})
        for _ in range(25):
            row = {"yaw_vel_mean": rng.normal(shift, 1), "yaw_vel_std": rng.normal(0, 1),
                   "yaw_vel_abs_mean": rng.normal(shift, 1), "cps": rng.normal(8 + shift, 2),
                   "speed_mean": rng.normal(0, 1), "on_ground_frac": rng.random()}
            lines.append({"type": "window", "g": i, "p": f"p{i}", "t": 1, "a": 3, "e": 20,
                          "f": [float(row.get(f, 0.0)) for f in sets["core"]], "s": {}})
    _upload(tel / "2026-01-01" / ("ab" * 16 + "-1-00000000.jsonl.gz"), lines)
    with pytest.raises(SystemExit, match="too many real players"):
        publish([run], models_dir=models, telemetry_dir=tel)
    assert publish([run], models_dir=models, telemetry_dir=tel, allow_flags=True)["version"] == 12


def _upload(path, lines):
    import gzip

    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wt", encoding="utf-8") as fh:
        fh.write("\n".join(json.dumps(x) for x in lines) + "\n")


def test_telemetry_gate_counts_players_per_server(tmp_path):
    from grimml_train import telemetry

    lines = [{"type": "header", "schema": 5, "features": ["x"]}, {"type": "label", "p": "p0", "label": "cheat"},
             {"type": "ban", "p": "p1", "how": "vanilla"}]
    for i in range(12):
        lines.append({"type": "fight", "g": i, "p": f"p{i}", "keep": 0.5 if i == 11 else 1, "band": "low"})
        lines += [{"type": "window", "g": i, "p": f"p{i}", "t": 1, "a": 2, "e": 9, "f": [1.0 if i < 3 else 0.0]}
                  for _ in range(20)]
    lines.append({"type": "window", "g": 99, "p": "short", "t": 1, "f": [1.0]})  # too short to judge
    _upload(tmp_path / "d" / ("cd" * 16 + "-1-00000000.jsonl.gz"), lines)
    tel = telemetry.load(tmp_path)
    assert tel.windows["attack_count"].iloc[0] == 2 and tel.bans == {("cd" * 16, "p1")}
    r = telemetry.flag_shares(tel.X[:, 0], 0.5, 0.5, tel)
    s = r["servers"][0]
    # p0 is labelled cheat and p1 banned: left out, so 1 of 10 judged players is flagged
    assert (s["players"], s["players_flagged"]) == (10, 1) and s["gated"]
    assert r["banned_players"] == 1 and r["banned_flagged"] == 1
    assert telemetry.gate(r, 0.08)[0] is False and telemetry.gate(r, 0.2)[0] is True


def test_match_activity_balances_classes():
    rng = np.random.default_rng(1)
    cheat = pd.DataFrame({"attack_count": rng.integers(0, 12, 300), "event_count": rng.integers(5, 60, 300)})
    legit = pd.DataFrame({"attack_count": rng.integers(0, 4, 500), "event_count": rng.integers(5, 30, 500)},
                         index=range(1000, 1500))
    c, lg, st = ds.match_activity(cheat, legit, ["attack_count", "event_count"], seed=7)
    assert len(c) == len(lg) == st["cheat_after"] > 0
