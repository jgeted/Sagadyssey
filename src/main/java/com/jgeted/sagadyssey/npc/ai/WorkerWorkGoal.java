package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工人工作 AI：在 WORK 命令下自动砍树、挖矿。
 * 优先级：先砍树（原木），再清树叶找树干，再挖露天石头/矿石，最后挖开覆盖层找矿。
 * 找不到工作时随机游荡探索，形成「走 → 看 → 干活」循环。
 */
public class WorkerWorkGoal extends Goal {

    private static final int SEARCH_RADIUS = 20;
    private static final double WORK_REACH_SQ = 20.25; // 以眼睛为起点，4.5 格工具触及距离
    private static final double STAND_REACHED_SQ = 2.25;
    private static final long UNREACHABLE_COOLDOWN_TICKS = 600L;
    private static final int MAX_TREE_HEIGHT = 20; // 整列砍伐的最大树干高度
    private static final int MAX_LEAF_CLEAR = 24;  // 一次最多清多少片树叶
    private static final int MAX_SCAFFOLD_HEIGHT = 3;

    private final NpcBase npc;
    private BlockPos workPos;
    private BlockPos workStandPos;
    private BlockPos workApproachPos;
    private List<BlockPos> workScaffoldBlocks = List.of();
    private Block placedScaffoldBlock;
    private boolean scaffoldBuilt;
    private boolean scaffoldNeedsRecovery;
    private int actionCooldown;
    private int stuckTicks;
    private int nextWorkScanTick;
    private BlockPos explorePos; // 找不到工作时随机游荡的目标
    private final Map<BlockPos, Long> unreachableUntil = new HashMap<>();

    /** 方块目标和 NPC 实际应该走到的作业站位必须分开。 */
    private record WorkTarget(BlockPos blockPos, BlockPos standPos,
                              List<BlockPos> scaffoldBlocks, BlockPos approachPos) {
        private WorkTarget(BlockPos blockPos, BlockPos standPos) {
            this(blockPos, standPos, List.of(), null);
        }

        private boolean needsScaffold() { return !scaffoldBlocks.isEmpty(); }
    }

    private record ScaffoldPlan(List<BlockPos> blocks, BlockPos approach) { }

