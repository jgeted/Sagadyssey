package com.jgeted.sagadyssey.npc.appearance;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Mule;

/** Saddle calibration for the humanoid and the 1.21.11 adult equine meshes. */
public final class NpcRidingSeat {
    public static final Vec3 DEFAULT_ATTACHMENT = new Vec3(0, .6, 0);
    // LivingEntityRenderer translates by 1.501, and the humanoid hip is model y=12.
    public static final double HIP_HEIGHT = 1.501 - 12.0 / 16;
    // Unscaled EquineSaddleModel: body y=11 + box y=-8 - deformation .5.
    public static final double SADDLE_HEIGHT = 1.501 - 2.5 / 16;
    // Include the trouser/skirt volume around the hip, not just the bare leg pivot.
    // The extra model pixel keeps the fabric above the saddle rim without the old floating gap.
    public static final double HIP_CLEARANCE = .10 + 1.0 / 16;
    public static final double HORSE_MODEL_SCALE = 1.1;
    public static final double DONKEY_MODEL_SCALE = .87;
    public static final double MULE_MODEL_SCALE = .92;
    private NpcRidingSeat() {}

    public static double modelScale(AbstractHorse mount) {
        if (mount instanceof Horse) return HORSE_MODEL_SCALE;
        if (mount instanceof Donkey) return DONKEY_MODEL_SCALE;
        if (mount instanceof Mule) return MULE_MODEL_SCALE;
        return 1; // Skeleton and zombie horses use the unscaled saddle mesh.
    }

    public static double attachmentHeight(double mountSeatHeight, double mountModelScale, double npcScale) {
        return mountSeatHeight - SADDLE_HEIGHT * mountModelScale + (HIP_HEIGHT - HIP_CLEARANCE) * npcScale;
    }
}
