"""Quick Prestige smoke test for grimml-train.   PIPELINE TEST ONLY.

Drop the .gmlml files of the marked sessions into  ./prestige temp test  (subfolders are fine,
file names must keep the mark id: ..._m<8 hex>_...) and run from the training folder:

    python prestige_quick.py                      # train on prestige, test per module
    python prestige_quick.py --list               # show the pre-entered ids and how many files each has
    python prestige_quick.py --modules "Aim Assist,Kill Aura"
    python prestige_quick.py --folder "D:/other folder" --chunk-min 4

What it does
  * labels every window from the mark id in its file name (the ids are pre-entered below,
    with the marker start/end times from your marks sidecar for edge trimming)
  * cheat = CHEAT marks, negatives = the LEGIT marks (the ~22 min legit recording is med3d6926)
  * leave-one-segment-out per module: a whole cheat segment and a whole legit time chunk are
    held out together, so a fight is never split across train and test
  * three feature sets: all, core (no length/ping features) and shortcut-only (window length, ping,
    counts). If the shortcut-only model scores like the real one, the label leaks through setup.
  * transfer test: train on prestige, score the same module from other clients (grim, rise, ...)

Everything here reuses the pipeline decoder and features, so numbers match `grimml-train ingest`.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

BANNER = (
    "PIPELINE TEST ONLY: one operator, legit = a few recordings, cheat and legit may come from "
    "different accounts. These numbers say whether features/labels work, not how it performs live."
)

# mark ids + marker start/end (epoch ms) from your marks sidecar, module/settings from your cheat log.
# client = the client-name stored in the marker. note = things worth a second look.

IDS = [
    {'mark_id': 'me9b0dc92', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Aim Assist', 'settings': 'FOV:90 SPEED:50 SMOOTHING:0 RANDOMNESS:70 ONLY_ON_ROTATE:FALSE', 'start_ms': 1793626734393, 'end_ms': 1793626879582, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'ma2e0fb94', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Aim Assist', 'settings': 'FOV:360 SPEED:50 SMOOTHING:75 RANDOMNESS:70 ONLY_ON_ROTATE:FALSE', 'start_ms': 1793627040507, 'end_ms': 1793627229998, 'interrupted': False, 'multi_module': False, 'note': 'log: fov 360 / randomness 70, marker: fov 0 / randomness 75'},
    {'mark_id': 'm9ec7d620', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Aim Assist', 'settings': 'FOV:360 SPEED:25.08 SMOOTHING:75 RANDOMNESS:70 ONLY_ON_ROTATE:FALSE', 'start_ms': 1793627315211, 'end_ms': 1793627453238, 'interrupted': False, 'multi_module': False, 'note': 'log: fov 360 / randomness 70, marker: fov 0 / randomness 75'},
    {'mark_id': 'm22700b10', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Kill Aura', 'settings': 'HUMANIZED:FALSE REACH:3 MISS_CHANCE:0 COOLDOWN_RANGE_MIN:88 COOLDOWN_RANGE_MAX:100', 'start_ms': 1793627685477, 'end_ms': 1793628011013, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mf2dd0754', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Kill Aura', 'settings': 'HUMANIZED:TRUE REACH:2.95 MISS_CHANCE:0 COOLDOWN_RANGE_MIN:88 COOLDOWN_RANGE_MAX:100', 'start_ms': 1793628147221, 'end_ms': 1793628385027, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm89ff4396', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Mace', 'settings': 'HORS_TARGET_DISTANCE:5.0 MIN_FALL_DISTANCE:10 STUNSLAM:FALSE SILENT_AIM:TRUE SWAP_BACK:FALSE', 'start_ms': 1793628828339, 'end_ms': 1793629104425, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm3ddf06f9', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Lunge', 'settings': 'SWAP_DELAY_MIN:20 SWAP_DELAY_MAX:70 SWAP_BACK:TRUE', 'start_ms': 1793629224552, 'end_ms': 1793629363358, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mb6a1b94b', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Lunge', 'settings': 'SWAP_DELAY_MIN:0 SWAP_DELAY_MAX:0 SWAP_BACK:TRUE', 'start_ms': 1793629723046, 'end_ms': 1793629856389, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm2fe00ef9', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Breach Swap', 'settings': 'CHANCE:100', 'start_ms': 1793629986983, 'end_ms': 1793630107861, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mb1f6fa34', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Silent Aim', 'settings': 'CONTINOUS_TRACKING:FALSE', 'start_ms': 1793630170325, 'end_ms': 1793630330056, 'interrupted': False, 'multi_module': False, 'note': 'log says CONTINOUS_TRACKING:FALSE but the marker says true'},
    {'mark_id': 'md5e8301b', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Silent Aim', 'settings': 'CONTINOUS_TRACKING:TRUE', 'start_ms': 1793630381965, 'end_ms': 1793630693771, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm12b44916', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Triggerbot', 'settings': 'DELAY_MIN_MS:0 DELAY_MAX_MS:0 MISS_CHANCE:0 COOLDOWN_MIN_PERCENT:80 COOLDOWN_MAX_PERCENT:100', 'start_ms': 1793631084664, 'end_ms': 1793631339636, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm109c3312', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Triggerbot', 'settings': 'DELAY_MIN_MS:25 DELAY_MAX_MS:50 MISS_CHANCE:0 COOLDOWN_MIN_PERCENT:80 COOLDOWN_MAX_PERCENT:100', 'start_ms': 1793631384317, 'end_ms': 1793631553565, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm37a281c0', 'label': 'CHEAT', 'client': 'prestige', 'module': 'UNLOGGED', 'settings': 'chance=100', 'start_ms': 1793631736051, 'end_ms': 1793631825311, 'interrupted': False, 'multi_module': False, 'note': '89 s, module unknown (not in your log)'},
    {'mark_id': 'm56635106', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto W Tap', 'settings': 'CHANCE:100 HOLD_LENGTH_MIN:100 HOLD_LENGTH_MAX:200 REACTION_TIME_MIN:10 REACTION_TIME_MAX:30', 'start_ms': 1793631975514, 'end_ms': 1793632142123, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm126182c9', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Wind Charge Aura', 'settings': 'REACH:2.95 HUMANIZED:TRUE', 'start_ms': 1793632532237, 'end_ms': 1793632651931, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm3bc01e38', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Mace', 'settings': 'horizontal_target_distance=5 min_fall_distance=0 silent_aim=true stunslam=true swap_back=false', 'start_ms': 1793633970851, 'end_ms': 1793634162409, 'interrupted': False, 'multi_module': False, 'note': 'not in your log; settings match mc8bdb2be (stunslam=true), module inferred'},
    {'mark_id': 'mc8bdb2be', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Mace', 'settings': 'STUNSLAM:TRUE CHANCE:100 SILENT_AIM:TRUE SWAP_BACK:TRUE', 'start_ms': 1793634298919, 'end_ms': 1793634740164, 'interrupted': False, 'multi_module': False, 'note': 'log says SWAP_BACK:TRUE but the marker says swap_back=false'},
    {'mark_id': 'm8b2b7b6a', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Anchor Macro', 'settings': 'ANCHOR_DELAY_MIN:30 ANCHOR_DELAY_MAX:50 SILENT_AIM:FALSE', 'start_ms': 1793635755984, 'end_ms': 1793635870143, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'md82d7087', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Anchor Macro', 'settings': 'ANCHOR_DELAY_MIN:0 ANCHOR_DELAY_MAX:0 SILENT_AIM:FALSE', 'start_ms': 1793635974374, 'end_ms': 1793636049784, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm49a01405', 'label': 'CHEAT', 'client': 'prestige', 'module': 'UNLOGGED', 'settings': 'damage_tick=false delay_max_ms=78 delay_min_ms=10 head_bob=true', 'start_ms': 1793636403934, 'end_ms': 1793636423299, 'interrupted': True, 'multi_module': False, 'note': '19 s, interrupted, restarted as mc4d1d678'},
    {'mark_id': 'mc4d1d678', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Crystal', 'settings': 'DAMAGE_TICK_FALSE DELAY_MIN_MS:10 DELAY_MAX_MS:78 HEAD_BOB:TRUE', 'start_ms': 1793636423299, 'end_ms': 1793636469847, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm2bf596fd', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Pearl Catch', 'settings': 'WIND_CHARGE_DELAY:120 MOVING_DELAY:80 LEGIT_ROTATE:FALSE SMOOTHING:0.0', 'start_ms': 1793653360451, 'end_ms': 1793653464143, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm626f669b', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Pearl Catch', 'settings': 'WIND_CHARGE_DELAY:50 MOVING_DELAY:50 LEGIT_ROTATE:FALSE SMOOTHING:0.0', 'start_ms': 1793653551622, 'end_ms': 1793653657518, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm426bed25', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Pearl Catch', 'settings': 'WIND_CHARGE_DELAY:50 MOVING_DELAY:50 LEGIT_ROTATE:TRUE SMOOTHING:0.5', 'start_ms': 1793653796869, 'end_ms': 1793653940287, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm85069287', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Diagonal Pearl Catch', 'settings': 'WIND_CHARGE_DELAY:50 SILENT_AIM:TRUE LEGIT_ROTATE:FALSE', 'start_ms': 1793654233972, 'end_ms': 1793654434296, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm1bd74715', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Diagonal Pearl Catch', 'settings': 'WIND_CHARGE_DELAY:50 SILENT_AIM:TRUE LEGIT_ROTATE:TRUE', 'start_ms': 1793654571972, 'end_ms': 1793654833410, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'md923db5a', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Xbow Cart', 'settings': 'DELAY_TICKS:2 SILENT_AIM:TRUE SILENT_AIM_FOV:180', 'start_ms': 1793664803693, 'end_ms': 1793664983166, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm46eb4f21', 'label': 'CHEAT', 'client': 'prestige', 'module': 'UNLOGGED', 'settings': 'delay_ticks=2 silent_aim=true silent_aim_fov=180', 'start_ms': 1793665016531, 'end_ms': 1793665018948, 'interrupted': False, 'multi_module': False, 'note': '2 s accidental mark'},
    {'mark_id': 'm292a06ce', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Xbow Cart', 'settings': 'DELAY_TICKS:0 SILENT_AIM:TRUE SILENT_AIM_FOV:180', 'start_ms': 1793665021683, 'end_ms': 1793665241523, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm63659df6', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Shield Breaker', 'settings': 'CHANCE:100 STUN:TRUE HIT_DELAY_MIN_MS:0 HIT_DELAY_MAX_MS:0 BREAK_COOLDOWN_MAX_MS:1200 BREAK_COOLDOWN_MIN_MS:900', 'start_ms': 1793665936838, 'end_ms': 1793666282689, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm94403eea', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Shield Breaker', 'settings': 'CHANCE:100 STUN:TRUE HIT_DELAY_MIN_MS:0 HIT_DELAY_MAX_MS:0 BREAK_COOLDOWN_MAX_MS:600 BREAK_COOLDOWN_MIN_MS:300', 'start_ms': 1793666351970, 'end_ms': 1793666647804, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mf97e1a20', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Shield Breaker', 'settings': 'CHANCE:100 STUN:TRUE HIT_DELAY_MIN_MS:0 HIT_DELAY_MAX_MS:0 BREAK_COOLDOWN_MAX_MS:0 BREAK_COOLDOWN_MIN_MS:0', 'start_ms': 1793666700636, 'end_ms': 1793666973206, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm0a6016b0', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Aim Assist', 'settings': 'FOV:360 SPEED:10.09 SMOOTHING:75 RANDOMNESS:70 ONLY_ON_ROTATE:FALSE', 'start_ms': 1793667638941, 'end_ms': 1793667904327, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm4108b362', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Aim Assist', 'settings': 'FOV:360 SPEED:100 SMOOTHING:0 RANDOMNESS:70 ONLY_ON_ROTATE:TRUE', 'start_ms': 1793668202163, 'end_ms': 1793668335193, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm70c1ef87', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Triggerbot', 'settings': 'DELAY_MIN_MS:0 DELAY_MAX_MS:0 MISS_CHANCE:0 COOLDOWN_MIN_PERCENT:94 COOLDOWN_MAX_PERCENT:100', 'start_ms': 1793668905179, 'end_ms': 1793669179697, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm1dff7271', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Shield Breaker|Kill Aura', 'settings': 'HUMANIZED:TRUE REACH:2.95 MISS_CHANCE:0 COOLDOWN_RANGE_MIN:88 COOLDOWN_RANGE_MAX:100', 'start_ms': 1793728410449, 'end_ms': 1793728928323, 'interrupted': False, 'multi_module': True, 'note': ''},
    {'mark_id': 'm3367bfcd', 'label': 'CHEAT', 'client': 'prestige', 'module': 'Auto Shield Breaker|Silent Aim', 'settings': 'CONTINOUS_TRACKING:TRUE', 'start_ms': 1793731852010, 'end_ms': 1793732505110, 'interrupted': False, 'multi_module': True, 'note': ''},
    {'mark_id': 'med3d6926', 'label': 'LEGIT_CONFIRMED', 'client': '', 'module': 'LEGIT', 'settings': '', 'start_ms': 1793925135475, 'end_ms': 1793926477847, 'interrupted': False, 'multi_module': False, 'note': 'THE ~22 min legit recording (ended cleanly)'},
    {'mark_id': 'm5c84ea76', 'label': 'LEGIT_CONFIRMED', 'client': '', 'module': 'LEGIT', 'settings': '', 'start_ms': 1793925139516, 'end_ms': 1793926235154, 'interrupted': True, 'multi_module': False, 'note': 'legit, ended when the player quit (interrupted)'},
    {'mark_id': 'm9e797b92', 'label': 'LEGIT_CONFIRMED', 'client': '', 'module': 'LEGIT', 'settings': '', 'start_ms': 1793925523767, 'end_ms': 1793926490353, 'interrupted': True, 'multi_module': False, 'note': 'legit, ended when the player quit (interrupted)'},
    {'mark_id': 'm5045e1ea', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Stun Slam', 'settings': 'MACE_DELAY_MIN_TICKS:1 MACE_DELAY_MAX_TICKS:1', 'start_ms': 1794011270021, 'end_ms': 1794011587006, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mc516ec9d', 'label': 'CHEAT', 'client': 'grim', 'module': 'Triggerbot', 'settings': 'COOLDOWN_MIN_PERCENT:90 COOLDOWN_MAX_PERCENT:90', 'start_ms': 1794014389422, 'end_ms': 1794014929530, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm24355c9b', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Shield Breaker', 'settings': 'STUN:TRUE', 'start_ms': 1794016595880, 'end_ms': 1794016874803, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'ma635ec0c', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Shield Breaker|Triggerbot', 'settings': 'COOLDOWN_MIN_PERCENT:90 COOLDOWN_MAX_PERCENT:90', 'start_ms': 1794016937999, 'end_ms': 1794017221470, 'interrupted': False, 'multi_module': True, 'note': ''},
    {'mark_id': 'mf3dc7f00', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Shield Breaker|Kill Aura', 'settings': 'STUN:TRUE', 'start_ms': 1794017360032, 'end_ms': 1794017832529, 'interrupted': False, 'multi_module': True, 'note': ''},
    {'mark_id': 'm365353b1', 'label': 'CHEAT', 'client': 'grim', 'module': 'Aim Assist', 'settings': 'SPEED:50', 'start_ms': 1794054177046, 'end_ms': 1794054695041, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm004031bb', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Mace', 'settings': 'STUN_SLAM:TRUE SILENT_AIM:FALSE', 'start_ms': 1794056612803, 'end_ms': 1794057109013, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mf7bb67e8', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Wind Charge Hitter', 'settings': 'HUMANIZED:TRUE REACH:3', 'start_ms': 1794057256083, 'end_ms': 1794057560849, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm9c80fe11', 'label': 'CHEAT', 'client': 'grim', 'module': 'Auto Lunge', 'settings': 'SWITCH_DELAY_MAX:0 SWITCH_DELAY_MIN:0 SWAP_BACK_TRUE', 'start_ms': 1794057882448, 'end_ms': 1794058111621, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm0bca2310', 'label': 'CHEAT', 'client': 'rise', 'module': 'Auto Xbow Cart', 'settings': 'DELAY_TICKS:0 SILENT_AIM:TRUE PLACE_PROTECTP:FALSE', 'start_ms': 1794059482918, 'end_ms': 1794059706641, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'mc9a8d071', 'label': 'CHEAT', 'client': 'rise', 'module': 'Auto Xbow Cart', 'settings': 'DELAY_TICKS:0 SILENT_AIM:TRUE PLACE_PROTECTP:TRUE', 'start_ms': 1794062370934, 'end_ms': 1794062567050, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm13d96247', 'label': 'CHEAT', 'client': 'grim', 'module': 'Kill Aura', 'settings': 'ROTATIONS:SNAP REACH:3 MISS_CHANCE:0 STUN:TRUE', 'start_ms': 1794062884345, 'end_ms': 1794063220269, 'interrupted': False, 'multi_module': False, 'note': ''},
    {'mark_id': 'm3f63358f', 'label': 'CHEAT', 'client': 'doomsday', 'module': 'Triggerbot', 'settings': 'cooldown_max_percent=100 cooldown_min_percent=90 delay_max_ms=0 delay_min_ms=0 miss_chance=0', 'start_ms': 1794349673670, 'end_ms': 1794349957035, 'interrupted': False, 'multi_module': False, 'note': 'doomsday; not in your log, module inferred from the setting keys'},
    {'mark_id': 'm7ecb1998', 'label': 'CHEAT', 'client': 'doomsday', 'module': 'UNLOGGED', 'settings': 'chance=100', 'start_ms': 1794350913243, 'end_ms': 1794350948212, 'interrupted': False, 'multi_module': False, 'note': '35 s doomsday, module unknown (not in your log)'},
]

SELECTED_DEFAULT = ["Aim Assist", "Silent Aim", "Kill Aura", "Triggerbot"]
LEGIT = "LEGIT_CONFIRMED"
CHEAT = "CHEAT"
SHORTCUT_COLS = ["win_len_ms", "frame_count", "event_count", "attack_count", "ping_ms", "jitter_ms"]
CORE_DROP = {"ping_ms", "jitter_ms", "frame_count", "attack_count"}
# Recorder builds before the per-window validity fix (2026-11-10) OR'd the event bits for the player's whole
# session, so valid_death/respawn/teleport/gamemode were set on almost every window after the first such event.
# Those bits mean nothing in old files. Set this to the wall-clock UTC epoch ms when the fixed plugin went live;
# windows recorded at/after it get the hard-invalid filter, earlier windows ignore it. None = no fixed build
# deployed yet, so every file is treated as old. See docs/known-issues.md ("sticky validity bits").
VALIDITY_FIXED_FROM_MS: int | None = None
HARD_INVALID = [
    "valid_death", "valid_respawn", "valid_teleport", "valid_setback",
    "valid_world", "valid_gamemode", "valid_vanish",
]
FLAG_COLS = ["valid_partial", "valid_lag", *HARD_INVALID]


# ---------------------------------------------------------------- id table

def id_table(args) -> pd.DataFrame:
    t = pd.DataFrame(IDS)
    t["secs"] = (t["end_ms"] - t["start_ms"]) / 1000.0

    def reason(r) -> str:
        if r.label == CHEAT:
            if r.multi_module:
                return "two modules were on at once"
            if r.interrupted:
                return "segment was interrupted"
            if r.module == "UNLOGGED":
                return "module not in your log"
            if r.secs < args.min_secs:
                return f"shorter than {args.min_secs:.0f}s"
        elif r.interrupted and not args.include_interrupted_legit:
            return "segment was interrupted"
        return ""

    t["exclude_reason"] = t.apply(reason, axis=1)
    if args.legit_ids:
        keep = {x.strip().lower() for x in args.legit_ids.split(",") if x.strip()}
        t.loc[(t.label == LEGIT) & ~t.mark_id.isin(keep), "exclude_reason"] = "not in --legit-ids"
    return t


# ---------------------------------------------------------------- decode

def find_files(folder: Path) -> list[Path]:
    return sorted(Path(folder).rglob("*.gmlml"))


MARK_RE = re.compile(r"_(m[0-9a-fA-F]{8})_")
BULK_ID = "bulk"  # pseudo mark id for sampled bulk legit windows


def load_processed_bulk(win_dir: Path, holdout_file: str) -> tuple[pd.DataFrame, str]:
    """Windows an earlier `grimml-train ingest` already decoded (features included). Live hold-out accounts are dropped."""
    parts = sorted(win_dir.glob("*.parquet"))
    if not parts:
        raise SystemExit(f"no parquet files in {win_dir}")
    b = pd.concat((pd.read_parquet(p) for p in parts), ignore_index=True)
    hp = Path(holdout_file)
    if hp.exists():
        hold = {x.strip() for x in hp.read_text(encoding="utf8").splitlines() if x.strip()}
        b = b[~b["anon_player_id"].astype(str).isin(hold)]
    return b.reset_index(drop=True), f"{len(parts)} parquet parts"


def sample_bulk(root: Path, n: int, seed: int, listing_cache: Path) -> list[Path]:
    """Random sample of n unmarked files from the bulk dump. The directory listing (slow on millions of files)
    is cached once; only the sampled files are ever decoded."""
    if listing_cache.exists():
        files = listing_cache.read_text(encoding="utf8").splitlines()
    else:
        files, stack, t0 = [], [str(root)], time.time()
        print("listing the bulk folder once (slow on millions of files; cached afterwards) ...", flush=True)
        while stack:
            with os.scandir(stack.pop()) as it:
                for e in it:
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.name.endswith(".gmlml"):
                        files.append(e.path)
                        if len(files) % 100_000 == 0:
                            print(f"  listed {len(files):,} files, {time.time() - t0:.0f}s", flush=True)
        listing_cache.parent.mkdir(parents=True, exist_ok=True)
        listing_cache.write_text("\n".join(files), encoding="utf8")
    files = [f for f in files if not MARK_RE.search(os.path.basename(f))]
    random.Random(seed).shuffle(files)
    return [Path(f) for f in files[:n]]


def decode_folder(folder: Path, workers: int, paths: list[Path] | None = None) -> tuple[pd.DataFrame, list[tuple[str, str]]]:
    from grimml_train.ingest import _process_path  # same decode + features as `grimml-train ingest`

    if paths is None:
        paths = find_files(folder)
    jobs = [(str(p), False, str(Path(folder).resolve())) for p in paths]
    if workers <= 0:
        workers = max(1, min(8, (os.cpu_count() or 2) - 1))
    if workers == 1 or len(jobs) < 200:
        results = [_process_path(j) for j in jobs]
    else:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(_process_path, jobs, chunksize=16))
    wins, errs = [], []
    for r in results:
        if r.get("error"):
            errs.append((r["path"], r["error"]))
        else:
            wins.append(r["window"])
    return pd.DataFrame(wins), errs


def _num(df: pd.DataFrame, col: str) -> pd.Series:
    if col in df.columns:
        return pd.to_numeric(df[col], errors="coerce")
    return pd.Series(np.nan, index=df.index)


def label_windows(w: pd.DataFrame, ids: pd.DataFrame, args) -> pd.DataFrame:
    """Attach label/module/client from the file-name mark id; mark every unusable window with a reason."""
    w = w.copy()
    epoch = _num(w, "epoch_ms")
    has_epoch = epoch > 1e12
    w["win_len_ms"] = (_num(w, "window_end_nanos") - _num(w, "window_start_nanos")) / 1e6
    w["ts_ms"] = np.where(has_epoch, epoch, _num(w, "window_start_nanos") / 1e6)
    w["has_epoch"] = has_epoch

    m = ids[["mark_id", "label", "client", "module", "settings", "start_ms", "end_ms", "exclude_reason"]]
    m = m.add_prefix("id_").rename(columns={"id_mark_id": "mark_id"})
    w = w.merge(m, on="mark_id", how="left")
    w["label"] = w["id_label"]

    reason = pd.Series("", index=w.index, dtype=object)

    def mark(mask, text):
        mask = mask & (reason == "")
        reason[mask] = text

    mark(w["mark_id"].isna(), "no mark id in file name")
    mark(w["mark_id"].notna() & w["id_label"].isna(), "mark id not in the table")
    excl = w["id_exclude_reason"].fillna("")
    mark(excl != "", "segment: " + excl)
    if args.roles:
        role = _num(w, "role")
        mark(role.notna() & ~role.isin(args.roles), "role not in --roles")
    trim = args.trim_s * 1000.0
    ws = epoch
    we = epoch + w["win_len_ms"].fillna(7000.0)
    outside = has_epoch & w["id_start_ms"].notna() & ((ws < w["id_start_ms"] + trim) | (we > w["id_end_ms"] - trim))
    mark(outside, "outside the trimmed segment")
    # hard-invalid filter: only on windows recorded by a fixed plugin build (see VALIDITY_FIXED_FROM_MS)
    mode = getattr(args, "hard_invalid", "auto")
    fixed_from = getattr(args, "validity_fixed_from_ms", VALIDITY_FIXED_FROM_MS)
    if mode == "drop":
        trust = pd.Series(True, index=w.index)
    elif mode == "keep" or fixed_from is None:
        trust = pd.Series(False, index=w.index)
    else:
        trust = has_epoch & (epoch >= fixed_from)
    w["validity_trusted"] = trust
    if trust.any():
        bad = pd.Series(False, index=w.index)
        for c in HARD_INVALID:
            if c in w.columns:
                bad |= w[c].fillna(False).astype(bool)
        mark(bad & trust, "hard validity flag (death/teleport/setback/...)")
    w["reason"] = reason
    w["use"] = reason == ""
    return w


def assign_blocks(d: pd.DataFrame, chunk_min: float, gap_s: float, bulk_groups: int = 8) -> tuple[pd.DataFrame, int]:
    """Cheat block = mark id (one segment). Legit block = a time chunk of the legit recording."""
    d = d.copy()
    d["block"] = d["mark_id"]
    keep = pd.Series(True, index=d.index)
    for mid, g in d[d["label"] == LEGIT].groupby("mark_id"):
        if mid == BULK_ID:
            # bulk legit: block = a group of whole players (never split one player across train/test)
            grp = g["anon_player_id"].astype(str).map(lambda a: zlib.crc32(a.encode()) % bulk_groups)
            d.loc[g.index, "block"] = [f"bulk:{i}" for i in grp]
            continue
        t = g["ts_ms"]
        span = float(t.max() - t.min())
        n = max(1, int(round(span / (chunk_min * 60_000)))) if span > 0 else 1
        width = span / n + 1.0
        rel = t - t.min()
        idx = np.minimum((rel // width).astype(int), n - 1)
        d.loc[g.index, "block"] = [f"{mid}:{i}" for i in idx]
        if gap_s > 0 and n > 1:
            pos = rel - idx * width
            drop = ((idx > 0) & (pos < gap_s * 1000.0)).to_numpy()
            keep.loc[g.index[drop]] = False
    return d[keep], int((~keep).sum())


# ---------------------------------------------------------------- model

def _fit(train: pd.DataFrame, cols: list[str], seed: int):
    import lightgbm as lgb

    clf = lgb.LGBMClassifier(
        n_estimators=150, learning_rate=0.05, num_leaves=8, min_child_samples=5,
        subsample=0.8, subsample_freq=1, colsample_bytree=0.8,
        class_weight="balanced", random_state=seed, verbosity=-1,
    )
    clf.fit(train[cols].to_numpy(dtype=float), (train["label"] == CHEAT).astype(int).to_numpy())
    return clf


def _score(clf, df: pd.DataFrame, cols: list[str]) -> np.ndarray:
    return clf.predict_proba(df[cols].to_numpy(dtype=float))[:, 1]


def loso(cheat: pd.DataFrame, legit: pd.DataFrame, cols: list[str], seed: int):
    """Hold out one whole cheat segment + one whole legit chunk per fold."""
    segs, chunks = sorted(cheat["block"].unique()), sorted(legit["block"].unique())
    if len(segs) < 2 or len(chunks) < 2:
        return None
    rows = []
    for j in range(max(len(segs), len(chunks))):
        s, c = segs[j % len(segs)], chunks[j % len(chunks)]
        te = pd.concat([cheat[cheat["block"] == s], legit[legit["block"] == c]])
        tr = pd.concat([cheat[cheat["block"] != s], legit[legit["block"] != c]])
        te = te.assign(score=_score(_fit(tr, cols, seed + j), te, cols))
        rows.append(te[["window_key", "label", "block", "mark_id", "score"]])
    return (
        pd.concat(rows)
        .groupby("window_key", as_index=False)
        .agg(label=("label", "first"), block=("block", "first"), mark_id=("mark_id", "first"), score=("score", "mean"))
    )


def _auc(label: np.ndarray, score: np.ndarray) -> float:
    from sklearn.metrics import roc_auc_score

    y = (label == CHEAT)
    if y.all() or (~y).all():
        return float("nan")
    return float(roc_auc_score(y, score))


def block_ci(oof: pd.DataFrame, n: int = 200, seed: int = 0) -> tuple[float, float]:
    rng = np.random.default_rng(seed)
    groups = {b: (g["label"].iloc[0], g["score"].to_numpy()) for b, g in oof.groupby("block")}
    cb = [b for b, (lab, _) in groups.items() if lab == CHEAT]
    lb = [b for b, (lab, _) in groups.items() if lab != CHEAT]
    vals = []
    for _ in range(n):
        pick = list(rng.choice(cb, len(cb))) + list(rng.choice(lb, len(lb)))
        sc = np.concatenate([groups[b][1] for b in pick])
        lab = np.concatenate([[groups[b][0]] * len(groups[b][1]) for b in pick])
        a = _auc(lab, sc)
        if a == a:
            vals.append(a)
    if not vals:
        return float("nan"), float("nan")
    return float(np.quantile(vals, 0.025)), float(np.quantile(vals, 0.975))


def operating_points(oof: pd.DataFrame) -> dict:
    legit = oof.loc[oof["label"] == LEGIT, "score"].to_numpy()
    cheat = oof.loc[oof["label"] == CHEAT, "score"].to_numpy()
    out = {}
    for f in (0.05, 0.01):
        thr = float(np.quantile(legit, 1 - f))
        out[f"fpr{int(f * 100)}"] = {"thr": thr, "recall": float((cheat >= thr).mean())}
    return out


def per_segment(oof: pd.DataFrame, ops: dict, ids: pd.DataFrame) -> list[dict]:
    rows = []
    for b, g in oof[oof["label"] == CHEAT].groupby("block"):
        info = ids[ids["mark_id"] == b].iloc[0]
        rows.append({
            "mark_id": b, "settings": info["settings"], "windows": int(len(g)),
            "recall_at_5pct_fpr": float((g["score"] >= ops["fpr5"]["thr"]).mean()),
            "recall_at_1pct_fpr": float((g["score"] >= ops["fpr1"]["thr"]).mean()),
            "median_score": float(g["score"].median()),
        })
    return rows


def transfer(train_cheat, legit, other_cheat, cols, seed):
    chunks = sorted(legit["block"].unique())
    if len(chunks) < 2 or other_cheat.empty or train_cheat.empty:
        return None
    leg, cheat = [], []
    for j, c in enumerate(chunks):
        tr = pd.concat([train_cheat, legit[legit["block"] != c]])
        clf = _fit(tr, cols, seed + j)
        leg.append(_score(clf, legit[legit["block"] == c], cols))
        cheat.append(pd.Series(_score(clf, other_cheat, cols), index=other_cheat.index))
    legit_all = np.concatenate(leg)
    mean_c = pd.concat(cheat, axis=1).mean(axis=1)
    res = {"auc": _auc(np.r_[[LEGIT] * len(legit_all), [CHEAT] * len(mean_c)], np.r_[legit_all, mean_c.to_numpy()])}
    for f in (0.05, 0.01):
        thr = float(np.quantile(legit_all, 1 - f))
        res[f"recall_fpr{int(f * 100)}"] = {
            str(cl): float((s >= thr).mean()) for cl, s in mean_c.groupby(other_cheat.loc[mean_c.index, "id_client"])
        }
    res["windows_by_client"] = {str(k): int(v) for k, v in other_cheat["id_client"].value_counts().items()}
    return res


def verdict(core_auc: float, short_auc: float, n_seg: int, n_chunks: int) -> str:
    if core_auc != core_auc:
        return "not enough segments/chunks"
    if short_auc == short_auc and short_auc >= 0.75:
        return "LEAK RISK: window length/ping/counts alone separate the labels"
    if core_auc < 0.6:
        return "no clear separation (features, or the setting is too subtle)"
    note = "separates on held-out segments"
    if n_seg < 3 or n_chunks < 3:
        note += " (very few blocks, treat as a hint)"
    return note + "; single operator"


def run_module(name, cheat, legit, ids, sets, seed) -> dict:
    res = {
        "module": name,
        "cheat_windows": int(len(cheat)), "cheat_segments": int(cheat["block"].nunique()),
        "legit_windows": int(len(legit)), "legit_chunks": int(legit["block"].nunique()),
    }
    oofs = {}
    for set_name, cols in sets.items():
        oof = loso(cheat, legit, cols, seed)
        if oof is None:
            res["skipped"] = "needs at least 2 cheat segments and 2 legit chunks"
            return res
        lo, hi = block_ci(oof)
        res[set_name] = {"auc": _auc(oof["label"].to_numpy(), oof["score"].to_numpy()), "ci": [lo, hi], "n_features": len(cols)}
        oofs[set_name] = oof
    ops = operating_points(oofs["core"])
    res["core_operating_points"] = ops
    res["segments"] = per_segment(oofs["core"], ops, ids)
    full = pd.concat([cheat, legit])
    clf = _fit(full, sets["core"], seed)
    gain = clf.booster_.feature_importance("gain")
    top = sorted(zip(sets["core"], gain), key=lambda x: -x[1])[:5]
    res["top_core_features"] = [(k, float(v)) for k, v in top]
    res["verdict"] = verdict(res["core"]["auc"], res["shortcut"]["auc"], res["cheat_segments"], res["legit_chunks"])
    return res


# ---------------------------------------------------------------- report

def feature_sets(d: pd.DataFrame) -> dict[str, list[str]]:
    from grimml_train.features.extract import FEATURE_COLUMNS

    base = [c for c in FEATURE_COLUMNS if c in d.columns]
    if "win_len_ms" in d.columns and "win_len_ms" not in base:
        base = base + ["win_len_ms"]
    ok = [c for c in base if d[c].notna().any()]
    core = [c for c in ok if c not in CORE_DROP and c != "win_len_ms"]
    short = [c for c in SHORTCUT_COLS if c in d.columns and d[c].notna().any()]
    return {"all": ok, "core": core, "shortcut": short or ok[:1]}


def fmt(x) -> str:
    return "n/a" if x is None or x != x else f"{x:.2f}"


def write_report(out: Path, header: dict, results: list[dict], notes: list[str]) -> str:
    L = [BANNER, ""]
    L += [f"{k}: {v}" for k, v in header.items()]
    L.append("")
    for r in results:
        L.append(f"== {r['module']} ==  cheat {r['cheat_windows']} windows / {r['cheat_segments']} segments,"
                 f" legit {r['legit_windows']} windows / {r['legit_chunks']} chunks")
        if "skipped" in r:
            L += [f"   skipped: {r['skipped']}", ""]
            continue
        for s in ("all", "core", "shortcut"):
            a = r[s]
            L.append(f"   {s:<9} AUC {fmt(a['auc'])}  95% CI [{fmt(a['ci'][0])}, {fmt(a['ci'][1])}]  ({a['n_features']} features)")
        ops = r["core_operating_points"]
        L.append(f"   core recall @5% legit-window FPR {ops['fpr5']['recall']:.0%}   @1% {ops['fpr1']['recall']:.0%}")
        L.append(f"   verdict: {r['verdict']}")
        L.append("   top features: " + ", ".join(k for k, _ in r["top_core_features"]))
        for s in r["segments"]:
            L.append(f"     {s['mark_id']}  {s['windows']:>4} win  @5% {s['recall_at_5pct_fpr']:.0%}  @1% {s['recall_at_1pct_fpr']:.0%}"
                     f"  {s['settings'][:60]}")
        tr = r.get("transfer")
        if tr:
            L.append(f"   transfer to other clients: AUC {fmt(tr['auc'])}  windows {tr['windows_by_client']}")
            L.append(f"     recall@5% {tr['recall_fpr5']}  @1% {tr['recall_fpr1']}")
        L.append("")
    if notes:
        L.append("Warnings:")
        L += [f"  - {n}" for n in notes]
    text = "\n".join(L)
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.txt").write_text(text, encoding="utf-8")
    (out / "results.json").write_text(json.dumps({"header": header, "results": results, "notes": notes}, indent=2, default=str), encoding="utf-8")
    return text


# ---------------------------------------------------------------- main

def list_mode(folder: Path, ids: pd.DataFrame) -> None:
    files = find_files(folder) if Path(folder).exists() else []
    counts: dict[str, int] = {}
    import re

    for p in files:
        m = re.search(r"_(m[0-9a-fA-F]{8})_", p.name)
        if m:
            counts[m.group(1).lower()] = counts.get(m.group(1).lower(), 0) + 1
    print(f"folder: {folder}  ({len(files)} .gmlml files, exists={Path(folder).exists()})")
    print(f"{'mark id':<12}{'label':<17}{'client':<10}{'module':<22}{'secs':>6}{'files':>7}  usable?")
    for r in ids.itertuples():
        print(f"{r.mark_id:<12}{r.label:<17}{str(r.client):<10}{str(r.module):<22}{r.secs:>6.0f}{counts.get(r.mark_id, 0):>7}  {r.exclude_reason or 'yes'}")
    unknown = sorted(set(counts) - set(ids.mark_id))
    if unknown:
        print("mark ids in the folder that are not in the table:", ", ".join(unknown))


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Quick Prestige smoke test for grimml-train (pipeline test only).")
    p.add_argument("--folder", default="prestige temp test")
    p.add_argument("--out", default="out_quick")
    p.add_argument("--list", action="store_true", help="show pre-entered ids and file counts, then exit")
    p.add_argument("--modules", default=",".join(SELECTED_DEFAULT), help="comma list, or 'all'")
    p.add_argument("--train-client", default="Prestige")
    p.add_argument("--trim-s", type=float, default=3.0, help="seconds dropped at each end of a segment")
    p.add_argument("--min-secs", type=float, default=60.0)
    p.add_argument("--chunk-min", type=float, default=4.0, help="legit recording is split into chunks of this many minutes")
    p.add_argument("--chunk-gap-s", type=float, default=7.0, help="drop windows this close after a chunk boundary")
    p.add_argument("--legit-ids", default="", help="comma list of legit mark ids to use (default: all non-excluded)")
    p.add_argument("--include-interrupted-legit", action="store_true")
    p.add_argument("--roles", default="1,2", help="window roles to keep (1 attacker, 2 defender, 3 bystander); '' = all")
    p.add_argument("--keep-hard-invalid", dest="hard_invalid", action="store_const", const="keep",
                   help="never apply the hard-validity filter (override)")
    p.add_argument("--drop-hard-invalid", dest="hard_invalid", action="store_const", const="drop",
                   help="always apply the hard-validity filter, even to files from the old recorder (override)")
    p.add_argument("--validity-fixed-from", default="",
                   help="UTC time (ISO, e.g. 2026-11-12T18:00:00Z, or epoch ms) the fixed recorder went live; "
                        "default = VALIDITY_FIXED_FROM_MS in this file")
    p.set_defaults(hard_invalid="auto")
    p.add_argument("--bulk-dir", default="", help="also sample legit windows from this bulk dump (e.g. E:/recordings); only the sampled files are decoded")
    p.add_argument("--bulk-processed", default="",
                   help="FAST: reuse windows already decoded by `grimml-train ingest` (e.g. E:/recordings_processed/ingest/windows); "
                        "no directory scan, no decoding. Hold-out accounts are excluded.")
    p.add_argument("--bulk-holdout", default="E:/recordings_processed/splits/live_holdout_accounts.txt",
                   help="accounts never used for training/eval here (live hold-out)")
    p.add_argument("--bulk-files", type=int, default=6000, help="how many random unmarked files to decode from --bulk-dir")
    p.add_argument("--bulk-per-player", type=int, default=30, help="max windows kept per player from the bulk sample")
    p.add_argument("--bulk-groups", type=int, default=8, help="bulk players are split into this many held-out groups")
    p.add_argument("--workers", type=int, default=0)
    p.add_argument("--seed", type=int, default=7)
    a = p.parse_args(argv)
    a.roles = [int(x) for x in a.roles.split(",") if x.strip()] if a.roles else []
    v = a.validity_fixed_from.strip()
    if not v:
        a.validity_fixed_from_ms = VALIDITY_FIXED_FROM_MS
    elif v.isdigit():
        a.validity_fixed_from_ms = int(v)
    else:
        a.validity_fixed_from_ms = int(pd.to_datetime(v, utc=True).timestamp() * 1000)
    return a


def main(argv=None) -> int:
    args = parse_args(argv)
    ids = id_table(args)
    folder = Path(args.folder)
    if args.list:
        list_mode(folder, ids)
        return 0
    print(BANNER)
    if not folder.exists() or not find_files(folder):
        print(f"No .gmlml files in '{folder}'. Put them there (subfolders ok) and rerun. `--list` shows the ids.")
        return 1
    t0 = time.time()
    raw, errs = decode_folder(folder, args.workers)
    print(f"decoded {len(raw)} windows ({len(errs)} unreadable files) in {time.time() - t0:.0f}s")
    if raw.empty:
        return 1
    notes: list[str] = []
    use_bulk = bool(args.bulk_dir or args.bulk_processed)
    if use_bulk:
        t1 = time.time()
        if args.bulk_processed:
            braw, bsrc = load_processed_bulk(Path(args.bulk_processed), args.bulk_holdout)
            print(f"bulk legit: loaded {len(braw)} already-processed windows from {bsrc} in {time.time() - t1:.0f}s", flush=True)
        else:
            bpaths = sample_bulk(Path(args.bulk_dir), args.bulk_files, args.seed, Path(args.out) / "bulk_listing.txt")
            print(f"bulk legit: decoding {len(bpaths)} sampled files ...", flush=True)
            braw, berrs = decode_folder(Path(args.bulk_dir), args.workers, bpaths)
            bsrc = f"{len(bpaths)} sampled files"
            print(f"bulk legit: decoded {len(braw)} windows ({len(berrs)} unreadable) in {time.time() - t1:.0f}s", flush=True)
        braw["mark_id"] = BULK_ID
        raw = pd.concat([raw, braw], ignore_index=True)
        ids = pd.concat([ids, pd.DataFrame([{
            "mark_id": BULK_ID, "label": LEGIT, "client": "", "module": "LEGIT", "settings": "",
            "start_ms": np.nan, "end_ms": np.nan, "interrupted": False, "multi_module": False,
            "note": "sampled bulk legit (operator-asserted, lowest label priority)", "secs": np.nan, "exclude_reason": "",
        }])], ignore_index=True)
    w = label_windows(raw, ids, args)
    if use_bulk:
        isb = w["mark_id"] == BULK_ID
        schemas = set(w.loc[(w["label"] == CHEAT) & ~isb, "schema_version"].dropna().unique())
        ok_schema = _num(w, "schema_version").isin(schemas)
        bad_fmt = isb & (w["reason"] == "") & ~ok_schema
        w.loc[bad_fmt, "reason"] = "bulk: older file format than the cheat files"
        w.loc[isb & (w["reason"] == "") & _num(w, "role").isna(), "reason"] = "bulk: role unknown"
        w["use"] = w["reason"] == ""
        # cap windows per player so a few busy accounts cannot dominate the legit class
        cand = w[isb & w["use"]].sample(frac=1.0, random_state=args.seed)
        over = cand.groupby("anon_player_id").cumcount() >= args.bulk_per_player
        w.loc[over[over].index, "reason"] = "bulk: per-player cap"
        w["use"] = w["reason"] == ""
        nb = w[isb & w["use"]]
        notes.append(
            f"bulk legit: {len(nb)} windows from {nb['anon_player_id'].nunique()} players from {bsrc} "
            f"(cap {args.bulk_per_player}/player, {args.bulk_groups} player-groups held out together). "
            "Bulk labels are operator-asserted, not verified. Bulk comes from other servers/recorder builds than the cheat test server, "
            "so look at the shortcut-only line: a high value means environment, not cheating."
        )
    if errs:
        notes.append(f"{len(errs)} files could not be decoded (first: {Path(errs[0][0]).name}: {errs[0][1][:80]})")

    n_trust = int(w["validity_trusted"].sum())
    notes.append(
        f"hard-validity filter applied to {n_trust}/{len(w)} windows; the rest come from the old recorder "
        "(sticky validity bits) or no fixed-build time is set, so their validity flags are ignored."
    )

    why = w["reason"].replace("", "used").value_counts()
    print("window usage:\n" + why.to_string())
    d, dropped_gap = assign_blocks(w[w["use"]], args.chunk_min, args.chunk_gap_s, args.bulk_groups)
    d = d[d["label"].isin([LEGIT, CHEAT])].copy()
    if "window_key" not in d.columns:
        d["window_key"] = d["path"]
    sets = feature_sets(d)
    legit = d[d["label"] == LEGIT]
    if legit.empty:
        print("No usable legit windows: drop the legit recording files (mark med3d6926) into the folder.")
        return 1
    if not (legit["has_epoch"].any()):
        notes.append("legit/cheat files have no wall-clock time (old GML1): edge trimming was skipped")

    wanted = None if args.modules.strip().lower() == "all" else {m.strip().lower() for m in args.modules.split(",") if m.strip()}
    cheat_all = d[(d["label"] == CHEAT) & (d["id_client"].str.lower() == args.train_client.lower())]
    modules = sorted(cheat_all["id_module"].dropna().unique())
    if wanted is not None:
        modules = [m for m in modules if m.lower() in wanted]
    other = d[(d["label"] == CHEAT) & (d["id_client"].str.lower() != args.train_client.lower())]

    results = []
    for m in modules:
        cheat = cheat_all[cheat_all["id_module"] == m]
        print(f"training {m}: {len(cheat)} cheat windows")
        r = run_module(m, cheat, legit, ids, sets, args.seed)
        o = other[other["id_module"] == m]
        if "skipped" not in r and not o.empty:
            r["transfer"] = transfer(cheat, legit, o, sets["core"], args.seed)
        results.append(r)
    if not modules:
        notes.append("no cheat windows for the chosen modules: check the file names contain the mark id")

    lacc = ids[(ids.label == LEGIT) & (ids.exclude_reason == "")]["mark_id"].nunique()
    notes.append("Cheat marks are all from one account and legit marks from other accounts, so the model may be learning the account/session, not the cheat. The shortcut-only line helps, but cannot rule this out.")
    notes.append(f"legit comes from {lacc} recording(s); chunk-level CIs are wide and only describe those chunks.")
    notes.append("Setting values come from your log; where marker and log disagreed the log was used (see the IDS table notes).")
    header = {
        "folder": str(folder), "windows_used": int(len(d)), "legit_windows": int(len(legit)),
        "cheat_windows": int((d['label'] == CHEAT).sum()), "chunk_gap_dropped": dropped_gap,
        "trim_s": args.trim_s, "chunk_min": args.chunk_min, "roles": args.roles or "all",
    }
    out = Path(args.out) / time.strftime("%Y%m%d_%H%M%S")
    text = write_report(out, header, results, notes)
    print("\n" + text)
    print(f"\nsaved to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
