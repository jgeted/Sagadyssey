package com.jgeted.sagadyssey.social.logistics;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3f;

import java.util.UUID;

/** 真实仓库、库存报告和运输需求指令。 */
public final class LogisticsCommand {
    private LogisticsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("logistics")
                        .then(Commands.literal("warehouses")
                                .executes(ctx -> listWarehouses(ctx.getSource())))
                        .then(Commands.literal("locate")
                                .then(Commands.argument("warehouse_id", StringArgumentType.word())
                                        .executes(ctx -> locateWarehouse(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "warehouse_id")))))
                        .then(Commands.literal("stock")
                                .executes(ctx -> stock(ctx.getSource())))
                        .then(Commands.literal("orders")
                                .executes(ctx -> listOrders(ctx.getSource())))
                        .then(Commands.literal("register")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> register(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("order")
                                .then(Commands.argument("source_warehouse", StringArgumentType.word())
                                        .then(Commands.argument("destination_warehouse", StringArgumentType.word())
                                                .then(Commands.argument("item", StringArgumentType.word())
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(
                                                                        1, LogisticsData.MAX_ORDER_ITEMS))
                                                                .executes(ctx -> createOrder(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "source_warehouse"),
                                                                        StringArgumentType.getString(ctx, "destination_warehouse"),
                                                                        StringArgumentType.getString(ctx, "item"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"))))))))));

        var cancelCommand = Commands.literal("cancel")
                .then(Commands.argument("order_id", StringArgumentType.word())
                        .executes(ctx -> cancelOrder(ctx.getSource(),
                                StringArgumentType.getString(ctx, "order_id"))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("logistics").then(cancelCommand)));
    }

    private static int register(CommandSourceStack source, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(6.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("请看向六格以内的箱子、木桶或其他容器。"));
            return 0;
        }
        var blockEntity = player.serverLevel().getBlockEntity(blockHit.getBlockPos());
        if (!WarehouseContainers.isSupported(blockEntity)) {
            source.sendFailure(Component.literal("仓库目前只支持箱子、木桶和潜影盒。"));
            return 0;
        }
        String dimension = player.serverLevel().dimension().location().toString();
        SettlementRegistryData settlements = SettlementRegistryData.get(source.getServer());
        SettlementRecord settlement = settlements.nearest(dimension, blockHit.getBlockPos(), 256.0D);
        if (settlement == null) {
            source.sendFailure(Component.literal("容器附近没有已登记聚落，请先登记聚落。"));
            return 0;
        }
        var character = CharacterRegistry.ensureCurrent(player);
        if (!canManageSettlement(source, settlement, character.id())) {
            source.sendFailure(Component.literal("你不是该聚落的建立者、领主或所属阵营君主。"));
            return 0;
        }
        LogisticsData logistics = LogisticsData.get(source.getServer());
        if (logistics.warehouseCount(settlement.id()) >= LogisticsData.MAX_WAREHOUSES_PER_SETTLEMENT) {
            source.sendFailure(Component.literal("该聚落已达到 64 个登记仓库上限。"));
            return 0;
        }
        WarehouseRecord warehouse = logistics.registerWarehouse(
                source.getServer(), settlement.id(), name, dimension, blockHit.getBlockPos(),
                character.id(), player.serverLevel().getGameTime());
        source.sendSuccess(() -> Component.literal("§a已登记仓库 " + warehouse.name()
                + "，ID=" + warehouse.id() + "，所属聚落=" + settlement.name()), true);
        return 1;
    }

    private static int listWarehouses(CommandSourceStack source) {
        var warehouses = LogisticsData.get(source.getServer()).warehouses();
        if (warehouses.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前没有已登记仓库。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6已登记仓库（" + warehouses.size() + "）："), false);
        for (WarehouseRecord warehouse : warehouses) {
            source.sendSuccess(() -> Component.literal("§e" + warehouse.name() + " §7ID=" + warehouse.id()
                    + "，聚落=" + warehouse.settlementId() + "，位置="
                    + warehouse.dimension() + " " + warehouse.position().toShortString()), false);
        }
        return warehouses.size();
    }

    private static int locateWarehouse(CommandSourceStack source, String id) {
        UUID warehouseId;
        try {
            warehouseId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("仓库 ID 格式无效。"));
            return 0;
        }
        WarehouseRecord warehouse = LogisticsData.get(source.getServer()).warehouse(warehouseId);
        if (warehouse == null) {
            source.sendFailure(Component.literal("找不到该仓库。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a" + warehouse.name() + " §7位于 "
                + warehouse.dimension() + " " + warehouse.position().toShortString()), false);
        if (!source.getLevel().dimension().location().toString().equals(warehouse.dimension())) return 1;
        DustParticleOptions particle = new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.35F), 1.0F);
        for (int height = 0; height <= 12; height++) {
            source.getLevel().sendParticles(particle, warehouse.position().getX() + 0.5D,
                    warehouse.position().getY() + 0.5D + height, warehouse.position().getZ() + 0.5D,
                    3, 0.15D, 0.1D, 0.15D, 0.0D);
        }
        return 1;
    }

    private static int stock(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String dimension = player.serverLevel().dimension().location().toString();
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer())
                .nearest(dimension, player.blockPosition(), 2048.0D);
        if (settlement == null) {
            source.sendFailure(Component.literal("附近没有已登记聚落。"));
            return 0;
        }
        StockSnapshot snapshot = LogisticsData.get(source.getServer())
                .stockForSettlement(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal("§6" + settlement.name() + " 库存：§7可读仓库="
                + snapshot.readableWarehouses() + "，未加载/失效=" + snapshot.unavailableWarehouses()), false);
        if (snapshot.items().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7没有读到物资。"), false);
            return snapshot.readableWarehouses();
        }
        snapshot.items().forEach((item, count) -> source.sendSuccess(
                () -> Component.literal("  §e" + item + " §f× " + count), false));
        return snapshot.items().size();
    }

    private static int createOrder(CommandSourceStack source, String sourceId, String destinationId,
                                   String itemId, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID from;
        UUID to;
        try {
            from = UUID.fromString(sourceId);
            to = UUID.fromString(destinationId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("仓库 ID 格式无效。"));
            return 0;
        }
        var character = CharacterRegistry.ensureCurrent(player);
        LogisticsData logistics = LogisticsData.get(source.getServer());
        WarehouseRecord fromWarehouse = logistics.warehouse(from);
        WarehouseRecord toWarehouse = logistics.warehouse(to);
        SettlementRecord fromSettlement = fromWarehouse == null ? null
                : SettlementRegistryData.get(source.getServer()).byId(fromWarehouse.settlementId());
        SettlementRecord toSettlement = toWarehouse == null ? null
                : SettlementRegistryData.get(source.getServer()).byId(toWarehouse.settlementId());
        if (fromSettlement == null || toSettlement == null
                || !canManageSettlement(source, fromSettlement, character.id())
                || !canManageSettlement(source, toSettlement, character.id())) {
            source.sendFailure(Component.literal("你必须同时拥有起点和终点聚落的物流管理权。"));
            return 0;
        }
        LogisticsOrderRecord order = logistics.createOrder(from, to,
                itemId, count, character.id(), player.serverLevel().getGameTime());
        if (order == null) {
            source.sendFailure(Component.literal("无法创建订单：请检查仓库、物品 ID 和数量。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a已创建实体运输订单 " + order.id()
                + "。它不会瞬移物资，必须由运输者执行。"), true);
        return 1;
    }

    private static int listOrders(CommandSourceStack source) {
        LogisticsData data = LogisticsData.get(source.getServer());
        var orders = data.orders();
        if (source.getEntity() instanceof ServerPlayer player) {
            UUID characterId = CharacterRegistry.currentId(player);
            if (characterId == null) {
                source.sendFailure(Component.literal("当前没有已绑定人物；不查询或接管其他人物的订单。"));
                return 0;
            }
            orders = orders.stream()
                    .filter(order -> characterId.equals(order.createdByCharacter()))
                    .toList();
        }
        if (orders.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前角色没有运输订单。"), false);
            return 0;
        }
        int count = orders.size();
        source.sendSuccess(() -> Component.literal("§6当前角色的运输订单（" + count + "）："), false);
        for (LogisticsOrderRecord order : orders) {
            WarehouseRecord from = data.warehouse(order.sourceWarehouseId());
            WarehouseRecord to = data.warehouse(order.destinationWarehouseId());
            String route = (from == null ? order.sourceWarehouseId().toString() : from.name())
                    + " → " + (to == null ? order.destinationWarehouseId().toString() : to.name());
            String cargo = order.inTransitCount() > 0 ? "，在途=" + order.inTransitCount() : "";
            source.sendSuccess(() -> Component.literal("§e" + shortId(order.id()) + " §7["
                    + statusName(order.status()) + "] §f" + order.itemId() + " §b"
                    + order.deliveredCount() + "/" + order.requestedCount() + cargo
                    + " §8· " + route), false);
        }
        return orders.size();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String statusName(LogisticsOrderStatus status) {
        return switch (status) {
            case PLANNED -> "等待";
            case IN_PROGRESS -> "运输中";
            case DELIVERED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    private static int cancelOrder(CommandSourceStack source, String orderId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("订单 ID 格式无效。"));
            return 0;
        }
        var character = CharacterRegistry.ensureCurrent(player);
        boolean cancelled = LogisticsData.get(source.getServer()).cancelOrder(id, character.id(),
                player.serverLevel().getGameTime());
        if (!cancelled) {
            source.sendFailure(Component.literal("无法取消：订单不存在、不属于当前角色，或仍有货物在途。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a订单已安全取消，没有删除任何实体物资。"), true);
        return 1;
    }

    private static boolean canManageSettlement(CommandSourceStack source, SettlementRecord settlement,
                                               UUID characterId) {
        if (source.hasPermission(2) || characterId.equals(settlement.founderCharacterId())) return true;
        PoliticalStateData politics = PoliticalStateData.get(source.getServer());
        return politics.canGrantTitle(settlement.factionId(), settlement.id(), characterId);
    }
}