    public WorkerWorkGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.level().isClientSide) return false;
        if (npc.getProfession() != NpcProfession.WORKER) return false;
        if (npc.getCommand() != NpcCommand.WORK) return false;
        if (!com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        if (npc.getTarget() != null || npc.isPassenger()) return false;
        if (!canStoreLikelyOutput()) return false;
        // GoalSelector 并不会逐 tick 调用每个 canUse。使用绝对取模会在多个同优先级
        // 目标切换后反复错过唯一相位；截止时间节流保证下一次被轮询时一定会扫描。
        if (npc.tickCount < nextWorkScanTick) return false;
        nextWorkScanTick = npc.tickCount + 20;

        clearExpiredUnreachableTargets();
        WorkTarget found = findWork();
        if (found != null) {
            if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.WORK,
                    50.0D, found.needsScaffold() ? 10.0D : 4.0D, false)) return false;
            applyWorkTarget(found);
            this.explorePos = null;
            return true;
        }

        if (npc.hasWorkZone()) {
            npc.reportWorkActivity("工人等待", null,
                    "指定工作区内已无可采集的木材、石料或矿物；需要更换工作区或补充测试资源");
            this.workPos = null;
            this.explorePos = null;
            return false;
        }

        // 找不到工作：随机游荡探索，走出去找活干。
        this.workPos = null;
        this.explorePos = randomExplorePos();
        return this.explorePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (npc.getTarget() != null || npc.getCommand() != NpcCommand.WORK || npc.isPassenger()) {
            return false;
        }
        if (!com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        return workPos != null || explorePos != null;
    }

    @Override
    public void start() {
        actionCooldown = 0;
        stuckTicks = 0;
        scaffoldBuilt = false;
        scaffoldNeedsRecovery = false;
        placedScaffoldBlock = null;
        if (workPos != null) {
            moveToNextWorkPosition();
            if (workScaffoldBlocks.isEmpty()) {
                npc.reportWorkActivity("工人采集", workPos,
                        "站位=" + (workStandPos == null ? "无" : workStandPos.toShortString()));
            } else {
                npc.reportWorkActivity("工人搭设施工架", workPos,
                        "高度=" + scaffoldHeight() + "，材料=" + workScaffoldBlocks.size()
                                + "块，入口=" + workApproachPos.toShortString());
            }
        } else if (explorePos != null) {
            npc.getNavigation().moveTo(explorePos.getX() + 0.5, explorePos.getY(), explorePos.getZ() + 0.5, 1.0);
            npc.reportWorkActivity("工人寻找工作", explorePos, "当前范围没有可采集目标");
        }
    }

    @Override
    public void stop() {
        recoverScaffold();
        workPos = null;
        workStandPos = null;
        workApproachPos = null;
        workScaffoldBlocks = List.of();
        placedScaffoldBlock = null;
        scaffoldBuilt = false;
        scaffoldNeedsRecovery = false;
        explorePos = null;
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        if (workPos != null) {
            tickWork();
        } else if (explorePos != null) {
            tickExplore();
        }
    }

    /** 走向工作点并作业 */
    private void tickWork() {
        if (scaffoldNeedsRecovery) {
            tickRecoverScaffoldSafely();
            return;
        }
        if (!workScaffoldBlocks.isEmpty() && !scaffoldBuilt) {
            tickBuildScaffold();
            return;
        }

        npc.getLookControl().setLookAt(workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5);
        double standDistSq = npc.distanceToSqr(
                workStandPos.getX() + 0.5, workStandPos.getY(), workStandPos.getZ() + 0.5);
        double reachDistSq = npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(workPos));
        if (standDistSq <= STAND_REACHED_SQ && reachDistSq <= WORK_REACH_SQ) {
            npc.getNavigation().stop();
            if (--actionCooldown <= 0) {
                actionCooldown = 20;
                mineBlock();
                if (scaffoldBuilt && workApproachPos != null) {
                    // 先从垫脚台走回地面，再拆除；避免把工人脚下方块瞬间抽走。
                    scaffoldNeedsRecovery = true;
                    stuckTicks = 0;
                    npc.reportWorkActivity("工人撤离施工架", workPos,
                            "采集完成，返回入口后回收 " + workScaffoldBlocks.size() + " 块材料");
                    npc.getNavigation().moveTo(workApproachPos.getX() + 0.5D,
                            workApproachPos.getY(), workApproachPos.getZ() + 0.5D, 0.85D);
                } else {
                    workPos = null;
                }
            }
        } else if (npc.getNavigation().isDone()) {
            if (++stuckTicks > 10) {
                // 卡住：目标是原木却被树叶挡路 → 改去清掉离工人最近、挨着树干的树叶，清出路
                if (npc.level().getBlockState(workPos).is(BlockTags.LOGS)) {
                    BlockPos leaf = findLeafNearLog(workPos);
                    if (leaf != null) {
                        WorkTarget leafTarget = createWorkTarget(leaf);
                        if (leafTarget != null) {
                            recoverScaffold();
                            applyWorkTarget(leafTarget);
                            stuckTicks = 0;
                            moveToNextWorkPosition();
                            return;
                        }
                    }
                }
                markTemporarilyUnreachable(workPos);
                recoverScaffold();
                workPos = null;
                workStandPos = null;
            } else {
                moveToWorkStand();
            }
        }
    }

    private void applyWorkTarget(WorkTarget target) {
        this.workPos = target.blockPos();
        this.workStandPos = target.standPos();
        this.workScaffoldBlocks = target.scaffoldBlocks();
        this.workApproachPos = target.approachPos();
        this.scaffoldBuilt = false;
        this.scaffoldNeedsRecovery = false;
        this.placedScaffoldBlock = null;
    }

    private void moveToNextWorkPosition() {
        if (!workScaffoldBlocks.isEmpty() && !scaffoldBuilt && workApproachPos != null) {
            npc.getNavigation().moveTo(
                    workApproachPos.getX() + 0.5, workApproachPos.getY(), workApproachPos.getZ() + 0.5, 1.0);
        } else {
            moveToWorkStand();
        }
    }

    /** 先站到施工架入口旁边放置材料，再沿阶梯走上作业站位。 */
    private void tickBuildScaffold() {
        BlockPos focus = workScaffoldBlocks.getFirst();
        npc.getLookControl().setLookAt(
                focus.getX() + 0.5, focus.getY() + 0.5, focus.getZ() + 0.5);
        double approachDistSq = npc.distanceToSqr(
                workApproachPos.getX() + 0.5, workApproachPos.getY(), workApproachPos.getZ() + 0.5);
        if (approachDistSq <= STAND_REACHED_SQ) {
            npc.getNavigation().stop();
            if (!placeScaffold()) {
                markTemporarilyUnreachable(workPos);
                workPos = null;
                workStandPos = null;
                return;
            }
            stuckTicks = 0;
            npc.swing(InteractionHand.MAIN_HAND);
            npc.reportWorkActivity("工人攀登施工架", workPos,
                    "施工架已完成，高度=" + scaffoldHeight() + "，前往作业站位="
                            + workStandPos.toShortString());
            moveToWorkStand();
        } else if (npc.getNavigation().isDone()) {
            if (++stuckTicks > 10) {
                markTemporarilyUnreachable(workPos);
                workPos = null;
                workStandPos = null;
            } else {
                moveToNextWorkPosition();
            }
        }
    }

    /** 作业完成后先回到台侧安全站位，再回收临时方块。 */
    private void tickRecoverScaffoldSafely() {
        if (workApproachPos == null || !scaffoldBuilt) {
            recoverScaffold();
            workPos = null;
            return;
        }
        BlockPos focus = workScaffoldBlocks.isEmpty() ? workStandPos : workScaffoldBlocks.getLast();
        npc.getLookControl().setLookAt(focus.getX() + 0.5D,
                focus.getY() + 0.5D, focus.getZ() + 0.5D);
        double distanceSq = npc.distanceToSqr(workApproachPos.getX() + 0.5D,
                workApproachPos.getY(), workApproachPos.getZ() + 0.5D);
        if (distanceSq <= STAND_REACHED_SQ) {
            npc.getNavigation().stop();
            npc.playAction(NpcAction.PLACE, 10);
            npc.swing(InteractionHand.MAIN_HAND);
            recoverScaffold();
            npc.reportWorkActivity("工人已回收施工架", workPos,
                    "已安全返回地面并回收全部临时材料");
            workPos = null;
            workStandPos = null;
            return;
        }
        if (npc.getNavigation().isDone() && npc.tickCount % 10 == 0) {
            npc.getNavigation().moveTo(workApproachPos.getX() + 0.5D,
                    workApproachPos.getY(), workApproachPos.getZ() + 0.5D, 0.85D);
            if (++stuckTicks > 12) {
                // 极端卡路时仍清理临时方块，避免在世界里留下施工垃圾。
                recoverScaffold();
                workPos = null;
                workStandPos = null;
            }
        }
    }

    private void moveToWorkStand() {
        if (workStandPos != null) {
            npc.getNavigation().moveTo(
                    workStandPos.getX() + 0.5, workStandPos.getY(), workStandPos.getZ() + 0.5, 1.0);
        }
    }

    private int scaffoldHeight() {
        return workStandPos == null || workApproachPos == null ? 0
                : Math.max(0, workStandPos.getY() - workApproachPos.getY());
    }

    /** 无工作可做时游荡探索 */
    private void tickExplore() {
        if (npc.getNavigation().isDone()
                || npc.distanceToSqr(explorePos.getX() + 0.5, explorePos.getY(), explorePos.getZ() + 0.5) <= 4.0) {
            explorePos = null;
        }
    }

    /** 扫描附近可挖的方块：先砍树（原木），再清树叶找树干，再挖露天石头/矿，最后挖开覆盖层找矿 */
    private WorkTarget findWork() {
        BlockPos center = npc.blockPosition();
        BlockPos fallback = null;
        BlockPos leafFallback = null;
        BlockPos digTarget = null;
        double bestMineDist = Double.MAX_VALUE;
        double bestDigDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                // 向上扩到 +10；普通站位够不到时允许搭最多三层实体阶梯。
                for (int dy = -2; dy <= 10; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!npc.isWorkAllowedAt(p)) continue;
                    if (isTemporarilyUnreachable(p)) continue;
                    BlockState state = npc.level().getBlockState(p);
                    if (state.is(BlockTags.LOGS)) {
                        WorkTarget logTarget = createWorkTarget(p);
                        if (logTarget != null) return logTarget; // 优先砍树
                    }
                    if (isMineTarget(state)) {
                        double d = center.distSqr(p);
                        if (d < bestMineDist) {
                            bestMineDist = d;
                            fallback = p;
                        }
                    }
                    if (leafFallback == null && state.is(BlockTags.LEAVES) && isLeafNearLog(p)) {
                        leafFallback = p;
                    }
                }
                // 挖矿兜底：找被土/草/砂砾盖住、下方有矿的地表，就近挖开
                BlockPos surface = findSurface(center.getX() + dx, center.getZ() + dz, center.getY() + 3);
                if (surface != null && npc.isWorkAllowedAt(surface)
                        && isCover(npc.level().getBlockState(surface)) && stoneBelow(surface)) {
                    double d = center.distSqr(surface);
                    if (d < bestDigDist) {
                        bestDigDist = d;
                        digTarget = surface;
                    }
                }
            }
        }
        WorkTarget target = createWorkTarget(leafFallback);
        if (target != null) return target;
        target = createWorkTarget(fallback);
        if (target != null) return target;
        return createWorkTarget(digTarget);
    }

    /**
     * 为目标寻找真正可站立、可触及的作业位置。高处目标会优先使用附近已有的平台，
     * 而不是要求寻路器走进实体方块中心。
     */
    private WorkTarget createWorkTarget(BlockPos target) {
        if (target == null || isTemporarilyUnreachable(target)) return null;

        BlockPos current = npc.blockPosition();
        if (isValidStandPosition(current, target)) {
            return new WorkTarget(target.immutable(), current.immutable());
        }

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!isValidStandPosition(stand, target)) continue;
                    // createPath(null) 代表原版寻路器无法到达该站位。
                    if (npc.getNavigation().createPath(stand, 0) == null) continue;
                    double dist = current.distSqr(stand);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = stand;
                    }
                }
            }
        }

        if (best != null) return new WorkTarget(target.immutable(), best.immutable());

        WorkTarget scaffoldTarget = createScaffoldTarget(target);
        if (scaffoldTarget != null) return scaffoldTarget;

        markTemporarilyUnreachable(target);
        return null;
    }

    private boolean isValidStandPosition(BlockPos stand, BlockPos target) {
        return isWalkableStandPosition(stand) && canReachTargetFromStand(stand, target);
    }

    private boolean isWalkableStandPosition(BlockPos stand) {
        if (!npc.level().isLoaded(stand)) return false;
        BlockState feet = npc.level().getBlockState(stand);
        BlockState head = npc.level().getBlockState(stand.above());
        BlockPos floorPos = stand.below();
        BlockState floor = npc.level().getBlockState(floorPos);
        if (!feet.getCollisionShape(npc.level(), stand).isEmpty()) return false;
        if (!head.getCollisionShape(npc.level(), stand.above()).isEmpty()) return false;
        return floor.isFaceSturdy(npc.level(), floorPos, Direction.UP);
    }

    private boolean canReachTargetFromStand(BlockPos stand, BlockPos target) {
        if (!npc.level().isLoaded(target)) return false;
        Vec3 standEye = new Vec3(
                stand.getX() + 0.5,
                stand.getY() + npc.getEyeHeight(),
                stand.getZ() + 0.5);
        Vec3 targetCenter = Vec3.atCenterOf(target);
        if (standEye.distanceToSqr(targetCenter) > WORK_REACH_SQ) return false;

        BlockHitResult hit = npc.level().clip(new ClipContext(
                standEye, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    /**
     * 为高处目标规划最多三层的实体阶梯。每一层由完整立柱支撑，
     * NPC 可以按普通一格台阶寻路上下，不依赖传送或悬空方块。
     */
    private WorkTarget createScaffoldTarget(BlockPos target) {
        BlockState targetState = npc.level().getBlockState(target);
        if (!(isMineTarget(targetState) || targetState.is(BlockTags.LOGS)
                || targetState.is(BlockTags.LEAVES)) || findScaffoldSlot(1) < 0) return null;

        BlockPos current = npc.blockPosition();
        WorkTarget best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -6; dy <= -3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!canReachTargetFromStand(stand, target)) continue;
                    if (!npc.level().getBlockState(stand).isAir()
                            || !npc.level().getBlockState(stand.above()).isAir()) continue;
                    for (int height = 1; height <= MAX_SCAFFOLD_HEIGHT; height++) {
                        int required = height * (height + 1) / 2;
                        if (findScaffoldSlot(required) < 0) continue;
                        BlockPos groundFeet = stand.below(height);
                        if (!isWalkableStandPosition(groundFeet)) continue;
                        for (Direction direction : Direction.Plane.HORIZONTAL) {
                            ScaffoldPlan plan = createStairPlan(stand, groundFeet, height, direction);
                            if (plan == null) continue;
                            // 优先用更少材料，其次才比较入口离 NPC 的距离。
                            double score = required * 1000.0D + current.distSqr(plan.approach());
                            if (score < bestScore) {
                                bestScore = score;
                                best = new WorkTarget(target.immutable(), stand.immutable(),
                                        plan.blocks(), plan.approach().immutable());
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private ScaffoldPlan createStairPlan(BlockPos stand, BlockPos groundFeet,
                                         int height, Direction outward) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int step = 1; step <= height; step++) {
            int outwardDistance = height - step;
            BlockPos columnBase = new BlockPos(
                    stand.getX() + outward.getStepX() * outwardDistance,
                    groundFeet.getY(),
                    stand.getZ() + outward.getStepZ() * outwardDistance);
            BlockPos floor = columnBase.below();
            if (!npc.level().isLoaded(floor)
                    || !npc.level().getBlockState(floor).isFaceSturdy(npc.level(), floor, Direction.UP)) {
                return null;
            }
            for (int layer = 0; layer < step; layer++) {
                BlockPos block = columnBase.above(layer);
                if (!npc.isWorkAllowedAt(block) || !npc.level().isLoaded(block)
                        || !npc.level().getBlockState(block).isAir()) return null;
                blocks.add(block.immutable());
            }
        }

        BlockPos approach = new BlockPos(
                stand.getX() + outward.getStepX() * height,
                groundFeet.getY(),
                stand.getZ() + outward.getStepZ() * height);
        if (!isWalkableStandPosition(approach)) return null;
        Path path = npc.getNavigation().createPath(approach, 0);
        if (!approach.equals(npc.blockPosition()) && (path == null || !path.canReach())) return null;
        return new ScaffoldPlan(List.copyOf(blocks), approach.immutable());
    }

    private int findScaffoldSlot(int required) {
        var inventory = npc.getEquipmentInventory();
        int bestSlot = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getCount() < required || scaffoldBlockFor(stack) == null) continue;
            int priority = scaffoldMaterialPriority(stack);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** 优先用普通土石；允许其他完整实心方块，但拒绝沙砾、容器和危险/非整块材料。 */
    private Block scaffoldBlockFor(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        if (block instanceof FallingBlock || block == Blocks.TNT || state.hasBlockEntity()) return null;
        if (!state.getFluidState().isEmpty()
                || !state.isCollisionShapeFullBlock(npc.level(), npc.blockPosition())) return null;
        return block;
    }

    private static int scaffoldMaterialPriority(ItemStack stack) {
        if (stack.is(Items.DIRT) || stack.is(Items.COARSE_DIRT)
                || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)) return 0;
        if (stack.is(Items.STONE) || stack.is(Items.ANDESITE)
                || stack.is(Items.DIORITE) || stack.is(Items.GRANITE)) return 1;
        return 2;
    }

    private boolean placeScaffold() {
        if (workScaffoldBlocks.isEmpty()) return false;
        for (BlockPos position : workScaffoldBlocks) {
            if (!npc.level().getBlockState(position).isAir()) return false;
        }
        int slot = findScaffoldSlot(workScaffoldBlocks.size());
        if (slot < 0) return false;
        ItemStack stack = npc.getEquipmentInventory().getItem(slot);
        Block block = scaffoldBlockFor(stack);
        if (block == null) return false;
        placedScaffoldBlock = block;
        scaffoldBuilt = true;
        for (BlockPos position : workScaffoldBlocks) {
            if (!npc.level().setBlock(position, block.defaultBlockState(), Block.UPDATE_ALL)) {
                recoverScaffold();
                return false;
            }
            stack.shrink(1);
        }
        npc.playAction(NpcAction.PLACE, 12);
        npc.playSound(block.defaultBlockState().getSoundType().getPlaceSound(), 0.8F, 0.9F);

        // 搭完后再次询问真实寻路；若阶梯仍不可达，立即原样回收，不浪费材料。
        Path path = workStandPos == null ? null : npc.getNavigation().createPath(workStandPos, 0);
        if (path == null || !path.canReach()) {
            recoverScaffold();
            return false;
        }
        return true;
    }

    private void recoverScaffold() {
        if (!scaffoldBuilt || workScaffoldBlocks.isEmpty() || placedScaffoldBlock == null) {
            scaffoldNeedsRecovery = false;
            return;
        }
        // 从最高、最内侧的方块开始拆，避免先破坏仍承载 NPC 的低层路径。
        for (int index = workScaffoldBlocks.size() - 1; index >= 0; index--) {
            BlockPos position = workScaffoldBlocks.get(index);
            BlockState state = npc.level().getBlockState(position);
            if (!state.is(placedScaffoldBlock)) continue;
            npc.level().destroyBlock(position, false, npc);
            ItemStack recovered = new ItemStack(placedScaffoldBlock);
            if (!npc.addToBag(recovered)) npc.spawnWorkOutput(recovered);
        }
        scaffoldBuilt = false;
        scaffoldNeedsRecovery = false;
        placedScaffoldBlock = null;
    }

    private boolean isTemporarilyUnreachable(BlockPos pos) {
        Long until = unreachableUntil.get(pos);
        return until != null && until > npc.level().getGameTime();
    }

    private void markTemporarilyUnreachable(BlockPos pos) {
        if (pos != null) {
            unreachableUntil.put(pos.immutable(), npc.level().getGameTime() + UNREACHABLE_COOLDOWN_TICKS);
        }
    }

    private void clearExpiredUnreachableTargets() {
        long now = npc.level().getGameTime();
        unreachableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    /** 树叶附近 3 格内是否有原木（清树叶以露出树干） */
    private boolean isLeafNearLog(BlockPos pos) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (npc.level().getBlockState(pos.offset(dx, dy, dz)).is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 找一根挨着树干、且离工人最近的树叶，用于卡住时清出通往树的路 */
    private BlockPos findLeafNearLog(BlockPos logPos) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos center = npc.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = logPos.offset(dx, dy, dz);
                    if (npc.level().getBlockState(p).is(BlockTags.LEAVES)
                            && npc.isWorkAllowedAt(p)) {
                        double d = center.distSqr(p);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 从 (x, z) 往下 8 格找第一个非空气方块（地表），找不到返回 null */
    private BlockPos findSurface(int x, int z, int startY) {
        for (int y = startY; y >= startY - 8; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!npc.level().getBlockState(p).isAir()) return p;
        }
        return null;
    }

    /** 地表覆盖层（土/草/砂砾/沙），可挖开 */
    private static boolean isCover(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND);
    }

    /** 该方块正下方 5 格内是否有石头/矿石 */
    private boolean stoneBelow(BlockPos surface) {
        for (int dy = 1; dy <= 5; dy++) {
            if (isMineTarget(npc.level().getBlockState(surface.below(dy)))) return true;
        }
        return false;
    }

    private void mineBlock() {
        BlockState state = npc.level().getBlockState(workPos);
        if (state.isAir()) return;

        boolean highTarget = workPos.getY() >= npc.getBlockY() + 2;
        boolean woodTarget = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
        npc.playAction(woodTarget
                ? (highTarget ? NpcAction.CHOP_HIGH : NpcAction.CHOP)
                : (highTarget ? NpcAction.MINE_HIGH : NpcAction.MINE), 18);

        if (state.is(BlockTags.LOGS)) {
            // 整列砍伐：从树顶到树根一次砍掉整条树干，站在树根也能砍完高树
            chopTrunk(workPos);
        } else if (state.is(BlockTags.LEAVES)) {
            clearLeafCluster(workPos); // 一次清掉一片树叶，快速清出通往树的路
        } else {
            harvestBlock(workPos);
        }
        npc.swing(InteractionHand.MAIN_HAND);
    }

    /** 收集并破坏单个方块（原木/矿石/树叶通用） */
    private void harvestBlock(BlockPos pos) {
        BlockState state = npc.level().getBlockState(pos);
        if (state.isAir()) return;
        List<ItemStack> drops = Block.getDrops(state, (ServerLevel) npc.level(), pos,
                npc.level().getBlockEntity(pos), npc, npc.getMainHandItem());
        for (ItemStack drop : drops) {
            if (!npc.addToBag(drop)) {
                npc.spawnWorkOutput(drop);
            }
        }
        npc.level().destroyBlock(pos, false, npc);
    }

    /** 一次清掉目标周围一片树叶（含目标），最多 MAX_LEAF_CLEAR 片，用于快速清出通往树的路 */
    private void clearLeafCluster(BlockPos start) {
        int cleared = 0;
        for (int dx = -2; dx <= 2 && cleared < MAX_LEAF_CLEAR; dx++) {
            for (int dy = -1; dy <= 1 && cleared < MAX_LEAF_CLEAR; dy++) {
                for (int dz = -2; dz <= 2 && cleared < MAX_LEAF_CLEAR; dz++) {
                    BlockPos p = start.offset(dx, dy, dz);
                    if (npc.level().getBlockState(p).is(BlockTags.LEAVES)
                            && npc.isWorkAllowedAt(p)) {
                        harvestBlock(p);
                        cleared++;
                    }
                }
            }
        }
    }

    /** 整条树干砍伐：从起点向上找树顶、向下找树根，再从顶砍到底 */
    private void chopTrunk(BlockPos start) {
        // 向上找树顶
        BlockPos top = start;
        int height = 0;
        while (npc.level().getBlockState(top).is(BlockTags.LOGS) && height < MAX_TREE_HEIGHT) {
            top = top.above();
            height++;
        }
        // 向下找树根（start 以下的部分）
        BlockPos bottom = start.below();
        int depth = 0;
        while (npc.level().getBlockState(bottom).is(BlockTags.LOGS) && depth < MAX_TREE_HEIGHT) {
            bottom = bottom.below();
            depth++;
        }
        // 从树顶往下逐根砍掉
        BlockPos cursor = top.below();
        for (int i = 0; i < height + depth; i++) {
            if (npc.level().getBlockState(cursor).is(BlockTags.LOGS)) {
                harvestBlock(cursor);
            }
            cursor = cursor.below();
        }
    }

    /** 石头/矿石是否可挖 */
    private static boolean isMineTarget(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.EMERALD_ORES);
    }

    /** 仓库暂时不可用时，在所有常见产出都无处堆叠前停止继续破坏方块。 */
    private boolean canStoreLikelyOutput() {
        return npc.hasBagSpaceFor(new ItemStack(Items.COBBLESTONE))
                || npc.hasBagSpaceFor(new ItemStack(Items.COAL))
                || npc.hasBagSpaceFor(new ItemStack(Items.RAW_IRON))
                || npc.hasBagSpaceFor(new ItemStack(Items.OAK_LOG));
    }

    /** 随机选一个更远的探索目标，走出去找活（有工作范围则在范围内随机） */
    private BlockPos randomExplorePos() {
        RandomSource random = npc.getRandom();
        if (npc.hasWorkZone()) {
            BlockPos min = npc.getWorkZoneMin();
            BlockPos max = npc.getWorkZoneMax();
            int x = min.getX() + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
            int z = min.getZ() + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
            return new BlockPos(x, npc.getBlockY(), z);
        }
        int x = npc.getBlockX() + random.nextInt(SEARCH_RADIUS * 2 + 1) - SEARCH_RADIUS;
        int z = npc.getBlockZ() + random.nextInt(SEARCH_RADIUS * 2 + 1) - SEARCH_RADIUS;
        return new BlockPos(x, npc.getBlockY(), z);
    }
}
