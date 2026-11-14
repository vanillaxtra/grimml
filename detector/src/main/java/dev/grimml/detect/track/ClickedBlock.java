package dev.grimml.detect.track;

import ac.grim.grimac.api.GrimUser;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

/** Same as the recorder's ClickedBlock: block_place extra g from Grim's per-player packet world (netty thread). */
final class ClickedBlock {

    private ClickedBlock() {
    }

    static int category(Object grimUser, int x, int y, int z) {
        if (grimUser == null) {
            return 0;
        }
        try {
            int id = ((GrimUser) grimUser).getPacketWorld().getBlockStateId(x, y, z);
            WrappedBlockState state = WrappedBlockState.getByGlobalId(id);
            StateType t = state.getType();
            if (t == StateTypes.RESPAWN_ANCHOR) return 1 | (state.getCharges() << 8);
            if (t == StateTypes.OBSIDIAN) return 2;
            if (t == StateTypes.BEDROCK) return 3;
            if (t == StateTypes.GLOWSTONE) return 4;
            return 5;
        } catch (Throwable t) {
            return 0;
        }
    }
}
