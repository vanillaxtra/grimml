from __future__ import annotations

from io import BytesIO

from grimml_train.decode import records as R
from grimml_train.decode.varint import (
    TruncatedFileError,
    read_f32,
    read_i32,
    read_i64,
    read_u16,
    read_u8,
    read_varint,
)


class UnknownRecordTypeError(ValueError):
    pass


def read_frames_v2(rec: BytesIO, window_key: str, schema_version: int) -> list[dict]:
    frame_count = read_u16(rec)
    if frame_count > 8192:
        raise TruncatedFileError(f"frame_count {frame_count} exceeds 8192")
    out: list[dict] = []
    ox = oy = oz = oyaw = opitch = 0
    for i in range(frame_count):
        rtype = read_u8(rec)
        if rtype != R.FRAME:
            raise UnknownRecordTypeError(f"expected FRAME(5) got {rtype}")
        tick = read_i32(rec)
        ns_off = read_i32(rec)
        order = read_u8(rec)
        tick_dur = read_u16(rec)
        if i == 0:
            x = read_i32(rec)
            y = read_i32(rec)
            z = read_i32(rec)
            yaw = read_i32(rec)
            pitch = read_i32(rec)
        else:
            x = ox + read_varint(rec)
            y = oy + read_varint(rec)
            z = oz + read_varint(rec)
            yaw = oyaw + read_varint(rec)
            pitch = opitch + read_varint(rec)
        ox, oy, oz, oyaw, opitch = x, y, z, yaw, pitch
        flags = read_i32(rec)
        pose = read_u8(rec)
        slot = read_u8(rec)
        held_cat = read_u8(rec)
        held_id = read_i32(rec)
        off_cat = read_u8(rec)
        off_id = read_i32(rec)
        _packets = read_u8(rec)
        ping = read_u16(rec)
        jitter = read_u16(rec)
        ack = read_i32(rec)
        ground_y = read_i32(rec)
        fall = read_i32(rec)
        veh_type = read_i32(rec)
        veh_id = read_i32(rec)
        out.append(
            {
                "window_key": window_key,
                "schema_version": schema_version,
                "tick": tick,
                "ns_offset": ns_off,
                "order_index": order,
                "tick_dur": tick_dur,
                "x_milli": x,
                "y_milli": y,
                "z_milli": z,
                "yaw_centi": yaw,
                "pitch_centi": pitch,
                "on_ground": bool(flags & R.F_ON_GROUND),
                "input_w": bool(flags & R.F_INPUT_W),
                "input_a": bool(flags & R.F_INPUT_A),
                "input_s": bool(flags & R.F_INPUT_S),
                "input_d": bool(flags & R.F_INPUT_D),
                "jump": bool(flags & R.F_JUMP),
                "sneak": bool(flags & R.F_SNEAK),
                "sprint": bool(flags & R.F_SPRINT),
                "flags": flags,
                "pose": pose,
                "held_slot": slot,
                "held_cat": held_cat,
                "held_id": held_id,
                "off_cat": off_cat,
                "off_id": off_id,
                "ping_ms": ping,
                "jitter_ms": jitter,
                "ack_tick": ack,
                "ground_y_milli": ground_y,
                "fall_milli": fall,
                "vehicle_type": veh_type,
                "vehicle_id": veh_id,
            }
        )
    return out


