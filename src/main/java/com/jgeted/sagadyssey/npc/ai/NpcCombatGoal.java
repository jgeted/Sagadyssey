package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.combat.CombatEncounterService;
import com.jgeted.sagadyssey.social.combat.EngagementAction;

import java.util.EnumSet;

/**
 * 统一战斗目标选择器。
 * 合并了 ProtectOwnerGoal 和 NpcHostileGoal：
 * - 有主人 → 启用保护主人目标源（OWNER_ATTACKED / OWNER_ATTACKING）
 * - 无主人 → 跳过主人相关源，从 SELF_DEFENSE 开始
 * <p>
 * 使用 TargetSelector 优先级链统一决策，占用 mutex ATTACK（保护主人时额外占用 MOVE）。
 */
public class NpcCombatGoal extends Goal {

    private final NpcBase npc;
    private final TargetSelector targetSelector;
    private int scanCooldown;
    private int priorityRecheckCooldown;
    private int unseenTicks;
    private TargetReason currentReason;

    private static final int SCAN_INTERVAL_PROTECT = 3;   // 保护主人模式扫描更快
    private static final int SCAN_INTERVAL_HOSTILE = 20;  // 普通敌对扫描间隔
    private static final int TARGET_RECHECK_INTERVAL = 3;
    private static final int UNSEEN_FORGET_TICKS = 30;
    private static final int OWNER_THREAT_UNSEEN_TICKS = 100;
    private static final double MAX_COMBAT_RANGE_SQ = 32.0D * 32.0D;

