import json
import math
import os
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pytest

from grimml_train.decode.reader import decode_file
from grimml_train.decode.records import KNOWN
from grimml_train.features.extract import FEATURE_COLUMNS, JAVA_FEATURE_COLUMNS, features_from_decoded
from grimml_train.train.lgbm import transform

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = Path(os.environ.get("GRIMML_JAVA_SAMPLE", ROOT / "target" / "selfcheck"))
PARITY = Path(__file__).parent / "fixtures" / "lgbm_parity"
JAVA_FEATURES = ROOT / "src" / "main" / "java" / "dev" / "grimml" / "inference" / "FeatureExtractor.java"


def _sample():
    gml = SAMPLE / "all-types.gmlml"
    if not gml.exists():
        if os.environ.get("GRIMML_REQUIRE_JAVA_SAMPLE"):
            pytest.fail(f"{gml} missing; mvn package must run before pytest in CI")
        pytest.skip("run mvn package first (writes target/selfcheck/all-types.gmlml)")
    return decode_file(gml), json.loads((SAMPLE / "all-types.json").read_text(encoding="utf-8"))


def test_java_sample_decodes_every_record_type():
    decoded, want = _sample()
    got_events = decoded["events"]
    assert len(got_events) == len(want["events"])
    for g, w in zip(got_events, want["events"]):
        for k, v in w.items():
            assert g[k] == v, (k, g, w)
    assert {e["record_type"] for e in got_events} >= KNOWN - {5}
    assert len(decoded["frames"]) == len(want["frames"])
    for g, w in zip(decoded["frames"], want["frames"]):
        for k, v in w.items():
            assert g[k] == v, (k, g, w)
    win = decoded["window"]
    for k in ("anon_player_id", "session_id", "ping_ms", "jitter_ms", "validity", "map_id"):
        assert win[k] == want[k], k


def test_java_and_python_features_match():
    decoded, want = _sample()
    row = features_from_decoded(decoded)
    row["ping_ms"] = decoded["window"]["ping_ms"]
    row["jitter_ms"] = decoded["window"]["jitter_ms"]
    java = want["features"]
    assert list(java) == JAVA_FEATURE_COLUMNS
    for name in JAVA_FEATURE_COLUMNS:
        j, p = java[name], row[name]
        if j is None:
            assert p is None or (isinstance(p, float) and math.isnan(p)), name
        else:
            assert p == pytest.approx(j, rel=1e-9, abs=1e-9), name


def test_java_feature_names_match_source():
    src = JAVA_FEATURES.read_text(encoding="utf-8")
    block = src[src.index("NAMES = {") : src.index("};", src.index("NAMES = {"))]
    names = [s.strip().strip('"') for s in block.split("{", 1)[1].replace("\n", " ").split(",") if s.strip()]
    assert names == FEATURE_COLUMNS


def test_lgbm_parity_fixture_matches_lightgbm():
    meta = json.loads((PARITY / "model_meta.json").read_text(encoding="utf-8"))
    cases = json.loads((PARITY / "cases.json").read_text(encoding="utf-8"))
    booster = lgb.Booster(model_file=str(PARITY / "model.txt"))

    class S:
        mean_ = np.array(meta["scaler_mean"])
        scale_ = np.array(meta["scaler_scale"])

    x = np.array([[np.nan if c["features"][n] is None else c["features"][n] for n in meta["feature_names"]] for c in cases])
    got = booster.predict(transform(S, x))
    np.testing.assert_allclose(got, [c["proba"] for c in cases], rtol=0, atol=1e-12)
