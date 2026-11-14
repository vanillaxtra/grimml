package dev.grimml.capture;

import ac.grim.grimac.api.GrimUser;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

/**
 * Schema 4 block_place extra value g: what block was clicked (docs/schema4.md).
 * Read from Grim's per-player packet world on this player's netty thread, which is the thread Grim keeps that world on;
 * Bukkit's world must not be read here. Without Grim the value is 0 (unknown).
 */
final class ClickedBlock {

    static final int UNKNOWN = 0, ANCHOR = 1, OBSIDIAN = 2, BEDROCK = 3, GLOWSTONE = 4, OTHER = 5;

    private ClickedBlock() {
    }

    /** category + 256 * anchor charges, or 0. Never throws. */
    static int category(Object grimUser, int x, int y, int z) {
        if (grimUser == null) {
            return UNKNOWN;
        }
        try {
            int id = ((GrimUser) grimUser).getPacketWorld().getBlockStateId(x, y, z);
            WrappedBlockState state = WrappedBlockState.getByGlobalId(id);
            StateType t = state.getType();
            if (t == StateTypes.RESPAWN_ANCHOR) {
                return ANCHOR | (state.getCharges() << 8);
            }
            if (t == StateTypes.OBSIDIAN) {
                return OBSIDIAN;
            }
            if (t == StateTypes.BEDROCK) {
                return BEDROCK;
            }
            if (t == StateTypes.GLOWSTONE) {
                return GLOWSTONE;
            }
            return OTHER;
        } catch (Throwable t) {
            return UNKNOWN; // Grim missing, chunk not loaded, or a version mismatch: never break capture
        }
    }
}
