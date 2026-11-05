package com.jgeted.sagadyssey.social.logistics;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** 运输者死亡时把货舱内容实体化掉落，并释放未完成订单供其他运输者接手。 */
public final class NpcLogisticsEvents {
    private NpcLogisticsEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onNpcDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof NpcBase npc) || !(npc.level() instanceof ServerLevel level)) return;
        com.jgeted.sagadyssey.social.needs.SupplyFlowService.carrierLost(npc, level.getGameTime());
        com.jgeted.sagadyssey.social.religion.ReligionTask.onDeath(npc);
        com.jgeted.sagadyssey.social.religion.life.ChurchLifeTask.onDeath(npc);
        if (!npc.getTransportInventory().isEmpty()) {
            var before=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,npc.getBoundingBox().inflate(3)).stream().map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            Containers.dropContents(level, npc.blockPosition(), npc.getTransportInventory());
            npc.getTransportInventory().clearContent();
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,npc.getBoundingBox().inflate(3)).stream().filter(drop->!before.contains(drop.getUUID()))
                    .forEach(drop->com.jgeted.sagadyssey.social.combat.NpcCombatRuntime.cargoDropped(npc,drop));
        }
        com.jgeted.sagadyssey.social.combat.NpcCombatRuntime.death(npc);
        LogisticsData.get(level.getServer()).releaseOrdersForNpc(npc.getCharacterId(), level.getGameTime());
    }
}
