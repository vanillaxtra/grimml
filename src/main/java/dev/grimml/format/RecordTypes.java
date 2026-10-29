package dev.grimml.format;

public final class RecordTypes {
    public static final byte ROTATION = 1;
    public static final byte ATTACK = 2;
    public static final byte STATE = 3;
    public static final byte GRIM_FLAG = 4;
    public static final byte FRAME = 5;
    public static final byte DAMAGE = 6;
    public static final byte SWING = 7;
    public static final byte USE_ITEM = 8;
    public static final byte HELD_SLOT = 9;
    public static final byte CLICK = 10;
    public static final byte BLOCK_PLACE = 11;
    public static final byte KNOCKBACK = 12;
    public static final byte TOTEM = 13;
    public static final byte SHIELD = 14;
    public static final byte MARKER = 15;
    public static final byte CONSUMABLE = 16;
    public static final byte TELEPORT = 17;
    public static final byte SETBACK = 18;
    public static final byte PROJECTILE = 19;
    public static final byte WINDOW = 20;
    public static final byte RTT = 21;
    public static final byte BREAK = 22;
    public static final byte BUCKET = 23;
    public static final byte ELYTRA = 24;
    public static final byte STATUS = 25;

    public static final int TYPE_COUNT = 26;

    public static final int FLAG_SPRINT = 1;
    public static final int FLAG_SNEAK = 1 << 1;
    public static final int FLAG_VEHICLE = 1 << 2;
    public static final int FLAG_ELYTRA = 1 << 3;
    public static final int FLAG_BEDROCK = 1 << 4;
    public static final int FLAG_STAFF = 1 << 5;
    public static final int FLAG_TEST_ACCOUNT = 1 << 6;
    public static final int FLAG_VANISHED = 1 << 7;
    public static final int FLAG_CREATIVE_FLIGHT = 1 << 8;
    public static final int FLAG_MAIN_HAND_LEFT = 1 << 9;
    public static final int FLAG_BLOCKING = 1 << 10;
    public static final int FLAG_USING_MAIN = 1 << 11;
    public static final int FLAG_USING_OFF = 1 << 12;
    public static final int FLAG_IN_WATER = 1 << 13;
    public static final int FLAG_IN_LAVA = 1 << 14;
    public static final int FLAG_IN_WEB = 1 << 15;
    public static final int FLAG_CLIMBING = 1 << 16;

    public static final int VALID_PARTIAL = 1;
    public static final int VALID_LAG = 1 << 1;
    public static final int VALID_DEATH = 1 << 2;
    public static final int VALID_RESPAWN = 1 << 3;
    public static final int VALID_TELEPORT = 1 << 4;
    public static final int VALID_SETBACK = 1 << 5;
    public static final int VALID_WORLD = 1 << 6;
    public static final int VALID_GAMEMODE = 1 << 7;
    public static final int VALID_VANISH = 1 << 8;

    public static final byte ROLE_ATTACKER = 1;
    public static final byte ROLE_DEFENDER = 2;
    public static final byte ROLE_BYSTANDER = 3;

    public static final byte TRIGGER_ATTACK = 1;
    public static final byte TRIGGER_DAMAGE = 2;
    public static final byte TRIGGER_FLAG = 3;
    public static final byte TRIGGER_SWAP = 4;
    public static final byte TRIGGER_TOTEM = 5;
    public static final byte TRIGGER_KNOCKBACK_JUMP = 6;
    public static final byte TRIGGER_CLICK = 7;
    public static final byte TRIGGER_SHIELD = 8;
    public static final byte TRIGGER_MACE = 9;
    public static final byte TRIGGER_SPEAR = 10;
    public static final byte TRIGGER_PLACE = 11;
    public static final byte TRIGGER_WIND = 12;
    public static final byte TRIGGER_MARKER = 13;

    public static final byte LABEL_UNLABELED = 0;
    public static final byte LABEL_STAFF_MARKER = 1;
    public static final byte LABEL_BAN_BACKFILL = 2;
    public static final byte LABEL_REPLAY_CONFIRMED = 3;

    private RecordTypes() {}
}
