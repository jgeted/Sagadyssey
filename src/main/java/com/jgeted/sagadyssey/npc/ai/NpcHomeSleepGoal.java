package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.life.NpcLifeBehavior;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcResidenceFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 原版村民式睡眠：休息窗口内只生成一条到床的连续路径，靠近并确认床可见后上床。
 * 起床和返工由排班及独立通勤目标处理，睡眠目标不再同时维护两套移动状态。
 */
public final class NpcHomeSleepGoal extends Goal {
    private static final int MIN_DEPARTURE_DELAY = 40;
    private static final int MAX_DEPARTURE_DELAY = 160;
    private static final long POST_DAMAGE_SAFETY_TICKS = 200L;
    private static final int MAX_TRAVEL_TICKS = 2400;
    private static final int FAILED_ROUTE_RETRY_TICKS = 100;
    private static final double SLEEP_DISTANCE = 2.0D;

    private final NpcBase npc;
    private NpcLifeRecord life;
    private BlockPos bed;
    private Path path;
    private int travelTicks;
    private boolean finished;
    private long earliestDepartureTick = -1L;
    private long nextRouteAttemptTick;
    private boolean awaitingSafety;

    public NpcHomeSleepGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive()
                || com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                || npc.isPassenger() || npc.getCommand() == NpcCommand.FOLLOW
                || (npc.tickCount - npc.getId()) % 20 != 0) return false;
        if (!com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsRest(npc)) {
            resetRestPreparation();
            return false;
        }

        life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
        if (life == null || life.bedPosition() == null
                || !life.dimension().equals(level.dimension().identifier().toString())
                || !NpcLifeBehavior.isRestWindow(life.shift(), level.getDayTime())) return false;
        bed = NpcResidenceFixtures.normalizeBedHead(level, life.bedPosition());
        if (!level.hasChunkAt(bed) || !level.getBlockState(bed).is(BlockTags.BEDS)) return false;
        if (npc.isSleeping()) return true;

        long now = level.getGameTime();
        if (earliestDepartureTick < 0L) {
            earliestDepartureTick = now + MIN_DEPARTURE_DELAY
                    + npc.getRandom().nextInt(MAX_DEPARTURE_DELAY - MIN_DEPARTURE_DELAY + 1);
            awaitingSafety = false;
        }
        if (npc.getTarget() != null) {
            postponeForDanger(now + POST_DAMAGE_SAFETY_TICKS,
                    "检测到战斗目标，等待连续安全 10 秒");
            return false;
        }
        if (npc.wasRecentlyDamaged(POST_DAMAGE_SAFETY_TICKS)) {
            postponeForDanger(npc.getLastDamageGameTime() + POST_DAMAGE_SAFETY_TICKS,
                    "最近受到伤害，等待连续安全 10 秒");
            return false;
        }
        if (now < earliestDepartureTick) {
            long remaining = earliestDepartureTick - now;
            npc.reportWorkActivity(awaitingSafety ? "脱战安全观察" : "等待休息出发", bed,
                    "剩余约 " + Math.max(1L, (remaining + 19L) / 20L) + " 秒；排班="
                            + life.shift().name());
            return false;
        }
        awaitingSafety = false;
        if (canEnterBed(level)) {
            path = null;
            return true;
        }
        if (now < nextRouteAttemptTick) return false;

        // 与原版 MoveToTargetSink 一致：只要求路径存在，不要求路径端点精确占据床方块。
        // 床有碰撞体，canReach() 很可能为 false，但路径仍会正确结束在床旁。
        path = npc.getNavigation().createPath(bed, 1);
        if (path == null) {
            nextRouteAttemptTick = now + FAILED_ROUTE_RETRY_TICKS;
            npc.reportWorkActivity("回家休息", bed, "没有到床附近的路径，5 秒后重试");
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(npc.level() instanceof ServerLevel level) || bed == null || life == null
                || finished || !npc.isAlive()
                || com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        long now = level.getGameTime();
        if (npc.getTarget() != null) {
            postponeForDanger(now + POST_DAMAGE_SAFETY_TICKS, "发现战斗目标，已中断休息");
            return false;
        }
        if (npc.wasRecentlyDamaged(POST_DAMAGE_SAFETY_TICKS)) {
            postponeForDanger(npc.getLastDamageGameTime() + POST_DAMAGE_SAFETY_TICKS,
                    "受到伤害，已中断休息");
            return false;
        }
        return NpcLifeBehavior.isRestWindow(life.shift(), level.getDayTime())
                && level.getBlockState(bed).is(BlockTags.BEDS)
                && (npc.isSleeping() || travelTicks < MAX_TRAVEL_TICKS);
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        if (npc.isSleeping()) return;
        if (npc.level() instanceof ServerLevel level && canEnterBed(level)) {
            beginSleeping();
            return;
        }
        if (path == null || !npc.getNavigation().moveTo(path, 0.9D)) {
            finished = true;
            nextRouteAttemptTick = npc.level().getGameTime() + FAILED_ROUTE_RETRY_TICKS;
            npc.reportWorkActivity("回家休息", bed, "无法开始到床附近的路径，5 秒后重试");
            return;
        }
        npc.reportWorkActivity("回家休息", bed,
                "沿连续路径前往床位；排班=" + life.shift().name());
    }

    @Override
    public void tick() {
        travelTicks++;
        if (npc.isSleeping() || finished || !(npc.level() instanceof ServerLevel level)) return;
        npc.getLookControl().setLookAt(bed.getX() + 0.5D, bed.getY() + 0.5D,
                bed.getZ() + 0.5D, 25.0F, npc.getMaxHeadXRot());
        if (canEnterBed(level)) {
            beginSleeping();
            return;
        }
        if (npc.getNavigation().isDone()) {
            finished = true;
            nextRouteAttemptTick = level.getGameTime() + FAILED_ROUTE_RETRY_TICKS;
            npc.reportWorkActivity("回家休息", bed,
                    "路径已结束但尚未看见床，停止移动并在 5 秒后重新寻路");
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        if (npc.isSleeping() && shouldWake()) npc.stopSleeping();
        path = null;
        bed = null;
        life = null;
        finished = false;
    }

    private boolean canEnterBed(ServerLevel level) {
        if (bed == null || !bed.closerToCenterThan(npc.position(), SLEEP_DISTANCE)) return false;
        return hasClearViewOfBed(level);
    }

    private boolean hasClearViewOfBed(ServerLevel level) {
        Vec3 from = npc.getEyePosition();
        Vec3 to = Vec3.atCenterOf(bed).add(0.0D, -0.15D, 0.0D);
        BlockHitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(bed)) return true;
        var state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        BlockPos otherHalf = bed.relative(state.getValue(BedBlock.FACING).getOpposite());
        return hit.getBlockPos().equals(otherHalf);
    }

    private void beginSleeping() {
        npc.getNavigation().stop();
        npc.startSleeping(bed);
        if (npc.isSleeping()) {
            npc.reportWorkActivity("回家休息", bed,
                    "已进入睡眠状态；排班=" + life.shift().name());
        } else {
            finished = true;
            npc.reportWorkActivity("回家休息", bed, "床位可见，但进入睡眠状态失败");
        }
    }

    private boolean shouldWake() {
        if (!(npc.level() instanceof ServerLevel level) || life == null || bed == null) return true;
        return !NpcLifeBehavior.isRestWindow(life.shift(), level.getDayTime())
                || npc.getTarget() != null
                || npc.wasRecentlyDamaged(POST_DAMAGE_SAFETY_TICKS)
                || !level.getBlockState(bed).is(BlockTags.BEDS);
    }

    private void postponeForDanger(long safeAfter, String detail) {
        earliestDepartureTick = Math.max(earliestDepartureTick, safeAfter);
        awaitingSafety = true;
        npc.reportWorkActivity("受到攻击，暂缓休息", bed, detail);
    }

    private void resetRestPreparation() {
        earliestDepartureTick = -1L;
        nextRouteAttemptTick = 0L;
        awaitingSafety = false;
    }
}
