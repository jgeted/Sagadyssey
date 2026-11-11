package com.jgeted.sagadyssey.social.economy;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.world.WorldDynamicsData;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 每个游戏日从真实仓库消耗口粮，并在缺粮时生成可执行的实体运输需求。 */
public final class SettlementEconomyEvents {
    private static final Map<Item, Integer> FOOD_VALUES = NpcFoodItems.rationValues();
    private static final Map<MinecraftServer, java.util.ArrayDeque<UUID>> PENDING = new java.util.WeakHashMap<>();

    private SettlementEconomyEvents() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // gameTime 单调递增，避免 /time set 导致同一批口粮被反复结算。
        long day = server.overworld().getGameTime() / 24000L;
        SettlementEconomyData economy = SettlementEconomyData.get(server);
        long started=System.nanoTime();
        var pending=PENDING.computeIfAbsent(server,s->new java.util.ArrayDeque<>());
        // Refill only after the previous pass; unloaded settlements remain due, not magically fed.
        if(pending.isEmpty()&&server.overworld().getGameTime()%20==0)
            SettlementRegistryData.get(server).allSettlements().forEach(s->pending.add(s.id()));
        int processed=0;
        while(!pending.isEmpty()&&processed++<4&&System.nanoTime()-started<1_000_000) {
            SettlementRecord settlement=SettlementRegistryData.get(server).byId(pending.removeFirst());
            if(settlement==null)continue;
            if (economy.shouldProcess(settlement.id(), day)) {
                processSettlement(server, economy, settlement, day);
            }
        }
        com.jgeted.sagadyssey.worldsim.lod.LodMetrics.record(server,com.jgeted.sagadyssey.worldsim.lod.LodMetrics.Kind.SETTLEMENT,System.nanoTime()-started);
    }
    public static int backlog(MinecraftServer server){var q=PENDING.get(server);return q==null?0:q.size();}

    private static void processSettlement(MinecraftServer server, SettlementEconomyData economy,
                                          SettlementRecord settlement, long day) {
        ServerLevel level = level(server, settlement.dimension());
        if (level == null || !level.hasChunkAt(settlement.center())) return;
        int radius = settlement.radius();
        AABB area = new AABB(settlement.center()).inflate(radius);
        NpcLifeData lifeData = NpcLifeData.get(server);
        var residents = level.getEntitiesOfClass(NpcBase.class, area,
                npc -> npc.isAlive()
                        && npc.blockPosition().distSqr(settlement.center()) <= (double) radius * radius
                        && isRegisteredResident(lifeData, settlement, npc));
        int population = residents.size();
        if (population == 0) {
            economy.update(new SettlementEconomyRecord(settlement.id(), 0, 0, 0, 0, day));
            return;
        }

        // Natural households use individually receipted meals; do not charge them a second daily ration.
        var rationResidents = residents.stream().filter(n -> !com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.individualRations(server,n.getCharacterId())).toList();
        int required = rationResidents.size() * 2 * WorldDynamicsData.get(server).foodMultiplier(settlement.id());
        int consumed = consumePhysicalFood(level, LogisticsData.get(server), settlement.id(), required);
        distributeRations(server, settlement, rationResidents, consumed,
                2 * WorldDynamicsData.get(server).foodMultiplier(settlement.id()), day);
        SettlementEconomyRecord previous = economy.record(settlement.id());
        int shortageDays = consumed < required ? (previous == null ? 1 : previous.shortageDays() + 1) : 0;
        economy.update(new SettlementEconomyRecord(settlement.id(), population, required,
                consumed, shortageDays, day));

        if (consumed < required) {
            residents.forEach(npc -> npc.adjustMoral(-10));
            if (shortageDays == 1 || shortageDays % 3 == 0) {
                WorldHistoryData.get(server).append(level.getGameTime(), "settlement_food_shortage",
                        settlement.id(), settlement.name(), (required - consumed) + " food", 45 + shortageDays * 3);
                notifyNearbyPlayers(level, settlement, "§c" + settlement.name() + " 缺粮：今日口粮 "
                        + consumed + "/" + required);
            }
            requestFoodTransport(server, settlement, required - consumed);
        } else {
            rationResidents.forEach(npc -> npc.adjustMoral(2));
        }
    }

    private static boolean isRegisteredResident(NpcLifeData lifeData, SettlementRecord settlement,
                                                NpcBase npc) {
        var life = lifeData.record(npc.getCharacterId());
        return life != null
                && settlement.id().equals(com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.individualRations(MinecraftApi.server(npc),npc.getCharacterId())?com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.residentSettlement(MinecraftApi.server(npc),npc.getCharacterId()):life.originSettlementId())
                && settlement.dimension().equals(life.dimension());
    }

    /**
     * 仓库已经在日结中真实扣过物品，这里只满足对应居民的需求，不再从箱子取第二次。
     * 缺粮时按“UUID 与日期”的稳定轮换选择获得完整口粮的人，避免永远饿到同一批 NPC。
     */
    private static void distributeRations(MinecraftServer server, SettlementRecord settlement,
                                           List<NpcBase> residents, int consumed,
                                           int rationCost, long day) {
        if (rationCost <= 0 || consumed <= 0) return;
        int fedCount = Math.min(residents.size(), consumed / rationCost);
        if (consumed >= residents.size() * rationCost) fedCount = residents.size();
        List<NpcBase> order = residents.stream().sorted(Comparator
                .comparingInt((NpcBase npc) -> npc.getCharacterId().hashCode() ^ Long.hashCode(day))
                .thenComparing(npc -> npc.getCharacterId().toString())).toList();
        NpcLifeData lifeData = NpcLifeData.get(server);
        long now = server.overworld().getGameTime();
        for (int index = 0; index < fedCount; index++) {
            NpcBase npc = order.get(index);
            lifeData.feed(npc.getCharacterId(), 420,
                    "聚落日结口粮：" + settlement.name(), now);
        }
    }

    private static int consumePhysicalFood(ServerLevel level, LogisticsData logistics,
                                           UUID settlementId, int required) {
        int consumed = 0;
        for (WarehouseRecord warehouse : logistics.warehouses()) {
            if (!settlementId.equals(warehouse.settlementId()) || !warehouse.dimension().equals(level.dimension().identifier().toString())
                    || !level.hasChunkAt(warehouse.position())) continue;
            Container container = WarehouseContainers.asContainer(level.getBlockEntity(warehouse.position()));
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize() && consumed < required; slot++) {
                ItemStack stack = container.getItem(slot);
                Integer value = FOOD_VALUES.get(stack.getItem());
                if (value == null || stack.isEmpty()) continue;
                int items = Math.min(stack.getCount(), (int) Math.ceil((required - consumed) / (double) value));
                RightsStateData rights = RightsStateData.get(level.getServer());
                SubjectRef settlement = SubjectRef.institution(settlementId);
                rights.ensureContext(warehouse.id(), warehouse.dimension() + ':'
                                + warehouse.position().asLong(), settlement, settlement,
                        AccessPolicy.INSTITUTION);
                ConsumptionService.Result result = new ConsumptionService(rights).consume(
                        warehouse.id(), container, settlement, ItemFingerprint.of(stack), items,
                        "settlement_rations:" + settlementId + ':' + level.getGameTime(),
                        level.getGameTime());
                if (result.successful()) consumed += result.consumed() * value;
            }
            if (consumed >= required) break;
        }
        return Math.min(consumed, required);
    }

    private static void requestFoodTransport(MinecraftServer server, SettlementRecord destination,
                                             int deficit) {
        UUID steward = logisticsSteward(server, destination);
        if (steward == null) return;
        LogisticsData logistics = LogisticsData.get(server);
        WarehouseRecord target = logistics.warehouses().stream()
                .filter(w -> destination.id().equals(w.settlementId())).findFirst().orElse(null);
        if (target == null) return;

        for (SettlementRecord sourceSettlement : SettlementRegistryData.get(server).allSettlements()) {
            if (sourceSettlement.id().equals(destination.id())
                    || !sourceSettlement.factionId().equals(destination.factionId())) continue;
            var stock = logistics.stockForSettlement(server, sourceSettlement.id()).items();
            for (Map.Entry<Item, Integer> staple : FOOD_VALUES.entrySet()) {
                String itemId = BuiltInRegistries.ITEM.getKey(staple.getKey()).toString();
                int available = stock.getOrDefault(itemId, 0);
                if (available < 8 || hasOpenOrder(logistics, target.id(), itemId, steward)) continue;
                WarehouseRecord source = findWarehouseWithItem(server, logistics, sourceSettlement.id(), staple.getKey());
                if (source == null) continue;
                int requested = Math.min(LogisticsData.MAX_ORDER_ITEMS,
                        Math.min(Math.max(4, deficit), available / 2));
                logistics.createOrder(source.id(), target.id(), itemId, requested,
                        steward, server.overworld().getGameTime());
                return;
            }
        }
    }

    /** 当前领主负责聚落物流；无领主时依次回退到君主和仍在世的建立者。 */
    @Nullable
    private static UUID logisticsSteward(MinecraftServer server, SettlementRecord settlement) {
        PoliticalStateData politics = PoliticalStateData.get(server);
        UUID candidate = politics.titleHolder(settlement.id());
        if (candidate == null) candidate = politics.ruler(settlement.factionId());
        if (candidate == null) candidate = settlement.founderCharacterId();
        if (candidate == null) return null;
        var record = CharacterRegistryData.get(server).byId(candidate);
        return record != null && record.alive() ? candidate : null;
    }

    private static boolean hasOpenOrder(LogisticsData data, UUID destination, String itemId, UUID characterId) {
        return data.orders().stream().anyMatch(order -> destination.equals(order.destinationWarehouseId())
                && itemId.equals(order.itemId()) && characterId.equals(order.createdByCharacter())
                && (order.status() == LogisticsOrderStatus.PLANNED
                    || order.status() == LogisticsOrderStatus.IN_PROGRESS));
    }

    @Nullable
    private static WarehouseRecord findWarehouseWithItem(MinecraftServer server, LogisticsData logistics,
                                                          UUID settlementId, Item item) {
        for (WarehouseRecord warehouse : logistics.warehouses()) {
            if (!settlementId.equals(warehouse.settlementId())) continue;
            ServerLevel level = level(server, warehouse.dimension());
            if (level == null || !level.hasChunkAt(warehouse.position())) continue;
            Container container = WarehouseContainers.asContainer(level.getBlockEntity(warehouse.position()));
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(item)) return warehouse;
            }
        }
        return null;
    }

    private static void notifyNearbyPlayers(ServerLevel level, SettlementRecord settlement, String message) {
        double rangeSq = (double) settlement.radius() * settlement.radius() * 4.0D;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(settlement.center()) <= rangeSq) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
