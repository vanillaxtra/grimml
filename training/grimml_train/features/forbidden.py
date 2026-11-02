from __future__ import annotations

# identity/context must never be model inputs
FORBIDDEN_FEATURE_NAMES = frozenset(
    {
        "anon_player_id",
        "session_id",
        "server_id",
        "map_id",
        "boot_id",
        "epoch_ms",
        "test_account",
        "is_test_account",
        "client_brand",
        "file_name",
        "file_date",
        "path",
        "fight_id",
        "window_id",
        "entity_id",
        "label",
        "label_source",
        "label_id",
        "mark_client_id",
        "mark_module_id",
        "operator",
        "window_key",
    }
)

GRIM_FEATURE_PREFIXES = ("grim_", "flag_count", "check_hash")
GRIM_FEATURE_NAMES = frozenset({"grim_flag_count", "check_hash", "vl", "grim_vl"})

CONFOUND_WATCH = (
    "ping_ms",
    "jitter_ms",
    "frame_count",
    "event_count",
    "window_length_ms",
    "bystander_count",
    "mspt_start_centi",
)


def is_grim_feature(name: str) -> bool:
    n = name.lower()
    if n in GRIM_FEATURE_NAMES:
        return True
    return any(n.startswith(p) or n == p.rstrip("_") for p in GRIM_FEATURE_PREFIXES)


def assert_no_forbidden_features(names: list[str], *, allow_grim: bool = False) -> None:
    bad = [n for n in names if n in FORBIDDEN_FEATURE_NAMES]
    if not allow_grim:
        bad.extend(n for n in names if is_grim_feature(n))
    if bad:
        raise AssertionError(f"forbidden feature names in model inputs: {sorted(set(bad))}")
