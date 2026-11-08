ROTATION = 1
ATTACK = 2
STATE = 3
GRIM_FLAG = 4
FRAME = 5
DAMAGE = 6
SWING = 7
USE_ITEM = 8
HELD_SLOT = 9
CLICK = 10
BLOCK_PLACE = 11
KNOCKBACK = 12
TOTEM = 13
SHIELD = 14
MARKER = 15
CONSUMABLE = 16
TELEPORT = 17
SETBACK = 18
PROJECTILE = 19
WINDOW = 20
RTT = 21
BREAK = 22
BUCKET = 23
ELYTRA = 24
STATUS = 25
MARKER_KV = 26
ENCHANT = 27
COMBAT_DETAIL = 28
RETOTEM = 29
JUMP_RESET = 30

KNOWN = frozenset(range(1, 31))

DETAIL_MACE_SMASH = 1
DETAIL_SPEAR_CHARGE = 2
DETAIL_SPEAR_HIT = 3
DETAIL_WIND_CHARGE = 4
DETAIL_SPEAR_STAB = 5

PROJECTILE_LAUNCH = 1
PROJECTILE_HIT = 2

RETOTEM_UNKNOWN = 0
RETOTEM_CLICK = 1
RETOTEM_SWAP_KEY = 2
RETOTEM_FAILED = 3

JUMP_RESET_NONE = 127
JUMP_RESET_BEFORE = 5
JUMP_RESET_AFTER = 10

EVENT_NAMES = {
    ROTATION: "rotation",
    ATTACK: "attack",
    STATE: "state",
    GRIM_FLAG: "grim_flag",
    FRAME: "frame",
    DAMAGE: "damage",
    SWING: "swing",
    USE_ITEM: "use_item",
    HELD_SLOT: "hotbar_swap",
    CLICK: "inventory_click",
    BLOCK_PLACE: "block_place",
    KNOCKBACK: "knockback",
    TOTEM: "totem",
    SHIELD: "shield",
    MARKER: "marker",
    CONSUMABLE: "consumable",
    TELEPORT: "teleport",
    SETBACK: "setback",
    PROJECTILE: "projectile",
    WINDOW: "inventory",
    RTT: "rtt",
    BREAK: "break",
    BUCKET: "bucket",
    ELYTRA: "elytra",
    STATUS: "status",
    MARKER_KV: "marker_kv",
    ENCHANT: "enchant",
    COMBAT_DETAIL: "combat_detail",
    RETOTEM: "retotem",
    JUMP_RESET: "jump_reset",
}

F_ON_GROUND = 1
F_HORIZ_COLL = 1 << 1
F_INPUT_W = 1 << 2
F_INPUT_A = 1 << 3
F_INPUT_S = 1 << 4
F_INPUT_D = 1 << 5
F_JUMP = 1 << 6
F_SNEAK = 1 << 7
F_SPRINT = 1 << 8
F_INPUT_INFERRED = 1 << 9
F_CLIENT_TICK_END = 1 << 10
F_JUMP_EDGE = 1 << 11

FLAG_SPRINT = 1
FLAG_SNEAK = 1 << 1
FLAG_VEHICLE = 1 << 2
FLAG_ELYTRA = 1 << 3
FLAG_BEDROCK = 1 << 4
FLAG_STAFF = 1 << 5
FLAG_TEST_ACCOUNT = 1 << 6
MARKER_START = 1
MARKER_END = 0
MARKER_INTERRUPTED = 2

VALID_PARTIAL = 1
VALID_LAG = 1 << 1
VALID_DEATH = 1 << 2
VALID_RESPAWN = 1 << 3
VALID_TELEPORT = 1 << 4
VALID_SETBACK = 1 << 5
VALID_WORLD = 1 << 6
VALID_GAMEMODE = 1 << 7
VALID_VANISH = 1 << 8

VALID_FLAGS = (
    ("valid_partial", VALID_PARTIAL),
    ("valid_lag", VALID_LAG),
    ("valid_death", VALID_DEATH),
    ("valid_respawn", VALID_RESPAWN),
    ("valid_teleport", VALID_TELEPORT),
    ("valid_setback", VALID_SETBACK),
    ("valid_world", VALID_WORLD),
    ("valid_gamemode", VALID_GAMEMODE),
    ("valid_vanish", VALID_VANISH),
)
