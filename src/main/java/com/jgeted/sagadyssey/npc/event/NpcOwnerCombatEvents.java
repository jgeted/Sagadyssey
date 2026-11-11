package com.jgeted.sagadyssey.npc.event;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.command.CommandTactics;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * 将玩家实际造成/受到的伤害明确传播给附近的己方 NPC。
 * 不依赖客户端交互，也不依赖玩家对象的短暂 lastHurt 状态，适用于多人服务器。
 */
public final class NpcOwnerCombatEvents {

    private NpcOwnerCombatEvents() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || event.getNewDamage() <= 0.0F) return;

        if (victim instanceof Player owner
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != owner
                && isAttackablePlayer(attacker)) {
            if (owner instanceof ServerPlayer serverOwner) {
                CommandTactics.dispatchOwnerCombat(serverOwner, attacker, true);
            }
        }

        if (event.getSource().getEntity() instanceof Player owner
                && victim != owner
                && isAttackablePlayer(victim)) {
            if (owner instanceof ServerPlayer serverOwner) {
                CommandTactics.dispatchOwnerCombat(serverOwner, victim, false);
            }
        }

        if (victim instanceof NpcBase protectedNpc
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && protectedNpc.getActiveOwner() instanceof ServerPlayer owner) {
            CommandTactics.dispatchMemberDefense(owner, protectedNpc, attacker);
        }

        if (victim instanceof NpcBase relatedVictim
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && !relatedVictim.isCombatAlly(attacker)) {
            NpcLifeData lifeData = NpcLifeData.get(MinecraftApi.server(relatedVictim));
            if (lifeData.record(relatedVictim.getUUID()) == null) return;
            relatedVictim.level().getEntitiesOfClass(NpcBase.class,
                            relatedVictim.getBoundingBox().inflate(48.0D),
                            npc -> npc.isAlive() && npc != relatedVictim && !npc.isCombatAlly(attacker))
                    .forEach(npc -> {
                        var defenderLife = lifeData.record(npc.getCharacterId());
                        if (defenderLife == null) return;
                        defenderLife.relations().stream()
                            .filter(relation -> relation.otherCharacterId().equals(relatedVictim.getUUID()))
                            .filter(relation -> relation.kind() != NpcRelationKind.RIVAL
                                    && relation.strength() > 0)
                            .findFirst().ifPresent(relation -> {
                                double risk = Math.max(0.0D, attacker.getHealth()
                                        / Math.max(1.0D, npc.getHealth()) * 8.0D);
                                if (NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.PROTECT,
                                        relatedVictim.getUUID(),
                                        28.0D + relation.strength() * 0.35D, risk, true)) {
                                    npc.setHostileTargetCandidate(attacker);
                                }
                            });
                    });
        }
    }

    private static boolean isAttackablePlayer(LivingEntity entity) {
        return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }
}
