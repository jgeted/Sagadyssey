package com.jgeted.sagadyssey.social.settlement;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 世界级聚落与建筑索引。所有写入都必须发生在服务器主线程。 */
public final class SettlementRegistryData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_settlements";
    private static final int AUTO_ATTACH_RADIUS = 128;
    private static final Factory<SettlementRegistryData> FACTORY =
            new Factory<>(SettlementRegistryData::new, SettlementRegistryData::load);

    private final Map<UUID, SettlementRecord> settlements = new HashMap<>();
    private final Map<String, BuildingRecord> buildings = new HashMap<>();

    public static SettlementRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public List<SettlementRecord> allSettlements() {
        return settlements.values().stream()
                .sorted(Comparator.comparing(SettlementRecord::name)).toList();
    }

    @Nullable
    public SettlementRecord byId(UUID settlementId) { return settlements.get(settlementId); }

    public long buildingCount(UUID settlementId) {
        return buildings.values().stream().filter(b -> settlementId.equals(b.settlementId())).count();
    }

    @Nullable
    public BuildingRecord building(String key) { return buildings.get(key); }
    public List<BuildingRecord> buildings() { return List.copyOf(buildings.values()); }

    public BuildingRecord buildingAt(String dimension, BlockPos position, String structureId) {
        BuildingRecord legacy = buildings.get(buildingKey(dimension, position, structureId));
        if (legacy != null) return legacy;
        // Natural keys also carry the parent structure identity and rotation. Preserve the old query
        // when its coordinates identify exactly one building; ambiguous queries need the full key.
        var matches = buildings.values().stream().filter(building -> building.dimension().equals(dimension)
                && building.position().equals(position) && building.structureId().equals(structureId)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    @Nullable
    public SettlementRecord nearest(String dimension, BlockPos pos, double maxDistance) {
        double maxSq = maxDistance * maxDistance;
        return settlements.values().stream()
                .filter(record -> record.dimension().equals(dimension))
                .filter(record -> record.center().distSqr(pos) <= maxSq)
                .min(Comparator.comparingDouble(record -> record.center().distSqr(pos)))
                .orElse(null);
    }

    public SettlementRecord create(MinecraftServer server, String name, SettlementType type,
                                   String dimension, BlockPos center, int radius, String factionId,
                                   @Nullable UUID founderCharacterId, long gameTime) {
        return createWithId(server, UUID.randomUUID(), name, type, dimension, center, radius,
                factionId, founderCharacterId, gameTime);
    }

    /** 场景部署使用确定性 ID，服务器在保存中断后重试也不会重复登记同一聚落。 */
    public SettlementRecord createWithId(MinecraftServer server, UUID id, String name, SettlementType type,
                                         String dimension, BlockPos center, int radius, String factionId,
                                         @Nullable UUID founderCharacterId, long gameTime) {
        SettlementRecord existing = settlements.get(id);
        if (existing != null) return existing;
        SettlementRecord record = new SettlementRecord(id, SettlementRecord.cleanName(name),
                type, dimension, center.immutable(), Math.max(16, radius),
                SettlementRecord.normalizeFaction(factionId), founderCharacterId, gameTime);
        settlements.put(record.id(), record);
        attachOrphanBuildings(record);
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "settlement_founded", record.id(),
                record.name(), record.type().name(), 45);
        return record;
    }

    public void registerGeneratedBuilding(MinecraftServer server, String dimension, BlockPos pos,
                                          String structureId, String category, String size,
                                          long gameTime) {
        registerBuilding(server, dimension, pos, structureId, category, size, null, gameTime);
    }

    /** Called after a physical construction has passed its completion checks. */
    public void registerConstructedBuilding(MinecraftServer server,String dimension,BlockPos pos,
            String structureId,String category,String size,UUID settlementId,long gameTime){
        if(!settlements.containsKey(settlementId))throw new IllegalArgumentException("Unknown construction settlement");
        registerBuilding(server,dimension,pos,structureId,category,size,settlementId,gameTime);
    }

    /** 固定场景可直接指定所属聚落，避免相邻聚落的最近点判断发生误归属。 */
    public void registerScenarioBuilding(MinecraftServer server, String dimension, BlockPos pos,
                                         String structureId, String category, String size,
                                         UUID settlementId, long gameTime) {
        registerBuilding(server, dimension, pos, structureId, category, size, settlementId, gameTime);
    }

    /** Register an actually placed building with explicit structure ownership, never nearest-neighbor merging. */
    public boolean registerPlacedVillage(com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding placed,
                                         long gameTime) {
        String key = placed.buildingKey();
        BuildingRecord existing = buildings.get(key);
        if (existing != null) {
            if (!placed.settlementId().equals(existing.settlementId())) {
                throw new IllegalStateException("Natural building identity has conflicting settlement ownership");
            }
            return false;
        }
        settlements.computeIfAbsent(placed.settlementId(), id -> new SettlementRecord(id,
                generatedName(SettlementType.VILLAGE, placed.settlementCenter()), SettlementType.VILLAGE,
                placed.dimension(), placed.settlementCenter(), AUTO_ATTACH_RADIUS,
                "sagadyssey:kingdom", null, gameTime));
        buildings.put(key, new BuildingRecord(key, placed.structureId(), "house", "small",
                placed.dimension(), placed.buildingOrigin(), placed.settlementId(), gameTime));
        setDirty();
        return true;
    }

    private void registerBuilding(MinecraftServer server, String dimension, BlockPos pos,
                                  String structureId, String category, String size,
                                  @Nullable UUID explicitSettlementId, long gameTime) {
        String key = buildingKey(dimension, pos, structureId);
        if (buildings.containsKey(key)) return;

        SettlementRecord settlement = explicitSettlementId == null ? nearest(dimension, pos, AUTO_ATTACH_RADIUS)
                : settlements.get(explicitSettlementId);
        if (settlement == null && isSettlementAnchor(category)) {
            SettlementType type = SettlementType.fromBuildingCategory(category);
            String faction = switch (type) {
                case CAMP -> "sagadyssey:bandit";
                case SANCTUARY -> "sagadyssey:church";
                default -> "sagadyssey:kingdom";
            };
            UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            settlement = new SettlementRecord(id, generatedName(type, pos), type, dimension,
                    pos.immutable(), AUTO_ATTACH_RADIUS, faction, null, gameTime);
            settlements.put(id, settlement);
            attachOrphanBuildings(settlement);
            WorldHistoryData.get(server).append(gameTime, "settlement_discovered", id,
                    settlement.name(), structureId, 30);
        }

        BuildingRecord building = new BuildingRecord(key, structureId, safe(category), safe(size),
                dimension, pos.immutable(), settlement == null ? null : settlement.id(), gameTime);
        buildings.put(key, building);
        setDirty();
    }

    private void attachOrphanBuildings(SettlementRecord settlement) {
        double radiusSq = (double) settlement.radius() * settlement.radius();
        buildings.replaceAll((key, building) -> building.settlementId() == null
                && building.dimension().equals(settlement.dimension())
                && building.position().distSqr(settlement.center()) <= radiusSq
                ? building.assignedTo(settlement.id()) : building);
    }

    private static boolean isSettlementAnchor(String category) {
        return "village".equalsIgnoreCase(category)
                || "camp".equalsIgnoreCase(category)
                || "church".equalsIgnoreCase(category);
    }

    private static String generatedName(SettlementType type, BlockPos pos) {
        String prefix = switch (type) {
            case VILLAGE -> "边境村落";
            case CAMP -> "灰旗营地";
            case SANCTUARY -> "雾月圣所";
            case FORT -> "边境堡";
            case TOWN -> "集镇";
            case HAMLET -> "小村";
        };
        return prefix + " " + pos.getX() + "," + pos.getZ();
    }

    private static String buildingKey(String dimension, BlockPos pos, String structureId) {
        return dimension + "|" + pos.asLong() + "|" + structureId;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag settlementList = new ListTag();
        settlements.values().forEach(record -> settlementList.add(record.save()));
        tag.put("Settlements", settlementList);
        ListTag buildingList = new ListTag();
        buildings.values().forEach(record -> buildingList.add(record.save()));
        tag.put("Buildings", buildingList);
        return tag;
    }

    static SettlementRegistryData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementRegistryData data = new SettlementRegistryData();
        ListTag settlementList = MinecraftApi.listOfType(tag, "Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlementList.size(); i++) {
            SettlementRecord record = SettlementRecord.load(settlementList.getCompoundOrEmpty(i));
            data.settlements.put(record.id(), record);
        }
        ListTag buildingList = MinecraftApi.listOfType(tag, "Buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            BuildingRecord record = BuildingRecord.load(buildingList.getCompoundOrEmpty(i));
            data.buildings.put(record.key(), record);
        }
        return data;
    }
}
