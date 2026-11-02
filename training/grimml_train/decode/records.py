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

KNOWN = frozenset(range(1, 27))

EVENT_NAMES = {
    ATTACK: "attack",
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
    MARKER_KV: "marker_kv",
    SETBACK: "setback",
    GRIM_FLAG: "grim_flag",
    WINDOW: "inventory",
    ROTATION: "rotation",
    STATE: "state",
}

F_ON_GROUND = 1
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
