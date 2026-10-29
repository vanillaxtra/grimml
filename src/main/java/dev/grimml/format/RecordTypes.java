package dev.grimml.format;

// Wire-format type tags for .gmlml records. 
public final class RecordTypes {
    public static final byte ROTATION = 1;
    public static final byte ATTACK = 2;
    public static final byte STATE = 3;
    public static final byte GRIM_FLAG = 4;

    // STATE bitfield
    public static final int FLAG_SPRINT = 1;
    public static final int FLAG_SNEAK = 1 << 1;
    public static final int FLAG_VEHICLE = 1 << 2;
    public static final int FLAG_ELYTRA = 1 << 3;
    public static final int FLAG_BEDROCK = 1 << 4;

    private RecordTypes() {}
}