    public NpcCombatGoal(NpcBase npc) {
        this.npc = npc;
        this.targetSelector = buildSelector();
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    /** 根据 NPC 是否有主人构建目标源链 */
    private TargetSelector buildSelector() {
        TargetSelector selector = new TargetSelector(npc);
        // 始终注册主人目标源；源内部自行检查 owner。这样从存档加载 owner 后无需重新构建。
        selector.register(TargetSelector.ownerAttacked());
        selector.register(TargetSelector.ownerAttacking());
        selector.register(TargetSelector.nearbyOwnerThreat());
        selector.register(TargetSelector.selfDefense());
        selector.register(TargetSelector.revenge());
        selector.register(TargetSelector.nearbyHostile());
        return selector;
    }

    @Override
    public boolean canUse() {
        if(com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.withdrawing(npc))return false;
        // STAY 模式下不主动寻敌（除非自卫/保护主人——即前 4 级源）
        if (npc.getCommand() == NpcCommand.STAY) {
            return evaluateStayMode();
        }

        if (--scanCooldown > 0) return false;
        scanCooldown = (npc.getActiveOwner() != null && npc.getCommand() == NpcCommand.FOLLOW)
                ? SCAN_INTERVAL_PROTECT : SCAN_INTERVAL_HOSTILE;

        // 只申请 ATTACK——MOVE 在 start() 中通过停止移动 goal 来处理
        if (!npc.requestMutex(AiMutex.ATTACK)) return false;

        TargetResult result = targetSelector.selectTarget();
        if (result != null && permittedByEncounter(result.target(), result.reason())) {
            npc.setTarget(result.target());
            currentReason = result.reason();
            unseenTicks = 0;
            // 目标已设置，立即释放 ATTACK，让战斗执行 goal（远程/近战）持有该位
            npc.releaseMutex(AiMutex.ATTACK);
            return true;
        }

        // 没找到目标，释放 mutex
        npc.releaseMutex(AiMutex.ATTACK);
        return false;
    }

    /**
     * STAY 模式下只响应高优先级威胁（owner 相关 + 自卫 + 仇恨候选），不主动扫描。
     */
    private boolean evaluateStayMode() {
        // 轻量检查：只查前 4 级（跳过 NEARBY_HOSTILE 扫描）
        TargetResult r;
        r = checkOwnerAttackedQuick();
        if (r != null) { applyResult(r); return true; }
        r = checkOwnerAttackingQuick();
        if (r != null) { applyResult(r); return true; }
        r = TargetSelector.selfDefense().evaluate(npc);
        if (r != null) { applyResult(r); return true; }
        r = TargetSelector.revenge().evaluate(npc);
        if (r != null) { applyResult(r); return true; }

        // 重甲兵的 STAY 是据点坚守：主动拦截十格内敌人，但不会追出阵地。
        if (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.HEAVY) {
            r = TargetSelector.nearbyGuardHostile().evaluate(npc);
            if (r != null && isInsideHeavyGuardRadius(r.target())) {
                applyResult(r);
                return true;
            }
        }

        return false;
    }

    private TargetResult checkOwnerAttackedQuick() {
        return TargetSelector.ownerAttacked().evaluate(npc);
    }

    private TargetResult checkOwnerAttackingQuick() {
        return TargetSelector.ownerAttacking().evaluate(npc);
    }

    private void applyResult(TargetResult r) {
        if (!permittedByEncounter(r.target(), r.reason())) return;
        if (npc.requestMutex(AiMutex.ATTACK)) {
            npc.setTarget(r.target());
            currentReason = r.reason();
            unseenTicks = 0;
            // 目标已设置，立即释放 ATTACK，供战斗执行 goal 持有
            npc.releaseMutex(AiMutex.ATTACK);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if(com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.withdrawing(npc))return false;
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (npc.isCombatAlly(target)) return false;

        // FOLLOWER/保护模式下检查主人距离
        if (npc.getActiveOwner() != null && npc.getCommand() == NpcCommand.FOLLOW) {
            Player owner = npc.getActiveOwner();
            if (owner != null && npc.distanceToSqr(owner) > MAX_COMBAT_RANGE_SQ) return false;
        }

        if (npc.distanceToSqr(target) > MAX_COMBAT_RANGE_SQ) return false;

        if (npc.getCommand() == NpcCommand.PATROL && !isInsidePatrolPursuitRange(target)) return false;

        if (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.HEAVY
                && npc.getCommand() == NpcCommand.STAY && !isInsideHeavyGuardRadius(target)) return false;
        if (!permittedByEncounter(target, currentReason)) return false;

        if (npc.getSensing().hasLineOfSight(target)) {
            unseenTicks = 0;
        } else {
            unseenTicks++;
            int limit = currentReason == TargetReason.OWNER_ATTACKED
                    || currentReason == TargetReason.OWNER_ATTACKING
                    ? OWNER_THREAT_UNSEEN_TICKS : UNSEEN_FORGET_TICKS;
            if (unseenTicks > limit) return false;
        }
        return true;
    }

    @Override
    public void start() {
        priorityRecheckCooldown = 0;
        unseenTicks = 0;
    }

    @Override
    public void tick() {
        if (--priorityRecheckCooldown > 0) return;
        priorityRecheckCooldown = TARGET_RECHECK_INTERVAL;

        TargetResult result = targetSelector.selectTarget();
        if (result == null || !permittedByEncounter(result.target(), result.reason())) return;
        LivingEntity current = npc.getTarget();
        boolean currentInvalid = current == null || !current.isAlive() || npc.isCombatAlly(current);
        boolean higherPriority = currentReason == null
                || result.reason().ordinal() < currentReason.ordinal();
        boolean newerOwnerThreat = result.target() != current
                && (result.reason() == TargetReason.OWNER_ATTACKED
                    || result.reason() == TargetReason.OWNER_ATTACKING)
                && (currentReason == result.reason());
        if (currentInvalid || higherPriority || newerOwnerThreat) {
            npc.setTarget(result.target());
            currentReason = result.reason();
            unseenTicks = 0;
        }
    }

    @Override
    public void stop() {
        npc.setTarget(null);
        currentReason = null;
        unseenTicks = 0;
        // ATTACK 已在 canUse/applyResult 中设置目标后立即释放，
        // 这里不释放，避免误放战斗执行 goal（NpcRangedAttackGoal 等）正持有的位
    }

    /**
     * 重新构建目标源链（主人变更时调用）。
     */
    public void refreshSources() {
        targetSelector.clear();
        targetSelector.register(TargetSelector.ownerAttacked());
        targetSelector.register(TargetSelector.ownerAttacking());
        targetSelector.register(TargetSelector.nearbyOwnerThreat());
        targetSelector.register(TargetSelector.selfDefense());
        targetSelector.register(TargetSelector.revenge());
        targetSelector.register(TargetSelector.nearbyHostile());
    }

    private boolean isInsideHeavyGuardRadius(LivingEntity target) {
        var anchor = npc.getGuardAnchor();
        if (anchor == null) return npc.distanceToSqr(target) <= 100.0D;
        double dx = target.getX() - (anchor.getX() + 0.5D);
        double dz = target.getZ() - (anchor.getZ() + 0.5D);
        return dx * dx + dz * dz <= 100.0D;
    }

    private boolean isInsidePatrolPursuitRange(LivingEntity target) {
        var center = npc.getPatrolCenter();
        if (center == null) return true;
        double dx = target.getX() - (center.getX() + 0.5D);
        double dz = target.getZ() - (center.getZ() + 0.5D);
        double radius = npc.getPatrolRadius() + 10.0D;
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean permittedByEncounter(LivingEntity target, TargetReason reason) {
        if (!com.jgeted.sagadyssey.social.growth.SettlementSafetyRuntime.allowedTarget(npc, target)) return false;
        if (!(npc.level() instanceof ServerLevel level)) return true;
        var encounter = CombatEncounterData.get(level.getServer())
                .activeFor(npc.getCharacterId(), level.getGameTime());
        if (encounter == null) return true;
        java.util.UUID targetCharacter = target instanceof NpcBase other
                ? other.getCharacterId()
                : target instanceof ServerPlayer player
                ? CharacterRegistry.currentId(player) : target.getUUID();
        if (targetCharacter == null) return false;
        boolean immediateThreat = reason == TargetReason.OWNER_ATTACKED
                || reason == TargetReason.SELF_DEFENSE || reason == TargetReason.REVENGE;
        var decision = CombatEncounterService.engagement(encounter,
                npc.getCharacterId(), targetCharacter, immediateThreat);
        return decision.action() == EngagementAction.ATTACK
                || decision.action() == EngagementAction.DEFEND
                || decision.action() == EngagementAction.ARREST && immediateThreat;
    }
}
