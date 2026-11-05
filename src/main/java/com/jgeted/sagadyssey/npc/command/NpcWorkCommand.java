package com.jgeted.sagadyssey.npc.command;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.work.NpcWorkstationContainers;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import com.jgeted.sagadyssey.social.life.NpcNeedsRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Comparator;

/** 面向测试和管理的只读 NPC 工作诊断。 */
public final class NpcWorkCommand {
    private static final double LOOK_RANGE = 16.0D;

    private NpcWorkCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("npc")
                .then(Commands.literal("inspect").requires(source -> source.hasPermission(2))
                        .executes(ctx -> inspect(ctx.getSource())))));
    }

    private static int inspect(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcBase npc = lookedAtNpc(player);
        if (npc == null) {
            source.sendFailure(Component.literal("请看向十六格以内的 NPC。"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§6[NPC 工作诊断] §e" + npc.getNpcName()
                + " §7UUID=" + npc.getCharacterId()), false);
        source.sendSuccess(() -> Component.literal("§7职业=" + npc.getProfession().name()
                + "，命令=" + npc.getCommand().name() + "，位置=" + npc.blockPosition().toShortString()), false);
        source.sendSuccess(() -> Component.literal("§7移动状态="
                + (npc.isPassenger() ? "§c骑乘中" : npc.getNavigation().isInProgress() ? "§a寻路中" : "§e静止")
                + "§7，工作判断=" + workReadiness(npc)), false);
        String taskTarget = npc.getWorkActivityTarget() == null
                ? "无" : npc.getWorkActivityTarget().toShortString();
        source.sendSuccess(() -> Component.literal("§b当前/最近任务=" + npc.getWorkActivity()
                + " §7目标=" + taskTarget + "，更新=" + npc.getWorkActivityAge() + " tick 前"), false);
        if (!npc.getWorkActivityDetail().isBlank()) {
            source.sendSuccess(() -> Component.literal("§7任务详情=" + npc.getWorkActivityDetail()), false);
        }
        source.sendSuccess(() -> Component.literal("§7工作背包=" + npc.getBagUsedSlots() + "/"
                + npc.getEquipmentInventory().getContainerSize() + " 格；原铁=" + countBag(npc, Items.RAW_IRON)
                + "，煤=" + countBag(npc, Items.COAL) + "，铁锭=" + countBag(npc, Items.IRON_INGOT)
                + "，小麦=" + countBag(npc, Items.WHEAT)), false);

        SettlementRecord home = npc.getHomeSettlementId() == null ? null
                : SettlementRegistryData.get(source.getServer()).byId(npc.getHomeSettlementId());
        source.sendSuccess(() -> Component.literal("§7归属聚落="
                + (home == null ? "未设置" : home.name() + " / " + home.id())), false);
        source.sendSuccess(() -> Component.literal("§7工作关系="
                + NpcWorkContext.from(npc).describe()), false);
        NpcLifeData lifeData = NpcLifeData.get(source.getServer());
        NpcLifeRecord life = lifeData.record(npc.getCharacterId());
        if (life == null) {
            source.sendSuccess(() -> Component.literal("§c生活档案=未登记（旧 NPC 可在重新招募或场景修复后补齐）"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§d住宅=" + life.residenceName()
                    + "，私人床=" + position(life.bedPosition())
                    + "，私人储物=" + position(life.privateStoragePosition())), false);
            source.sendSuccess(() -> Component.literal("§7排班=" + life.shift().name()
                    + "，疲劳=" + life.fatigue() + "/1000，当前权威=" + life.authority().name()), false);
            NpcNeedsRecord needs = lifeData.ensureNeeds(npc.getCharacterId(), player.serverLevel().getGameTime());
            source.sendSuccess(() -> Component.literal("§6饥饿=" + needs.hunger()
                    + "/1000（越高越饿），上次食物来源=" + needs.lastFoodSource()), false);
            var schedule = com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.current(npc);
            source.sendSuccess(() -> Component.literal("§e当前日程="
                    + schedule.activity().displayName() + "；" + schedule.reason()), false);
            source.sendSuccess(() -> Component.literal("§d长期目标=" + life.longTermGoal().displayName()
                    + "；勇气=" + life.courage() + "，责任感=" + life.duty()
                    + "，进取心=" + life.ambition() + "，社交性=" + life.sociability()
                    + "，良知=" + life.conscience() + "，信仰=" + life.faith()), false);
            source.sendSuccess(() -> Component.literal("§7重要关系=" + describeRelations(
                    source, life) + "；近期记忆=" + describeMemories(life)), false);
            source.sendSuccess(() -> Component.literal("§b最近决定=" + life.lastDecision()
                    + (life.lastDecisionReason().isBlank() ? "" : "；" + life.lastDecisionReason())), false);
            NpcEmploymentContract contract = lifeData.contract(npc.getCharacterId());
            if (contract != null) {
                source.sendSuccess(() -> Component.literal("§e合同=" + contract.status().displayName()
                        + "，日薪=" + contract.wageEmeralds() + "绿宝石，欠薪="
                        + contract.unpaidEmeralds() + "；" + contract.statusReason()), false);
            }
            if (life.bedPosition() != null) {
                showLine(player, npc.position(), Vec3.atCenterOf(life.bedPosition()));
            }
        }
        if (npc.hasWorkZone()) {
            source.sendSuccess(() -> Component.literal("§7工作区=" + npc.getWorkZoneMin().toShortString()
                    + " → " + npc.getWorkZoneMax().toShortString()), false);
        } else {
            source.sendSuccess(() -> Component.literal("§8工作区=未限制"), false);
        }
        if (npc.getPatrolCenter() != null) {
            source.sendSuccess(() -> Component.literal("§7巡逻中心=" + npc.getPatrolCenter().toShortString()
                    + "，半径=" + npc.getPatrolRadius()), false);
        }

        WarehouseRecord warehouse = nearestUsableWarehouse(source, npc);
        if (warehouse == null) {
            source.sendSuccess(() -> Component.literal("§c可用仓库=无；生产物资只能留在背包或掉落。"), false);
        } else {
            boolean loaded = player.serverLevel().hasChunkAt(warehouse.position())
                    && WarehouseContainers.asContainer(player.serverLevel()
                    .getBlockEntity(warehouse.position())) != null;
            source.sendSuccess(() -> Component.literal("§a最近可用仓库=" + warehouse.name()
                    + "，位置=" + warehouse.position().toShortString()
                    + (loaded ? "" : " §c[当前未加载或容器失效]")), false);
            showLine(player, npc.position(), Vec3.atCenterOf(warehouse.position()));
        }

        var order = LogisticsData.get(source.getServer()).orders().stream()
                .filter(candidate -> npc.getCharacterId().equals(candidate.assignedNpcId()))
                .findFirst().orElse(null);
        if (order != null) {
            WarehouseRecord from = LogisticsData.get(source.getServer()).warehouse(order.sourceWarehouseId());
            WarehouseRecord to = LogisticsData.get(source.getServer()).warehouse(order.destinationWarehouseId());
            source.sendSuccess(() -> Component.literal("§b运输="
                    + (from == null ? "?" : from.name()) + " → " + (to == null ? "?" : to.name())
                    + "，货物=" + order.itemId() + " " + order.deliveredCount()
                    + "/" + order.requestedCount() + "，在途=" + order.inTransitCount()
                    + "，自身货舱=" + npc.getLogisticsCargo()), false);
        } else if (npc.getCommand() == com.jgeted.sagadyssey.npc.entity.NpcCommand.WORK
                && (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.TRADER
                || npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.COURIER)) {
            NpcWorkContext context = NpcWorkContext.from(npc);
            long claimable = LogisticsData.get(source.getServer()).orders().stream()
                    .filter(candidate -> candidate.status()
                            == com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus.PLANNED)
                    .filter(candidate -> context.canClaimOrder(
                            LogisticsData.get(source.getServer()), candidate))
                    .count();
            source.sendSuccess(() -> Component.literal("§e运输状态=等待可领取订单；当前匹配="
                    + claimable), false);
        }
        if (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.BLACKSMITH) {
            describeNearestFurnace(source, player, npc);
        }
        return 1;
    }

    private static String workReadiness(NpcBase npc) {
        if (!npc.isAlive()) return "§c实体已死亡";
        if (npc.isNoAi()) return "§cNoAI 已启用";
        if (npc.getCommand() != com.jgeted.sagadyssey.npc.entity.NpcCommand.WORK) {
            return "§e等待 WORK 命令";
        }
        if (npc.getTarget() != null) return "§e正在处理战斗目标";
        if (npc.isPassenger()
                && (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.TRADER
                || npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.COURIER)) {
            return "§a骑乘物流可运行";
        }
        if (npc.isPassenger()) return "§e等待自动下马";
        return "§a职业 AI 可运行";
    }

    private static int countBag(NpcBase npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < npc.getEquipmentInventory().getContainerSize(); slot++) {
            if (npc.getEquipmentInventory().getItem(slot).is(item)) {
                count += npc.getEquipmentInventory().getItem(slot).getCount();
            }
        }
        return count;
    }

    private static String position(@Nullable BlockPos position) {
        return position == null ? "未分配" : position.toShortString();
    }

    private static String describeRelations(CommandSourceStack source, NpcLifeRecord life) {
        if (life.relations().isEmpty()) return "无";
        CharacterRegistryData characters = CharacterRegistryData.get(source.getServer());
        return life.relations().stream().limit(3).map(relation -> {
            CharacterRecord character = characters.byId(relation.otherCharacterId());
            String name = character == null ? relation.otherCharacterId().toString().substring(0, 8)
                    : character.name();
            return relation.kind().displayName() + ":" + name + "(" + relation.strength() + ")";
        }).collect(java.util.stream.Collectors.joining("，"));
    }

    private static String describeMemories(NpcLifeRecord life) {
        if (life.memories().isEmpty()) return "无";
        return life.memories().stream().limit(2).map(memory -> memory.summary()
                + "[" + (memory.valence() >= 0 ? "+" : "") + memory.valence() + "]")
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private static void describeNearestFurnace(CommandSourceStack source, ServerPlayer player, NpcBase npc) {
        BlockPos origin = npc.blockPosition();
        BlockPos furnace = null;
        double nearest = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -3, -16), origin.offset(16, 3, 16))) {
            var state = player.serverLevel().getBlockState(pos);
            if (!state.is(Blocks.FURNACE) && !state.is(Blocks.BLAST_FURNACE)) continue;
            double distance = pos.distSqr(origin);
            if (distance < nearest) {
                nearest = distance;
                furnace = pos.immutable();
            }
        }
        if (furnace == null) {
            source.sendSuccess(() -> Component.literal("§c附近熔炉=无"), false);
            return;
        }
        BlockPos finalFurnace = furnace;
        Container inventory = NpcWorkstationContainers.asFurnace(player.serverLevel().getBlockEntity(furnace));
        if (inventory == null || inventory.getContainerSize() < 3) {
            source.sendSuccess(() -> Component.literal("§c熔炉容器失效，位置=" + finalFurnace.toShortString()), false);
            return;
        }
        String input = inventory.getItem(0).isEmpty() ? "空" : inventory.getItem(0).getCount()
                + "×" + inventory.getItem(0).getHoverName().getString();
        String fuel = inventory.getItem(1).isEmpty() ? "空" : inventory.getItem(1).getCount()
                + "×" + inventory.getItem(1).getHoverName().getString();
        String output = inventory.getItem(2).isEmpty() ? "空" : inventory.getItem(2).getCount()
                + "×" + inventory.getItem(2).getHoverName().getString();
        source.sendSuccess(() -> Component.literal("§6熔炉=" + finalFurnace.toShortString()
                + " §7输入[" + input + "] 燃料[" + fuel + "] 输出[" + output + "]"), false);
    }

    @Nullable
    private static WarehouseRecord nearestUsableWarehouse(CommandSourceStack source, NpcBase npc) {
        String dimension = npc.level().dimension().location().toString();
        return LogisticsData.get(source.getServer()).warehouses().stream()
                .filter(warehouse -> warehouse.dimension().equals(dimension))
                .filter(warehouse -> canUseWarehouse(source, npc, warehouse))
                .min(Comparator.comparingDouble(warehouse -> warehouse.position().distSqr(npc.blockPosition())))
                .orElse(null);
    }

    private static boolean canUseWarehouse(CommandSourceStack source, NpcBase npc, WarehouseRecord warehouse) {
        return NpcWorkContext.from(npc).canUseWarehouse(source.getServer(), warehouse);
    }

    @Nullable
    private static NpcBase lookedAtNpc(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0F);
        Vec3 end = eye.add(direction.scale(LOOK_RANGE));
        AABB search = player.getBoundingBox().expandTowards(direction.scale(LOOK_RANGE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> entity instanceof NpcBase && entity.isPickable(), LOOK_RANGE * LOOK_RANGE);
        return hit != null && hit.getEntity() instanceof NpcBase npc ? npc : null;
    }

    private static void showLine(ServerPlayer player, Vec3 start, Vec3 end) {
        DustParticleOptions particle = new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.35F), 0.8F);
        Vec3 delta = end.subtract(start);
        int steps = Math.max(1, Math.min(96, (int) Math.ceil(delta.length() * 2.0D)));
        for (int index = 0; index <= steps; index++) {
            Vec3 point = start.add(delta.scale((double) index / steps));
            player.serverLevel().sendParticles(player, particle, true,
                    point.x, point.y + 0.5D, point.z, 1, 0, 0, 0, 0);
        }
    }
}