def read_events_v2(rec: BytesIO, window_key: str, schema_version: int) -> tuple[list[dict], list[dict]]:
    events: list[dict] = []
    markers: list[dict] = []
    event_count = read_u16(rec)
    if event_count > 8192:
        raise TruncatedFileError(f"event_count {event_count} exceeds 8192")
    for _ in range(event_count):
        etype = read_u8(rec)
        if etype not in R.KNOWN:
            raise UnknownRecordTypeError(f"unknown record type {etype}")
        tick = read_i32(rec)
        ns_off = read_i32(rec)
        order = read_u8(rec)
        nanos = read_i64(rec)
        ea = read_i64(rec)
        eb = read_i32(rec)
        ec = read_i32(rec)
        ed = read_i32(rec)
        ee = read_i32(rec)
        ef = read_i32(rec)
        eping = read_u16(rec)
        name = R.EVENT_NAMES.get(etype, f"type_{etype}")
        row = {
            "window_key": window_key,
            "schema_version": schema_version,
            "event_type": name,
            "record_type": etype,
            "tick": tick,
            "ns_offset": ns_off,
            "order_index": order,
            "nanos": nanos,
            "a": ea,
            "b": eb,
            "c": ec,
            "d": ed,
            "e": ee,
            "f": ef,
            "ping_ms": eping,
        }
        if etype == R.MARKER:
            action = eb & 0xFF
            source = (eb >> 8) & 0xFF
            kv_count = (eb >> 16) & 0xFF
            interrupted = action == R.MARKER_INTERRUPTED
            if interrupted:
                action = R.MARKER_END
            markers.append(
                {
                    "window_key": window_key,
                    "schema_version": schema_version,
                    "kind": "marker",
                    "tick": tick,
                    "nanos": nanos,
                    "wall_ms": ea,
                    "action": action,
                    "source": source,
                    "interrupted": interrupted,
                    "label_id": ec,
                    "client_id": ed,
                    "module_id": ee,
                    "settings_hash": ef,
                    "kv_count": kv_count,
                    "key_hash": None,
                    "val_hash": None,
                }
            )
            row["marker_action"] = action
        elif etype == R.MARKER_KV:
            markers.append(
                {
                    "window_key": window_key,
                    "schema_version": schema_version,
                    "kind": "kv",
                    "tick": tick,
                    "nanos": nanos,
                    "wall_ms": None,
                    "action": None,
                    "source": None,
                    "interrupted": None,
                    "label_id": None,
                    "client_id": None,
                    "module_id": None,
                    "settings_hash": None,
                    "kv_count": None,
                    "key_hash": int(ea),
                    "val_hash": eb,
                }
            )
        events.append(row)
    return events, markers


def read_gml1_body(rec: BytesIO, window_key: str) -> tuple[list[dict], list[dict], list[dict]]:
    frames: list[dict] = []
    events: list[dict] = []
    markers: list[dict] = []

    while rec.tell() < len(rec.getbuffer()):
        start = rec.tell()
        try:
            etype = read_u8(rec)
        except EOFError:
            break
        if etype != R.ROTATION:
            rec.seek(start)
            break
        tick = read_i32(rec)
        yaw = read_f32(rec)
        pitch = read_f32(rec)
        nanos = read_i64(rec)
        frames.append(
            {
                "window_key": window_key,
                "schema_version": 1,
                "tick": tick,
                "ns_offset": None,
                "order_index": None,
                "tick_dur": None,
                "x_milli": None,
                "y_milli": None,
                "z_milli": None,
                "yaw_centi": int(round(yaw * 100)),
                "pitch_centi": int(round(pitch * 100)),
                "on_ground": None,
                "input_w": None,
                "input_a": None,
                "input_s": None,
                "input_d": None,
                "jump": None,
                "sneak": None,
                "sprint": None,
                "flags": None,
                "pose": None,
                "held_slot": None,
                "held_cat": None,
                "held_id": None,
                "off_cat": None,
                "off_id": None,
                "ping_ms": None,
                "jitter_ms": None,
                "ack_tick": None,
                "ground_y_milli": None,
                "fall_milli": None,
                "vehicle_type": None,
                "vehicle_id": None,
            }
        )
        _ = nanos

    while rec.tell() < len(rec.getbuffer()):
        pos = rec.tell()
        try:
            etype = read_u8(rec)
        except EOFError:
            break
        tick = read_i32(rec)
        nanos = read_i64(rec)
        if etype == R.ATTACK:
            target = read_i32(rec)
            hit = read_i32(rec)
            ping = read_u16(rec)
            events.append(_ev(window_key, 1, etype, "attack", tick, nanos, target, hit, None, None, None, None, ping))
        elif etype == R.STATE:
            flags = read_u16(rec)
            ping = read_u16(rec)
            events.append(_ev(window_key, 1, etype, "state", tick, nanos, flags, None, None, None, None, None, ping))
        elif etype == R.GRIM_FLAG:
            check_hash = read_i32(rec)
            ping = read_u16(rec)
            events.append(_ev(window_key, 1, etype, "grim_flag", tick, nanos, check_hash, None, None, None, None, None, ping))
        else:
            raise UnknownRecordTypeError(f"gml1 unknown type {etype} at offset {pos}")
    return frames, events, markers


def _ev(wk, sv, rt, name, tick, nanos, a, b, c, d, e, f, ping):
    return {
        "window_key": wk,
        "schema_version": sv,
        "event_type": name,
        "record_type": rt,
        "tick": tick,
        "ns_offset": None,
        "order_index": None,
        "nanos": nanos,
        "a": a,
        "b": b,
        "c": c,
        "d": d,
        "e": e,
        "f": f,
        "ping_ms": ping,
    }
