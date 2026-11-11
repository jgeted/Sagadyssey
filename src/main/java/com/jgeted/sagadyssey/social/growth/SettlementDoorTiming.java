package com.jgeted.sagadyssey.social.growth;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Observe actual open transitions, including doors opened by a player. */
final class SettlementDoorTiming {
    static final int HOLD_TICKS = 40;

    private SettlementDoorTiming() { }

    static boolean mayClose(CompoundTag row, BlockPos door, boolean open, boolean crossing, long now) {
        String suffix = Long.toString(door.asLong());
        String observed = "ObservedDoorOpen:" + suffix;
        String openedAt = "ObservedDoorOpenedAt:" + suffix;
        if (!open && !crossing) {
            row.putBoolean(observed, false);
            return false;
        }
        if (crossing || !row.getBooleanOr(observed, false) || !row.contains(openedAt) || row.getLongOr(openedAt, 0L) > now) {
            row.putLong(openedAt, now);
        }
        row.putBoolean(observed, true);
        return open && !crossing && now - row.getLongOr(openedAt, 0L) >= HOLD_TICKS;
    }
}
