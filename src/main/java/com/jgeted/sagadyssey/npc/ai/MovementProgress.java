package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** 检测 NPC/坐骑是否在持续尝试移动却没有产生有效进展。 */
public final class MovementProgress {
    private Vec3 lastPosition;
    private double lastDistanceSq = Double.MAX_VALUE;
    private int stalledTicks;

    public void reset(NpcBase npc, BlockPos destination) {
        Entity mover = mover(npc);
        lastPosition = mover.position();
        lastDistanceSq = mover.distanceToSqr(Vec3.atBottomCenterOf(destination));
        stalledTicks = 0;
    }

    /** @return 连续约三秒没有位移或距离改善。 */
    public boolean tick(NpcBase npc, BlockPos destination) {
        Entity mover = mover(npc);
        Vec3 current = mover.position();
        double distanceSq = mover.distanceToSqr(Vec3.atBottomCenterOf(destination));
        boolean moved = lastPosition == null || current.distanceToSqr(lastPosition) > 0.01D;
        boolean improved = lastDistanceSq - distanceSq > 0.05D;
        stalledTicks = moved || improved ? 0 : stalledTicks + 1;
        lastPosition = current;
        lastDistanceSq = distanceSq;
        return stalledTicks >= 60;
    }

    public int stalledTicks() { return stalledTicks; }

    private static Entity mover(NpcBase npc) {
        return npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
    }
}
