package com.jgeted.sagadyssey.npc.event;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Set;

public class NpcExperienceEvents {

    /** 战斗经验：NPC 造成伤害 → 1 点伤害 = 1 经验 */
    @SubscribeEvent
    public static void onNpcDealDamage(LivingDamageEvent.Pre event) {
        if (event.getSource().getEntity() instanceof NpcBase npc) {
            if (npc.getNpcLevel() >= 4) return;
            int xp = (int) event.getNewDamage();
            Set<NpcProfession> combatProfs = Set.of(
                    NpcProfession.WARRIOR, NpcProfession.ARCHER, NpcProfession.HEAVY);
            if (combatProfs.contains(npc.getProfession())) {
                xp = (int) (xp * 1.25);
            }
            npc.addExperience(xp);
        }
    }

    /** 农民经验：主人（跟随模式下）收获成熟作物 */
    @SubscribeEvent
    public static void onCropHarvest(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return;

        // 搜索附近跟随的农民 NPC
        player.level().getEntitiesOfClass(NpcBase.class,
                player.getBoundingBox().inflate(12.0D),
                npc -> npc.isOwnedBy(player.getUUID())
                        && npc.getProfession() == NpcProfession.FARMER
                        && npc.getCommand() == NpcCommand.FOLLOW
                        && npc.getNpcLevel() < 4)
                .forEach(npc -> npc.addExperience(5));
    }

    /** 医师经验：主人受到伤害时，附近跟随的医师获得经验 */
    @SubscribeEvent
    public static void onOwnerDamaged(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof Player player)) return;
        player.level().getEntitiesOfClass(NpcBase.class,
                player.getBoundingBox().inflate(12.0D),
                npc -> npc.isOwnedBy(player.getUUID())
                        && npc.getProfession() == NpcProfession.MEDIC
                        && npc.getCommand() == NpcCommand.FOLLOW
                        && npc.getNpcLevel() < 4)
                .forEach(npc -> npc.addExperience((int) event.getNewDamage()));
    }
}
