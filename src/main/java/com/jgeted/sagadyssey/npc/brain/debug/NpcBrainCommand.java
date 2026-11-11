package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
import com.jgeted.sagadyssey.social.production.ProductionBatchData;
import com.jgeted.sagadyssey.social.production.ProductionMaterialRequestData;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/** 管理员专用的 NPC 计划真值查看与灰度切换入口。 */
public final class NpcBrainCommand {
    private NpcBrainCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> brain = Commands.literal("brain");
        brain.requires(source -> MinecraftApi.hasPermission(source, 2));
        brain.then(Commands.literal("inspect")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> inspect(context.getSource(),
                                EntityArgument.getEntity(context, "target")))));
        brain.then(Commands.literal("history")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> history(context.getSource(),
                                EntityArgument.getEntity(context, "target")))));
        brain.then(Commands.literal("reservations")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> reservations(context.getSource(),
                                EntityArgument.getEntity(context, "target")))));
        brain.then(Commands.literal("mode")
                .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.literal("legacy")
                                .executes(context -> setMode(context.getSource(),
                                        EntityArgument.getEntity(context, "target"),
                                        NpcBehaviorMode.LEGACY)))
                        .then(Commands.literal("planned")
                                .executes(context -> setMode(context.getSource(),
                                        EntityArgument.getEntity(context, "target"),
                                        NpcBehaviorMode.PLANNED)))));
        brain.then(Commands.literal("seed-wait")
                .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 72_000))
                                .executes(context -> seedWait(context.getSource(),
                                        EntityArgument.getEntity(context, "target"),
                                        IntegerArgumentType.getInteger(context, "ticks"))))));
        brain.then(Commands.literal("orders")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> orders(context.getSource(),
                                EntityArgument.getEntity(context, "target")))));
        brain.then(Commands.literal("fieldwork")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> fieldwork(context.getSource(), EntityArgument.getEntity(context, "target")))));
        brain.then(Commands.literal("bind-fieldwork")
                .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("container", BlockPosArgument.blockPos())
                                .executes(context -> bindFieldwork(context.getSource(), EntityArgument.getEntity(context, "target"),
                                        BlockPosArgument.getLoadedBlockPos(context, "container"))))));
        brain.then(Commands.literal("seed-blacksmith")
                .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("workstation", BlockPosArgument.blockPos())
                                .then(Commands.argument("container", BlockPosArgument.blockPos())
                                        .executes(context -> seedBlacksmith(
                                                context.getSource(),
                                                EntityArgument.getEntity(context, "target"),
                                                BlockPosArgument.getLoadedBlockPos(
                                                        context, "workstation"),
                                                BlockPosArgument.getLoadedBlockPos(
                                                        context, "container")))))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("npc").then(brain)));
    }

    private static int inspect(CommandSourceStack source, Entity entity) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        for (String line : NpcBrainSnapshotFormatter.format(snapshot(source, npc))) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int fieldwork(CommandSourceStack source, Entity entity) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        var data = FieldworkData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("职业作业后台真值｜" + npc.getNpcName() + "｜" + npc.getProfession()), false);
        data.scan(npc.getUUID()).ifPresent(cursor -> source.sendSuccess(() -> Component.literal("检索=" + cursor.nextIndex()
                + "/" + cursor.area().size() + "｜未加载=" + cursor.sawUnloaded() + "｜暂缓重试@" + cursor.retryAt()), false));
        source.sendSuccess(() -> Component.literal("待交付=" + data.pendingCargo(npc.getUUID())
                + "｜脚手架回收义务=" + data.outstandingScaffolds(npc.getUUID()).size()
                + "｜交付重试@" + data.deliveryRetryAt(npc.getUUID())), false);
        source.sendSuccess(() -> Component.literal("未完成交付意图=" + data.pendingDelivery(npc.getUUID())), false);
        var receipts = data.receiptsFor(npc.getUUID());
        receipts.stream().skip(Math.max(0, receipts.size() - 8)).forEach(receipt -> source.sendSuccess(() -> Component.literal(
                receipt.action().key() + "｜" + receipt.action().operation() + "｜" + receipt.action().target().toShortString()
                        + "｜投入产出=" + receipt.inventoryDeltas() + "｜工具损耗=" + receipt.toolDamage() + "｜提交@" + receipt.committedAt()), false));
        data.memories(npc.getUUID()).stream().limit(8).forEach(memory -> source.sendSuccess(() -> Component.literal(
                "短期记忆=" + memory.target().toShortString() + "｜" + memory.reason() + "｜重试@" + memory.retryAt()), false));
        return 1;
    }

    private static int bindFieldwork(CommandSourceStack source, Entity entity, BlockPos position) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        if (!FieldworkDiscovery.producer(npc) || npc.level() != source.getLevel()) {
            source.sendFailure(Component.literal("测试绑定需要本维度的农民或工人")); return 0;
        }
        var container = WarehouseContainers.asContainer(source.getLevel().getBlockEntity(position));
        if (container == null) { source.sendFailure(Component.literal("目标必须是已加载的储物容器")); return 0; }
        String dimension = source.getLevel().dimension().identifier().toString();
        UUID id = ContainerIds.block("fieldwork_test", dimension, position);
        SubjectRef owner = SubjectRef.character(npc.getCharacterId());
        var rights = RightsStateData.get(source.getServer());
        var existing = rights.context(id);
        if (existing != null && !existing.defaultBeneficialOwner().equals(owner)) {
            source.sendFailure(Component.literal("不能覆盖另一劳动者的测试容器所有权")); return 0;
        }
        rights.ensureContext(id, dimension + ':' + position.asLong(), owner, owner, AccessPolicy.PRIVATE);
        FieldworkData.get(source.getServer()).bindTestStorage(npc.getUUID(), new FieldworkStorage(dimension, position, id,
                owner, AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "fieldwork_test"));
        npc.setCommand(NpcCommand.WORK);
        NpcBrainRuntime.setMode(npc, NpcBehaviorMode.PLANNED);
        source.sendSuccess(() -> Component.literal("已绑定职业测试储物处并启用计划制工作；不会增加物品或清除旧计划"), false);
        return 1;
    }

    private static int history(CommandSourceStack source, Entity entity) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        NpcBrainSnapshot snapshot = snapshot(source, npc);
        source.sendSuccess(() -> Component.literal("NPC计划历史 " + npc.getNpcName()
                + "｜共" + snapshot.recentHistory().size() + "项"), false);
        if (snapshot.recentHistory().isEmpty()) {
            source.sendSuccess(() -> Component.literal("无已结束计划。"), false);
        } else {
            snapshot.recentHistory().forEach(entry -> source.sendSuccess(() -> Component.literal(
                    entry.status() + "｜" + entry.kind() + "｜" + entry.candidateKey()
                            + "｜更新@" + entry.updatedAt()
                            + (entry.explanation().isBlank() ? "" : "｜" + entry.explanation())), false));
        }
        return 1;
    }

    private static int reservations(CommandSourceStack source, Entity entity) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        NpcBrainSnapshot snapshot = snapshot(source, npc);
        source.sendSuccess(() -> Component.literal("NPC资源保留 " + npc.getNpcName()
                + "｜共" + snapshot.reservations().size() + "项"), false);
        if (snapshot.reservations().isEmpty()) {
            source.sendSuccess(() -> Component.literal("该 NPC 没有互斥资源保留（含非活动计划）。"), false);
        } else {
            snapshot.reservations().forEach(entry -> source.sendSuccess(() -> Component.literal(
                    entry.type() + "｜" + entry.resourceId() + "｜计划=" + entry.planId()
                            + "｜到期@" + entry.expiresAt()
                            + (snapshot.isActiveReservation(entry) ? "" : "｜非活动计划")), false));
        }
        var materials = NpcProductionReservationAudit.capture(npc.getCharacterId(),
                ProductionOrderData.get(source.getServer()).ordersForWorker(npc.getCharacterId()),
                ProductionBatchData.get(source.getServer()).batches(),
                RightsStateData.get(source.getServer()).reservations());
        source.sendSuccess(() -> Component.literal("生产原料预留｜共" + materials.size() + "项"), false);
        materials.forEach(entry -> source.sendSuccess(() -> Component.literal(
                entry.fingerprint().itemId() + " ×" + entry.quantity() + "｜" + entry.token()
                        + "｜容器=" + entry.containerId() + "｜到期@" + entry.expiresAt()), false));
        return 1;
    }

    private static int setMode(CommandSourceStack source, Entity entity, NpcBehaviorMode mode) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        NpcBrainRuntime.setMode(npc, mode);
        source.sendSuccess(() -> Component.literal("已将 " + npc.getNpcName() + " 切换为 " + mode
                + (mode == NpcBehaviorMode.PLANNED
                ? "。新模式不会运行旧职业 Goal；尚无计划时 NPC 会等待。"
                : "。活动计划已取消并恢复旧行为。")), true);
        return 1;
    }

    private static int seedWait(CommandSourceStack source, Entity entity, int ticks) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        try {
            var plan = NpcBrainRuntime.createDiagnosticWait(npc, ticks);
            source.sendSuccess(() -> Component.literal("已创建无副作用等待计划 "
                    + plan.id().toString().substring(0, 8) + "，持续 " + ticks + " tick。"), true);
            return 1;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int orders(CommandSourceStack source, Entity entity) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        List<ProductionOrder> orders = ProductionOrderData.get(source.getServer())
                .ordersForWorker(npc.getCharacterId());
        source.sendSuccess(() -> Component.literal("铁匠生产订单 " + npc.getNpcName()
                + "｜共" + orders.size() + "项｜动作启动计数="
                + npc.getActionStartCount()), false);
        if (orders.isEmpty()) {
            source.sendSuccess(() -> Component.literal("没有分配给该 NPC 的生产订单。"), false);
        } else {
            orders.stream().limit(12).forEach(order -> {
                long openRequests = ProductionMaterialRequestData.get(source.getServer())
                        .forOrder(order.id()).stream()
                        .filter(request -> request.status()
                                == com.jgeted.sagadyssey.social.production.ProductionMaterialRequest.Status.OPEN)
                        .count();
                source.sendSuccess(() -> Component.literal(
                    order.id().toString().substring(0, 8) + "｜" + order.status()
                            + "｜" + order.recipeId() + "｜批次 "
                            + order.batchId().toString().substring(0, 8)
                            + "｜缺料请求 " + openRequests
                            + (order.blocker() == null ? "" : "｜阻塞 "
                            + order.blocker().code() + " 重试@" + order.blocker().retryAt())
                            + "｜" + order.detail()), false);
            });
        }
        return 1;
    }

    private static int seedBlacksmith(CommandSourceStack source, Entity entity,
                                      BlockPos workstation, BlockPos containerPosition) {
        NpcBase npc = requireNpc(source, entity);
        if (npc == null) return 0;
        if (npc.getProfession() != NpcProfession.BLACKSMITH) {
            source.sendFailure(Component.literal("目标 NPC 不是铁匠。"));
            return 0;
        }
        if (NpcBrainRuntime.data(source.getServer()).active(npc.getUUID()).isPresent()) {
            source.sendFailure(Component.literal("目标 NPC 已有活动计划，请先等待完成或切回 legacy 取消。"));
            return 0;
        }
        if (!(npc.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("铁匠不在服务端世界中。"));
            return 0;
        }
        if (!isSmithingStation(level, workstation)) {
            source.sendFailure(Component.literal("workstation 必须是铁砧或锻造台。"));
            return 0;
        }
        if (WarehouseContainers.asContainer(level.getBlockEntity(containerPosition)) == null) {
            source.sendFailure(Component.literal("container 必须是已加载的箱子、木桶或潜影盒。"));
            return 0;
        }
        try {
            String dimension = level.dimension().identifier().toString();
            UUID containerId = ProductionContainerIds.resolve(source.getServer(), dimension,
                    containerPosition);
            SubjectRef issuer = SubjectRef.character(npc.getCharacterId());
            var rightsContext = RightsStateData.get(source.getServer()).ensureContext(containerId,
                    dimension + ":production_order:" + containerPosition.asLong(),
                    issuer, issuer, AccessPolicy.CONTRACT);
            SubjectRef owner = rightsContext.defaultBeneficialOwner();
            long now = level.getGameTime();
            ProductionOrder order = ProductionOrder.open(UUID.randomUUID(), issuer, owner,
                    "sagadyssey:test_iron_pickaxe",
                    List.of(new ProductionMaterial(
                                    ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)), 3),
                            new ProductionMaterial(
                                    ItemFingerprint.of(new ItemStack(Items.COAL)), 1)),
                    new ProductionMaterial(
                            ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)), 1),
                    80, UUID.randomUUID(), dimension, workstation, containerId,
                    dimension, containerPosition, now, "管理员测试订单：铁镐");
            ProductionOrderData.get(source.getServer()).put(order);
            NpcBrainRuntime.setMode(npc, NpcBehaviorMode.PLANNED);
            var plan = NpcBrainRuntime.createBlacksmithOrderPlan(npc, order.id());
            source.sendSuccess(() -> Component.literal("已创建铁匠测试订单 "
                    + order.id().toString().substring(0, 8) + " 与计划 "
                    + plan.id().toString().substring(0, 8)
                    + "。容器需有 3 铁锭、1 煤炭和至少 1 个空槽。"), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage()));
            return 0;
        }
    }

    private static boolean isSmithingStation(ServerLevel level, BlockPos position) {
        var block = level.getBlockState(position).getBlock();
        return block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL || block == Blocks.SMITHING_TABLE;
    }

    private static NpcBrainSnapshot snapshot(CommandSourceStack source, NpcBase npc) {
        NpcBrainData data = NpcBrainRuntime.data(source.getServer());
        return NpcBrainSnapshot.capture(npc.getUUID(), data.mode(npc.getUUID()),
                data.active(npc.getUUID()), data.history(npc.getUUID()),
                NpcBrainRuntime.reservations(source.getServer()).snapshot());
    }

    private static NpcBase requireNpc(CommandSourceStack source, Entity entity) {
        if (entity instanceof NpcBase npc) return npc;
        source.sendFailure(Component.literal("目标不是 Sagadyssey NPC。"));
        return null;
    }
}
