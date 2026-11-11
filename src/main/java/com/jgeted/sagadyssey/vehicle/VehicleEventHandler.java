package com.jgeted.sagadyssey.vehicle;

import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Vehicle 模块事件处理器。
 * 处理右键绑定坐骑、给栓绳、NPC 出生带坐骑等交互。
 */
public class VehicleEventHandler {

    /**
     * 右键 NPC 交互：
     * 情况 A：拿栓绳（没牵马）→ 给 NPC 栓绳
     * 情况 B：牵马右键 → 绑定坐骑
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        var hand = event.getHand();
        var stack = player.getItemInHand(hand);

        if (!(event.getTarget() instanceof NpcBase npc)) return;

        if (stack.is(Items.LEAD)) {
            // 检测玩家是否正在牵马
            boolean isLeadingHorse = false;
            for (AbstractHorse horse : player.level().getEntitiesOfClass(AbstractHorse.class,
                    player.getBoundingBox().inflate(8))) {
                if (horse instanceof Leashable leashable
                        && leashable.isLeashed()
                        && leashable.getLeashHolder() == player) {
                    isLeadingHorse = true;
                    break;
                }
            }

            if (!isLeadingHorse) {
                // 情况 A：玩家只是拿着栓绳 → 给 NPC 背包加栓绳
                for (int i = 0; i < npc.getEquipmentInventory().getContainerSize(); i++) {
                    if (npc.getEquipmentInventory().getItem(i).isEmpty()) {
                        npc.getEquipmentInventory().setItem(i, new ItemStack(Items.LEAD));
                        if (!player.level().isClientSide()) {
                            player.displayClientMessage(
                                    Component.literal("§a" + npc.getNpcName() + " 拿到了一根栓绳"), true);
                        }
                        if (!player.getAbilities().instabuild) {
                            stack.shrink(1);
                        }
                        event.setCanceled(true);
                        return;
                    }
                }
                // NPC 背包满了
                if (!player.level().isClientSide()) {
                    player.displayClientMessage(
                            Component.literal("§c" + npc.getNpcName() + " 背包已满"), true);
                }
                event.setCanceled(true);
            } else {
                // 情况 B：玩家牵着马右键 NPC → 绑定坐骑
                if (npc.hasMount()) {
                    if (!player.level().isClientSide()) {
                        player.displayClientMessage(
                                Component.literal("§c" + npc.getNpcName() + " 已有坐骑"), true);
                    }
                    event.setCanceled(true);
                    return;
                }

                // 找附近属于该玩家的已驯服马（优先玩家正牵着的）
                AbstractHorse nearestHorse = null;
                double nearestDist = 10.0;
                for (AbstractHorse horse : npc.level().getEntitiesOfClass(AbstractHorse.class,
                        npc.getBoundingBox().inflate(10))) {
                    if (horse.isTamed() && com.jgeted.sagadyssey.core.MinecraftApi.ownerId(horse) != null
                            && com.jgeted.sagadyssey.core.MinecraftApi.ownerId(horse).equals(player.getUUID())) {
                        // 优先选中玩家牵着的马
                        if (horse instanceof Leashable l && l.isLeashed() && l.getLeashHolder() == player) {
                            nearestHorse = horse;
                            break;
                        }
                        double d = npc.distanceTo(horse);
                        if (d < nearestDist) {
                            nearestDist = d;
                            nearestHorse = horse;
                        }
                    }
                }

                if (nearestHorse != null) {
                    if (!player.level().isClientSide()) {
                        npc.bindMount(nearestHorse);
                        // bindMount 内已尝试上马，这里兜底：距离稍远时也强制骑
                        if (!npc.isPassenger() && nearestHorse.isSaddled()
                                && npc.distanceTo(nearestHorse) <= 5.0F) {
                            npc.startRiding(nearestHorse);
                        }
                        player.displayClientMessage(
                                Component.literal("§a" + npc.getNpcName() + " 已绑定坐骑！"), true);
                    }
                }
                event.setCanceled(true);
            }
        }
    }

    /**
     * NPC 生成后概率附带坐骑。
     */
    @SubscribeEvent
    public void onNpcSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        // Chunk/world reload is not a new spawn. Re-rolling here creates mounts, saddles
        // and a lead in an existing body's inventory, breaking LOD asset reconciliation.
        if (event.loadedFromDisk()) return;
        if (!(event.getEntity() instanceof NpcBase npc)) return;
        if (npc.hasMount()) return;
        if (!canSpawnWithMount(npc)) return;
        if (npc.getRandom().nextFloat() >= SagadysseyConfig.MOUNT_SPAWN_CHANCE.get()) return;

        EntityType<?> mountType = selectMountFor(npc);
        Entity mount = mountType.create(npc.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (mount == null) return;

        mount.setPos(npc.getX(), npc.getY(), npc.getZ());

        // 让马匹正常初始化（随机变体、属性等），否则所有马都是默认白马
        if (mount instanceof net.minecraft.world.entity.Mob mob) {
            mob.finalizeSpawn(
                    (net.minecraft.server.level.ServerLevel) npc.level(),
                    com.jgeted.sagadyssey.core.MinecraftApi.serverLevel(npc).getCurrentDifficultyAt(npc.blockPosition()),
                    net.minecraft.world.entity.EntitySpawnReason.COMMAND,
                    null
            );
        }

        npc.level().addFreshEntity(mount);

        if (mount instanceof AbstractHorse horse) {
            horse.setTamed(true);
            Player owner = npc.getActiveOwner();
            if (owner != null) {
                horse.tameWithName(owner);
            }
            horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));

            // bindMount 会自动配栓绳
            npc.bindMount(horse);
        }
    }

    /** 根据职业选择坐骑类型 */
    private static EntityType<?> selectMountFor(NpcBase npc) {
        return switch (npc.getProfession()) {
            case WORKER, FARMER, BLACKSMITH, TRADER, COURIER ->
                    npc.getRandom().nextBoolean() ? EntityType.DONKEY : EntityType.MULE;
            default -> EntityType.HORSE;
        };
    }

    /**
     * 自动坐骑只分配给需要长距离机动的职业。生产者必须能立即使用工作站，
     * 固定待命者也不应因为区块重新加载而逐渐堆积无用坐骑。
     */
    private static boolean canSpawnWithMount(NpcBase npc) {
        if (npc.getCommand() == NpcCommand.STAY) return false;
        NpcProfession profession = npc.getProfession();
        return profession == NpcProfession.WARRIOR || profession == NpcProfession.ARCHER
                || profession == NpcProfession.HEAVY || profession == NpcProfession.TRADER
                || profession == NpcProfession.COURIER;
    }
}
