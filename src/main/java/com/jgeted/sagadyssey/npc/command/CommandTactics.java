package com.jgeted.sagadyssey.npc.command;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 集团/小队战术层。这里只产生带时效的目标意图，真正追击、视线丢失和自卫仍由 NPC AI 决定。
 */
public final class CommandTactics {
    public static final double RESPONSE_RANGE = 40.0D;
    public static final long ORDER_TICKS = 100L;

    private CommandTactics() {}

    public static void dispatchOwnerCombat(ServerPlayer owner, LivingEntity focalTarget, boolean defense) {
        PlayerCommandData data = CommandGroupAttachments.get(owner);
        List<NpcBase> owned = owner.level().getEntitiesOfClass(NpcBase.class,
                owner.getBoundingBox().inflate(RESPONSE_RANGE),
                npc -> npc.isAlive() && npc.isOwnedBy(owner.getUUID()) && !npc.isCombatAlly(focalTarget));
        if (owned.isEmpty()) return;
        owned.forEach(npc -> data.registerMember(npc.getCharacterId(), npc.getNpcName()));

        List<LivingEntity> opponents = collectOpponents(owner, focalTarget);
        Map<String, List<NpcBase>> squads = new LinkedHashMap<>();
        for (NpcBase npc : owned) squads.computeIfAbsent(data.squadOf(npc.getCharacterId()), ignored -> new ArrayList<>()).add(npc);

        for (Map.Entry<String, List<NpcBase>> entry : squads.entrySet()) {
            SquadStrategy strategy = data.strategy(entry.getKey());
            List<NpcBase> members = entry.getValue();
            members.sort(Comparator.comparingInt((NpcBase npc) -> data.rankOf(npc.getCharacterId()).priority()).reversed());

            if (strategy == SquadStrategy.RETREAT) {
                members.forEach(npc -> {
                    if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) {
                        com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.requestWithdrawal(owner,npc);
                    } else if(com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(owner,npc,NpcCommand.FOLLOW).accepted()) {
                        npc.clearOwnerCombatTargets();npc.setTarget(null);
                    }
                });
                continue;
            }
            if ((strategy == SquadStrategy.HOLD_POSITION || strategy == SquadStrategy.PROTECT_OWNER
                    || strategy == SquadStrategy.ESCORT_CONVOY) && !defense) {
                continue;
            }

            for (int i = 0; i < members.size(); i++) {
                NpcBase npc = members.get(i);
                LivingEntity target = chooseTarget(owner, npc, focalTarget, opponents, strategy, i);
                if (target == null) continue;
                if (strategy == SquadStrategy.HOLD_POSITION && npc.distanceToSqr(target) > 24.0D * 24.0D) continue;
                if (defense) npc.rememberOwnerDefenseTarget(target, ORDER_TICKS);
                else npc.rememberOwnerAssistTarget(target, ORDER_TICKS);
            }
        }
    }

    /** 高等级成员受击时，同小队会派出更多保护者。 */
    public static void dispatchMemberDefense(ServerPlayer owner, NpcBase protectedNpc, LivingEntity attacker) {
        if (protectedNpc.isCombatAlly(attacker)) return;
        PlayerCommandData data = CommandGroupAttachments.get(owner);
        data.registerMember(protectedNpc.getCharacterId(), protectedNpc.getNpcName());
        String squad = data.squadOf(protectedNpc.getCharacterId());
        int responderLimit = 1 + data.rankOf(protectedNpc.getCharacterId()).priority();
        List<NpcBase> responders = owner.level().getEntitiesOfClass(NpcBase.class,
                        protectedNpc.getBoundingBox().inflate(RESPONSE_RANGE),
                        npc -> npc != protectedNpc && npc.isAlive() && npc.isOwnedBy(owner.getUUID()))
                .stream()
                .filter(npc -> {
                data.registerMember(npc.getCharacterId(), npc.getNpcName());
                SquadStrategy strategy = data.strategyOf(npc.getCharacterId());
                return squad.equals(data.squadOf(npc.getCharacterId())) && strategy != SquadStrategy.RETREAT;
                })
                .sorted(Comparator.comparingDouble(npc -> npc.distanceToSqr(protectedNpc)))
                .limit(responderLimit)
                .toList();
        responders.forEach(npc -> npc.rememberOwnerDefenseTarget(attacker, ORDER_TICKS));
    }

    public static boolean allowsOwnerSignal(NpcBase npc, boolean defense) {
        SquadStrategy strategy = strategyOf(npc);
        if (strategy == SquadStrategy.RETREAT) return false;
        if (!defense && (strategy == SquadStrategy.PROTECT_OWNER || strategy == SquadStrategy.HOLD_POSITION
                || strategy == SquadStrategy.ESCORT_CONVOY)) return false;
        return true;
    }

    public static boolean allowsAutonomousScan(NpcBase npc) {
        SquadStrategy strategy = strategyOf(npc);
        return strategy != SquadStrategy.RETREAT
                && strategy != SquadStrategy.HOLD_POSITION
                && strategy != SquadStrategy.PROTECT_OWNER
                && strategy != SquadStrategy.ESCORT_CONVOY;
    }

    public static SquadStrategy strategyOf(NpcBase npc) {
        if (!(npc.getActiveOwner() instanceof ServerPlayer owner)) {
            return SquadStrategy.BALANCED;
        }
        PlayerCommandData data = CommandGroupAttachments.get(owner);
            data.registerMember(npc.getCharacterId(), npc.getNpcName());
            return data.strategyOf(npc.getCharacterId());
    }

    private static LivingEntity chooseTarget(ServerPlayer owner, NpcBase npc, LivingEntity focal,
                                             List<LivingEntity> opponents, SquadStrategy strategy, int index) {
        List<LivingEntity> visible = opponents.stream()
                .filter(LivingEntity::isAlive)
                .filter(target -> target.level() == npc.level() && !npc.isCombatAlly(target))
                .filter(target -> target == focal || npc.getSensing().hasLineOfSight(target))
                .filter(target -> npc.distanceToSqr(target) <= RESPONSE_RANGE * RESPONSE_RANGE)
                .toList();
        if (visible.isEmpty()) return null;
        return switch (strategy) {
            case NEAREST -> visible.stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(focal);
            case HIGH_RANK_FIRST -> visible.stream().max(Comparator
                    .comparingInt((LivingEntity target) -> enemyRank(owner, target))
                    .thenComparingDouble(target -> -npc.distanceToSqr(target))).orElse(focal);
            case BALANCED -> visible.get(index % visible.size());
            case PROTECT_OWNER, HOLD_POSITION, ESCORT_CONVOY -> visible.contains(focal) ? focal : visible.getFirst();
            case RETREAT -> null;
        };
    }

    private static List<LivingEntity> collectOpponents(ServerPlayer owner, LivingEntity focal) {
        List<LivingEntity> result = new ArrayList<>();
        result.add(focal);
        UUID opposingOwner = null;
        if (focal instanceof Player player) opposingOwner = player.getUUID();
        if (focal instanceof NpcBase npc) opposingOwner = npc.getOwnerUUID();
        if (opposingOwner != null && !opposingOwner.equals(owner.getUUID())) {
            UUID finalOpposingOwner = opposingOwner;
            AABB area = focal.getBoundingBox().inflate(RESPONSE_RANGE);
            focal.level().getEntitiesOfClass(NpcBase.class, area,
                            npc -> npc.isAlive() && npc.isOwnedBy(finalOpposingOwner))
                    .forEach(npc -> { if (!result.contains(npc)) result.add(npc); });
        }
        return result;
    }

    private static int enemyRank(ServerPlayer owner, LivingEntity target) {
        if (target instanceof Player) return CommandRank.COMMANDER.priority();
        if (target instanceof NpcBase npc && npc.getOwnerUUID() != null) {
            ServerPlayer enemyOwner = owner.server.getPlayerList().getPlayer(npc.getOwnerUUID());
            if (enemyOwner != null) return CommandGroupAttachments.get(enemyOwner).rankOf(npc.getCharacterId()).priority();
        }
        return CommandRank.MEMBER.priority();
    }
}
