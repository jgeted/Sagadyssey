package com.jgeted.sagadyssey.npc.appearance;

import net.minecraft.util.Mth;

/** Visual angles only; never changes server aim, movement, or combat. */
public final class NpcRidingAngles {
    private NpcRidingAngles() {}

    public static float bodyYaw(float previousMountYaw, float mountYaw, float partialTick) {
        return Mth.rotLerp(partialTick, previousMountYaw, mountYaw);
    }

    public static float headYaw(float previousHeadYaw, float headYaw, float mountBodyYaw, float partialTick) {
        return Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partialTick, previousHeadYaw, headYaw) - mountBodyYaw), -65, 65);
    }
}
