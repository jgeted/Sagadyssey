package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdRole;
import com.jgeted.sagadyssey.social.settlement.SettlementType;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 固定地理场景清单。坐标全部相对玩家执行生成命令时的区块原点。 */
public final class FixedScenarioDefinition {
    public enum TerrainKind {
        ROAD, PLAZA, FARMLAND_MATURE, FARMLAND_GROWING, FARMLAND_EMPTY,
        WATER, FENCE, FARM_GATE, COMPOSTER, STONE, COAL_ORE, IRON_ORE,
        FURNACE, BLAST_FURNACE, ANVIL, SMITHING_TABLE, BELL
    }

    public record TerrainBlock(int x, int y, int z, TerrainKind kind) {
        public TerrainBlock(int x, int z, TerrainKind kind) { this(x, 0, z, kind); }
    }
    public record Placement(String templateId, int x, int z, Rotation rotation, String settlementKey) { }
    public record SettlementSpec(String key, String name, SettlementType type, int x, int z,
                                 int radius, String factionId) { }
    public record FarmSpec(String key, String settlementKey, int x, int z, int size) { }
    public record WorkZoneSpec(String key, int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ) { }
    public record Workplace(int x, int z) { }
    public record WarehouseSpec(String settlementKey, String name, int x, int z,
                                Map<String, Integer> initialStock, boolean chest) {
        public WarehouseSpec(String settlementKey, String name, int x, int z,
                             Map<String, Integer> initialStock) {
            this(settlementKey, name, x, z, initialStock, false);
        }
    }
    public record NpcSpec(String name, NpcProfession profession, NpcSocialClass socialClass,
                          NpcOffice office, String factionId, String settlementKey,
                          int x, int z, NpcCommand command,
                          @Nullable String workZoneKey, @Nullable Workplace workplace) {
        public NpcSpec(String name, NpcProfession profession, NpcSocialClass socialClass,
                       NpcOffice office, String factionId, String settlementKey,
                       int x, int z, NpcCommand command) {
            this(name, profession, socialClass, office, factionId, settlementKey,
                    x, z, command, null, null);
        }

        public NpcSpec(String name, NpcProfession profession, NpcSocialClass socialClass,
                       NpcOffice office, String factionId, String settlementKey,
                       int x, int z, NpcCommand command, @Nullable String workZoneKey) {
            this(name, profession, socialClass, office, factionId, settlementKey,
                    x, z, command, workZoneKey, null);
        }
    }
    public record HouseholdSpec(String key, String name, String settlementKey,
                                int headIndex, int memberIndex, HouseholdRole memberRole,
                                Map<String, Integer> initialStock) { }

    private static final Map<String, FixedScenarioDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        register(smallRealm());
        register(blacksmithLab());
        register(farmerLab());
        register(workerLab());
        register(traderLab());
        register(guardLab());
        register(medicLab());
        register(bardLab());
        register(householdLab());
    }

    private final String id;
    private final String displayName;
    private final String description;
    private final int revision;
    private final int radius;
    private final List<TerrainBlock> terrain;
    private final List<Placement> placements;
    private final List<SettlementSpec> settlements;
    private final List<FarmSpec> farms;
    private final List<WorkZoneSpec> workZones;
    private final List<WarehouseSpec> warehouses;
    private final List<NpcSpec> npcs;
    private final List<HouseholdSpec> households;

    private FixedScenarioDefinition(String id, String displayName, String description, int revision, int radius,
                                     List<TerrainBlock> terrain, List<Placement> placements,
                                     List<SettlementSpec> settlements, List<FarmSpec> farms,
                                     List<WorkZoneSpec> workZones, List<WarehouseSpec> warehouses,
                                     List<NpcSpec> npcs) {
        this(id, displayName, description, revision, radius, terrain, placements, settlements,
                farms, workZones, warehouses, npcs, List.of());
    }

    private FixedScenarioDefinition(String id, String displayName, String description, int revision, int radius,
                                     List<TerrainBlock> terrain, List<Placement> placements,
                                     List<SettlementSpec> settlements, List<FarmSpec> farms,
                                     List<WorkZoneSpec> workZones, List<WarehouseSpec> warehouses,
                                     List<NpcSpec> npcs, List<HouseholdSpec> households) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.revision = revision;
        this.radius = radius;
        this.terrain = List.copyOf(terrain);
        this.placements = List.copyOf(placements);
        this.settlements = List.copyOf(settlements);
        this.farms = List.copyOf(farms);
        this.workZones = List.copyOf(workZones);
        this.warehouses = List.copyOf(warehouses);
        this.npcs = List.copyOf(npcs);
        this.households = List.copyOf(households);
        validate();
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public int revision() { return revision; }
    public int radius() { return radius; }
    public List<TerrainBlock> terrain() { return terrain; }
    public List<Placement> placements() { return placements; }
    public List<SettlementSpec> settlements() { return settlements; }
    public List<FarmSpec> farms() { return farms; }
    public List<WorkZoneSpec> workZones() { return workZones; }
    public List<WarehouseSpec> warehouses() { return warehouses; }
    public List<NpcSpec> npcs() { return npcs; }
    public List<HouseholdSpec> households() { return households; }

    @Nullable
    public FarmSpec farm(String key) {
        return farms.stream().filter(farm -> farm.key().equals(key)).findFirst().orElse(null);
    }

    @Nullable
    public WorkZoneSpec workZone(String key) {
        return workZones.stream().filter(zone -> zone.key().equals(key)).findFirst().orElse(null);
    }

    @Nullable
    public SettlementSpec settlement(String key) {
        return settlements.stream().filter(settlement -> settlement.key().equals(key)).findFirst().orElse(null);
    }

    public int totalWorkUnits() {
        return settlements.size() + terrain.size() + placements.size() + warehouses.size() + npcs.size() + 1;
    }

    public static FixedScenarioDefinition get(String id) {
        return id == null ? null : DEFINITIONS.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<FixedScenarioDefinition> all() { return List.copyOf(DEFINITIONS.values()); }

    private static void register(FixedScenarioDefinition definition) {
        DEFINITIONS.put(definition.id, definition);
    }

    private static FixedScenarioDefinition smallRealm() {
        List<FarmSpec> farms = List.of(
                new FarmSpec("wheat_west", "farmland", 224, 82, 9),
                new FarmSpec("wheat_east", "farmland", 266, 42, 9)
        );
        List<WorkZoneSpec> workZones = List.of(
                new WorkZoneSpec("wheat_west", 224, -1, 82, 232, 0, 90),
                new WorkZoneSpec("wheat_east", 266, -1, 42, 274, 0, 50),
                new WorkZoneSpec("mine_yard", 145, -1, 23, 155, 5, 33)
        );

        List<SettlementSpec> settlements = List.of(
                new SettlementSpec("capital", "河谷镇", SettlementType.TOWN,
                        0, 0, 112, "sagadyssey:kingdom"),
                new SettlementSpec("farmland", "麦田村", SettlementType.VILLAGE,
                        210, 30, 96, "sagadyssey:kingdom"),
                new SettlementSpec("mine", "赤岩矿场", SettlementType.VILLAGE,
                        158, 30, 40, "sagadyssey:kingdom"),
                new SettlementSpec("fort", "北境堡", SettlementType.FORT,
                        0, -210, 96, "sagadyssey:kingdom"),
                new SettlementSpec("bandits", "灰旗营地", SettlementType.CAMP,
                        -215, 15, 80, "sagadyssey:bandit"),
                new SettlementSpec("sanctuary", "雾月圣所", SettlementType.SANCTUARY,
                        0, 210, 104, "sagadyssey:church")
        );

        List<Placement> placements = List.of(
                new Placement("vilg_1", -18, -18, Rotation.NONE, "capital"),
                new Placement("forge", -72, -15, Rotation.CLOCKWISE_90, "capital"),
                new Placement("stable", 45, -12, Rotation.NONE, "capital"),
                new Placement("wallgate", -20, 70, Rotation.NONE, "capital"),
                new Placement("house_cottage_large", -76, 48, Rotation.CLOCKWISE_90, "capital"),
                new Placement("house_cottage_medium", 42, 48, Rotation.COUNTERCLOCKWISE_90, "capital"),

                new Placement("vilg_2", 185, 0, Rotation.NONE, "farmland"),
                new Placement("house_cottage_small", 252, 2, Rotation.CLOCKWISE_90, "farmland"),
                new Placement("house_cottage_medium", 188, 64, Rotation.NONE, "farmland"),
                new Placement("stable", 248, 62, Rotation.CLOCKWISE_180, "farmland"),

                new Placement("wallgate", -20, -166, Rotation.CLOCKWISE_180, "fort"),
                new Placement("watchtower_stone_large", -62, -230, Rotation.NONE, "fort"),
                new Placement("house_cottage_large", 22, -232, Rotation.CLOCKWISE_180, "fort"),

                new Placement("camp_bandit_small", -240, -8, Rotation.NONE, "bandits"),
                new Placement("watchtower_stone_large", -183, 38, Rotation.CLOCKWISE_90, "bandits"),

                new Placement("chapel_misty_moon", -30, 165, Rotation.NONE, "sanctuary")
        );

        List<WarehouseSpec> warehouses = List.of(
                new WarehouseSpec("capital", "河谷镇粮仓", 22, 28,
                        Map.of("minecraft:bread", 48, "minecraft:wheat", 64,
                                "minecraft:coal", 32, "minecraft:iron_ingot", 24)),
                new WarehouseSpec("farmland", "麦田村谷仓", 215, 82,
                        Map.of("minecraft:wheat", 96, "minecraft:bread", 24,
                                "minecraft:wheat_seeds", 64, "minecraft:carrot", 32)),
                new WarehouseSpec("fort", "北境军需库", 10, -198,
                        Map.of("minecraft:bread", 32, "minecraft:arrow", 64,
                                "minecraft:iron_ingot", 32, "minecraft:coal", 24)),
                new WarehouseSpec("bandits", "灰旗赃物桶", -208, 24,
                        Map.of("minecraft:bread", 20, "minecraft:coal", 16, "minecraft:arrow", 32)),
                new WarehouseSpec("sanctuary", "圣所济贫仓", 36, 210,
                        Map.of("minecraft:bread", 36, "minecraft:wheat", 32,
                                "minecraft:honey_bottle", 12, "minecraft:golden_apple", 2)),
                new WarehouseSpec("mine", "赤岩矿业市场", 160, 30,
                        Map.of("minecraft:coal", 64, "minecraft:raw_iron", 32,
                                "minecraft:iron_ingot", 8, "minecraft:emerald", 24,
                                "minecraft:bread", 4), true),
                new WarehouseSpec("farmland", "麦田粮食市场", 205, 30,
                        Map.of("minecraft:wheat", 96, "minecraft:bread", 32,
                                "minecraft:wheat_seeds", 64, "minecraft:emerald", 32,
                                "minecraft:coal", 4), true)
        );

        List<NpcSpec> npcs = List.of(
                new NpcSpec("王廷总管奥斯温", NpcProfession.NONE, NpcSocialClass.NOBLE,
                        NpcOffice.HIGH_RULER, "sagadyssey:kingdom", "capital", 4, 5, NpcCommand.STAY),
                new NpcSpec("河谷卫队长", NpcProfession.HEAVY, NpcSocialClass.FREEMAN,
                        NpcOffice.SWORN, "sagadyssey:kingdom", "capital", 10, 12, NpcCommand.PATROL),
                new NpcSpec("河谷守卫甲", NpcProfession.WARRIOR, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "capital", 14, 10, NpcCommand.PATROL),
                new NpcSpec("河谷守卫乙", NpcProfession.ARCHER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "capital", -8, 12, NpcCommand.PATROL),
                new NpcSpec("镇铁匠", NpcProfession.BLACKSMITH, NpcSocialClass.FREEMAN,
                        NpcOffice.NONE, "sagadyssey:kingdom", "mine", 164, 32, NpcCommand.WORK,
                        null, new Workplace(164, 28)),
                new NpcSpec("南路商人", NpcProfession.TRADER, NpcSocialClass.BURGHER,
                        NpcOffice.NONE, "sagadyssey:merchant_guild", "mine", 160, 34, NpcCommand.WORK,
                        null, new Workplace(160, 30)),
                new NpcSpec("驿路运输者", NpcProfession.COURIER, NpcSocialClass.FREEMAN,
                        NpcOffice.NONE, "sagadyssey:merchant_guild", "mine", 162, 36, NpcCommand.WORK,
                        null, new Workplace(160, 30)),

                new NpcSpec("麦田村长", NpcProfession.FARMER, NpcSocialClass.FREEMAN,
                        NpcOffice.LOCAL_RULER, "sagadyssey:kingdom", "farmland", 208, 25, NpcCommand.STAY),
                new NpcSpec("麦田农民甲", NpcProfession.FARMER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "farmland", 228, 79, NpcCommand.WORK, "wheat_west"),
                new NpcSpec("麦田农民乙", NpcProfession.FARMER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "farmland", 270, 39, NpcCommand.WORK, "wheat_east"),
                new NpcSpec("赤岩矿工", NpcProfession.WORKER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "mine", 154, 34,
                        NpcCommand.WORK, "mine_yard"),

                new NpcSpec("北境守将", NpcProfession.HEAVY, NpcSocialClass.NOBLE,
                        NpcOffice.LOCAL_RULER, "sagadyssey:kingdom", "fort", 5, -205, NpcCommand.PATROL),
                new NpcSpec("北境弓手", NpcProfession.ARCHER, NpcSocialClass.FREEMAN,
                        NpcOffice.SWORN, "sagadyssey:kingdom", "fort", -12, -192, NpcCommand.PATROL),
                new NpcSpec("北境士兵", NpcProfession.WARRIOR, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "fort", 16, -190, NpcCommand.PATROL),

                new NpcSpec("灰旗头目", NpcProfession.HEAVY, NpcSocialClass.FREEMAN,
                        NpcOffice.LOCAL_RULER, "sagadyssey:bandit", "bandits", -220, 12, NpcCommand.PATROL),
                new NpcSpec("灰旗劫掠者甲", NpcProfession.WARRIOR, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:bandit", "bandits", -210, 5, NpcCommand.PATROL),
                new NpcSpec("灰旗劫掠者乙", NpcProfession.ARCHER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:bandit", "bandits", -228, 28, NpcCommand.PATROL),

                new NpcSpec("雾月司祭", NpcProfession.BARD, NpcSocialClass.NOBLE,
                        NpcOffice.HIGH_RULER, "sagadyssey:church", "sanctuary", 8, 205, NpcCommand.STAY),
                new NpcSpec("圣所医师", NpcProfession.MEDIC, NpcSocialClass.FREEMAN,
                        NpcOffice.SWORN, "sagadyssey:church", "sanctuary", 22, 216, NpcCommand.WORK),
                // 追加新居民，保持旧版本 NPC 的索引和确定性 UUID 不变。
                new NpcSpec("麦田商人", NpcProfession.TRADER, NpcSocialClass.BURGHER,
                        NpcOffice.NONE, "sagadyssey:merchant_guild", "farmland", 205, 34, NpcCommand.WORK,
                        null, new Workplace(205, 30))
        );

        List<HouseholdSpec> households = List.of(
                new HouseholdSpec("wheat_kin", "麦穗之家", "farmland", 7, 8,
                        HouseholdRole.KIN,
                        Map.of("minecraft:bread", 8, "minecraft:wheat", 16, "minecraft:emerald", 2)),
                new HouseholdSpec("sanctuary_clergy", "雾月教士共同户", "sanctuary", 17, 18,
                        HouseholdRole.DEPENDENT,
                        Map.of("minecraft:bread", 6, "minecraft:honey_bottle", 4, "minecraft:book", 2))
        );

        return new FixedScenarioDefinition("small_realm", "裂冠河谷",
                "由王权空悬的河谷镇、农业村、北境堡、强盗营地和南部圣所组成的固定小世界。",
                9, 304, createTerrain(farms), placements, settlements, farms, workZones,
                warehouses, npcs, households);
    }

    private static FixedScenarioDefinition blacksmithLab() {
        List<TerrainBlock> terrain = labPlaza(8);
        terrain.add(new TerrainBlock(2, 0, 0, TerrainKind.FURNACE));
        terrain.add(new TerrainBlock(3, 0, 0, TerrainKind.BLAST_FURNACE));
        terrain.add(new TerrainBlock(2, 0, 2, TerrainKind.ANVIL));
        terrain.add(new TerrainBlock(3, 0, 2, TerrainKind.SMITHING_TABLE));
        terrain.add(new TerrainBlock(0, 0, 6, TerrainKind.BELL));
        return lab("blacksmith_lab", "铁匠实验场",
                "单一铁匠、原料箱、熔炉、高炉、铁砧与锻造台；可分开测试冶炼、锻造和维修。",
                terrain,
                List.of(new WarehouseSpec("lab", "铁匠原料箱", -5, 0,
                        Map.of("minecraft:raw_iron", 24, "minecraft:coal", 12), true)),
                List.of(new NpcSpec("实验铁匠", NpcProfession.BLACKSMITH, NpcSocialClass.FREEMAN,
                        NpcOffice.NONE, "sagadyssey:kingdom", "lab", 0, 4, NpcCommand.WORK,
                        null, new Workplace(2, 1))),
                List.of(), List.of(), List.of());
    }

    private static FixedScenarioDefinition farmerLab() {
        FarmSpec farm = new FarmSpec("wheat_plot", "lab", -4, -4, 9);
        Map<Long, TerrainKind> blocks = new LinkedHashMap<>();
        addRoad(blocks, 0, -9, 0, -5, 3);
        addFarm(blocks, farm);
        return lab("farmer_lab", "农民实验场",
                "一块标准 9×9 农田，中央一格水、独立围栏、北门、堆肥桶和收获仓。",
                terrainFrom(blocks),
                List.of(new WarehouseSpec("lab", "农作物收获箱", 8, 0,
                        Map.of("minecraft:wheat_seeds", 32, "minecraft:bread", 8), true)),
                List.of(new NpcSpec("实验农民", NpcProfession.FARMER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "lab", 0, -8, NpcCommand.WORK,
                        "wheat_plot")),
                List.of(farm),
                List.of(new WorkZoneSpec("wheat_plot", -4, -1, -4, 4, -1, 4)),
                List.of());
    }

    private static FixedScenarioDefinition workerLab() {
        List<TerrainBlock> terrain = labPlaza(9);
        for (int x = 3; x <= 7; x++) {
            for (int z = -2; z <= 2; z++) {
                int value = Math.floorMod(x * 19 + z * 11, 7);
                terrain.add(new TerrainBlock(x, 1, z,
                        value == 0 ? TerrainKind.IRON_ORE
                                : value <= 2 ? TerrainKind.COAL_ORE : TerrainKind.STONE));
            }
        }
        return lab("worker_lab", "工人采集实验场",
                "一名工人、可达矿墙和产物箱；重置档位还能测试高处矿物多层施工架与伐木。",
                terrain,
                List.of(new WarehouseSpec("lab", "工人产物箱", -6, 0,
                        Map.of("minecraft:bread", 8), true)),
                List.of(new NpcSpec("实验工人", NpcProfession.WORKER, NpcSocialClass.COMMONER,
                        NpcOffice.NONE, "sagadyssey:kingdom", "lab", -4, 0, NpcCommand.WORK,
                        "resource_zone")),
                List.of(),
                // 资源区只覆盖矿墙/树木和允许放置垫脚块的地面上方。
                // 不得包含 y=-1 的圆石广场，否则工人会把道路当成最近的矿物拆掉。
                List.of(new WorkZoneSpec("resource_zone", 2, 0, -3, 8, 8, 3)),
                List.of());
    }

    private static FixedScenarioDefinition traderLab() {
        Map<Long, TerrainKind> blocks = new LinkedHashMap<>();
        addPlaza(blocks, -10, 0, 3);
        addPlaza(blocks, 10, 0, 3);
        addRoad(blocks, -10, 0, 10, 0, 3);
        List<TerrainBlock> terrain = terrainFrom(blocks);
        terrain.add(new TerrainBlock(0, 0, 3, TerrainKind.BELL));
        return lab("trader_lab", "商人与运输实验场",
                "相距二十格的矿物市场和粮食市场，一名商人巡回、一名运输员执行真实搬运订单。",
                terrain,
                List.of(
                        new WarehouseSpec("lab", "西侧矿物市场", -10, 0,
                                Map.of("minecraft:coal", 32, "minecraft:raw_iron", 16,
                                        "minecraft:emerald", 16), true),
                        new WarehouseSpec("lab", "东侧粮食市场", 10, 0,
                                Map.of("minecraft:wheat", 48, "minecraft:bread", 16,
                                        "minecraft:emerald", 16), true)
                ),
                List.of(
                        new NpcSpec("实验商人", NpcProfession.TRADER, NpcSocialClass.BURGHER,
                                NpcOffice.NONE, "sagadyssey:merchant_guild", "lab", -6, 2,
                                NpcCommand.WORK, null, new Workplace(0, 0)),
                        new NpcSpec("实验运输员", NpcProfession.COURIER, NpcSocialClass.FREEMAN,
                                NpcOffice.NONE, "sagadyssey:merchant_guild", "lab", -6, -2,
                                NpcCommand.WORK, null, new Workplace(0, 0))
                ), List.of(), List.of(), List.of());
    }

    private static FixedScenarioDefinition guardLab() {
        Map<Long, TerrainKind> blocks = new LinkedHashMap<>();
        addPlaza(blocks, 0, 0, 8);
        List<NpcSpec> npcs = List.of(
                new NpcSpec("实验守卫", NpcProfession.WARRIOR, NpcSocialClass.FREEMAN,
                        NpcOffice.SWORN, "sagadyssey:kingdom", "guard_post", -4, 0, NpcCommand.PATROL)
        );
        return new FixedScenarioDefinition("guard_lab", "守卫实验场",
                "一座紧凑哨所；默认观察巡逻，combat 档生成带固定标识的训练敌怪测试索敌。",
                1, 64, terrainFrom(blocks), List.of(),
                List.of(new SettlementSpec("guard_post", "训练哨所", SettlementType.FORT,
                        0, 0, 32, "sagadyssey:kingdom")), List.of(), List.of(),
                List.of(), npcs);
    }

    private static FixedScenarioDefinition medicLab() {
        List<TerrainBlock> terrain = labPlaza(8);
        terrain.add(new TerrainBlock(0, 0, 5, TerrainKind.BELL));
        return lab("medic_lab", "医师实验场",
                "一名携带绷带的医师和一名受伤盟友，用于观察寻路、治疗、耗材和生命变化。",
                terrain, List.of(),
                List.of(
                        new NpcSpec("实验医师", NpcProfession.MEDIC, NpcSocialClass.FREEMAN,
                                NpcOffice.NONE, "sagadyssey:church", "lab", -4, 0, NpcCommand.WORK,
                                null, new Workplace(0, 0)),
                        new NpcSpec("受伤助手", NpcProfession.WORKER, NpcSocialClass.COMMONER,
                                NpcOffice.NONE, "sagadyssey:church", "lab", 4, 0, NpcCommand.STAY)
                ), List.of(), List.of(), List.of());
    }

    private static FixedScenarioDefinition bardLab() {
        List<TerrainBlock> terrain = labPlaza(8);
        terrain.add(new TerrainBlock(0, 0, 0, TerrainKind.BELL));
        return lab("bard_lab", "吟游诗人实验场",
                "一名正式演奏的吟游诗人和两名低士气听众，用于观察演奏动作、粒子与士气恢复。",
                terrain, List.of(),
                List.of(
                        new NpcSpec("实验诗人", NpcProfession.BARD, NpcSocialClass.FREEMAN,
                                NpcOffice.NONE, "sagadyssey:church", "lab", 0, 3, NpcCommand.WORK,
                                null, new Workplace(0, 0)),
                        new NpcSpec("低士气听众甲", NpcProfession.WARRIOR, NpcSocialClass.COMMONER,
                                NpcOffice.NONE, "sagadyssey:church", "lab", -3, 0, NpcCommand.STAY),
                        new NpcSpec("低士气听众乙", NpcProfession.FARMER, NpcSocialClass.COMMONER,
                                NpcOffice.NONE, "sagadyssey:church", "lab", 3, 0, NpcCommand.STAY)
                ), List.of(), List.of(), List.of());
    }

    private static FixedScenarioDefinition householdLab() {
        List<TerrainBlock> terrain = labPlaza(7);
        HouseholdSpec household = new HouseholdSpec("test_family", "实验家庭", "lab",
                0, 1, HouseholdRole.KIN,
                Map.of("minecraft:bowl", 2));
        return lab("household_lab", "家庭生活实验场",
                "两名同户居民、各自私宅和共享食品柜；可分别测试家庭援助、进食和夜间归床。",
                terrain, List.of(),
                List.of(
                        new NpcSpec("供养者", NpcProfession.NONE, NpcSocialClass.FREEMAN,
                                NpcOffice.NONE, "sagadyssey:kingdom", "lab", -3, 0, NpcCommand.IDLE),
                        new NpcSpec("受照料者", NpcProfession.NONE, NpcSocialClass.COMMONER,
                                NpcOffice.NONE, "sagadyssey:kingdom", "lab", 3, 0, NpcCommand.IDLE)
                ), List.of(), List.of(), List.of(household));
    }

    private static FixedScenarioDefinition lab(String id, String name, String description,
                                               List<TerrainBlock> terrain,
                                               List<WarehouseSpec> warehouses, List<NpcSpec> npcs,
                                               List<FarmSpec> farms, List<WorkZoneSpec> workZones,
                                               List<HouseholdSpec> households) {
        return new FixedScenarioDefinition(id, name, description, 1, 64, terrain, List.of(),
                List.of(new SettlementSpec("lab", name, SettlementType.VILLAGE,
                        0, 0, 48, npcs.isEmpty() ? "sagadyssey:kingdom" : npcs.getFirst().factionId())),
                farms, workZones, warehouses, npcs, households);
    }

    private static List<TerrainBlock> labPlaza(int radius) {
        Map<Long, TerrainKind> blocks = new LinkedHashMap<>();
        addPlaza(blocks, 0, 0, radius);
        return terrainFrom(blocks);
    }

    private static List<TerrainBlock> terrainFrom(Map<Long, TerrainKind> blocks) {
        List<TerrainBlock> result = new ArrayList<>(blocks.size());
        blocks.forEach((key, value) -> result.add(
                new TerrainBlock((int) (key >> 32), (int) (long) key, value)));
        return result;
    }

    private static List<TerrainBlock> createTerrain(List<FarmSpec> farms) {
        Map<Long, TerrainKind> blocks = new LinkedHashMap<>();
        addPlaza(blocks, 0, 12, 10);
        addRoad(blocks, -82, 12, 82, 12, 5);
        addRoad(blocks, 0, -82, 0, 82, 5);
        addRoad(blocks, 82, 12, 185, 12, 3);
        addRoad(blocks, 158, 30, 210, 30, 3);
        addRoad(blocks, 0, -82, 0, -166, 3);
        addRoad(blocks, -82, 12, -185, 12, 3);
        addRoad(blocks, 0, 82, 0, 165, 3);

        // 麦田村主街绕开马场，再分别连接两块标准农田的北门。
        addRoad(blocks, 185, 12, 258, 12, 3);
        addRoad(blocks, 258, 12, 258, 78, 3);
        addRoad(blocks, 258, 78, 228, 81, 3);
        addRoad(blocks, 258, 30, 270, 41, 3);
        addRoad(blocks, 215, 82, 228, 81, 3);
        addRoad(blocks, 0, 12, 22, 28, 3);
        addRoad(blocks, 0, -166, 10, -198, 3);
        addRoad(blocks, -185, 12, -208, 24, 3);
        addRoad(blocks, 0, 165, 36, 210, 3);
        farms.forEach(farm -> addFarm(blocks, farm));

        List<TerrainBlock> result = terrainFrom(blocks);
        addMiningYard(result);
        return result;
    }

    private static void addMiningYard(List<TerrainBlock> terrain) {
        for (int x = 146; x <= 154; x++) {
            for (int z = 24; z <= 32; z++) {
                int value = Math.floorMod(x * 31 + z * 17, 9);
                TerrainKind kind = value < 2 ? TerrainKind.COAL_ORE
                        : value == 2 ? TerrainKind.IRON_ORE : TerrainKind.STONE;
                terrain.add(new TerrainBlock(x, 0, z, kind));
                if (x >= 148 && x <= 152 && z >= 26 && z <= 30) {
                    terrain.add(new TerrainBlock(x, 1, z,
                            value == 0 ? TerrainKind.IRON_ORE : TerrainKind.STONE));
                }
            }
        }
        terrain.add(new TerrainBlock(164, 0, 28, TerrainKind.FURNACE));
        terrain.add(new TerrainBlock(165, 0, 28, TerrainKind.BLAST_FURNACE));
        terrain.add(new TerrainBlock(164, 0, 29, TerrainKind.ANVIL));
        terrain.add(new TerrainBlock(165, 0, 29, TerrainKind.SMITHING_TABLE));
        terrain.add(new TerrainBlock(182, 0, 30, TerrainKind.BELL));
    }

    private static void addPlaza(Map<Long, TerrainKind> blocks, int centerX, int centerZ, int radius) {
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if ((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ) <= radius * radius)
                    blocks.put(key(x, z), TerrainKind.PLAZA);
            }
        }
    }

    private static void addRoad(Map<Long, TerrainKind> blocks, int x0, int z0, int x1, int z1, int width) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dz = -Math.abs(z1 - z0), sz = z0 < z1 ? 1 : -1;
        int error = dx + dz;
        while (true) {
            boolean horizontal = dx >= -dz;
            for (int offset = -width / 2; offset <= width / 2; offset++) {
                int x = horizontal ? x0 : x0 + offset;
                int z = horizontal ? z0 + offset : z0;
                blocks.put(key(x, z), TerrainKind.ROAD);
            }
            if (x0 == x1 && z0 == z1) break;
            int twice = 2 * error;
            if (twice >= dz) { error += dz; x0 += sx; }
            if (twice <= dx) { error += dx; z0 += sz; }
        }
    }

    private static void addFarm(Map<Long, TerrainKind> blocks, FarmSpec farm) {
        int center = farm.size() / 2;
        for (int x = 0; x < farm.size(); x++) {
            for (int z = 0; z < farm.size(); z++) {
                TerrainKind kind;
                if (x == center && z == center) {
                    kind = TerrainKind.WATER;
                } else if (x <= 3 && z <= 1) {
                    // 连续留出一小块待播区，避免旧版棋盘式空洞。
                    kind = TerrainKind.FARMLAND_EMPTY;
                } else {
                    int variation = Math.floorMod((farm.x() + x) * 31 + (farm.z() + z) * 17, 11);
                    kind = variation < 2 ? TerrainKind.FARMLAND_GROWING : TerrainKind.FARMLAND_MATURE;
                }
                blocks.put(key(farm.x() + x, farm.z() + z), kind);
            }
        }

        int minX = farm.x() - 1;
        int maxX = farm.x() + farm.size();
        int minZ = farm.z() - 1;
        int maxZ = farm.z() + farm.size();
        for (int offset = -1; offset <= farm.size(); offset++) {
            blocks.put(key(farm.x() + offset, minZ), TerrainKind.FENCE);
            blocks.put(key(farm.x() + offset, maxZ), TerrainKind.FENCE);
            blocks.put(key(minX, farm.z() + offset), TerrainKind.FENCE);
            blocks.put(key(maxX, farm.z() + offset), TerrainKind.FENCE);
        }
        blocks.put(key(farm.x() + center, minZ), TerrainKind.FARM_GATE);
        blocks.put(key(maxX + 1, farm.z() + center), TerrainKind.COMPOSTER);
    }

    private void validate() {
        if (revision < 1) throw new IllegalStateException("固定场景修订号必须大于 0: " + id);
        for (TerrainBlock block : terrain) requireInsideRadius(block.x(), block.z(), "地形方块");
        Set<String> settlementKeys = new HashSet<>();
        for (SettlementSpec settlement : settlements) {
            if (!settlementKeys.add(settlement.key()))
                throw new IllegalStateException("固定场景存在重复聚落键: " + settlement.key());
            requireInsideRadius(settlement.x(), settlement.z(), "聚落 " + settlement.key());
        }
        Set<String> farmKeys = new HashSet<>();
        for (FarmSpec farm : farms) {
            if (!farmKeys.add(farm.key())) throw new IllegalStateException("固定场景存在重复农田键: " + farm.key());
            if (!settlementKeys.contains(farm.settlementKey()))
                throw new IllegalStateException("农田引用未知聚落: " + farm.key());
            if (farm.size() < 3 || farm.size() % 2 == 0)
                throw new IllegalStateException("农田尺寸必须是大于等于 3 的奇数: " + farm.key());
            requireInsideRadius(farm.x() + farm.size(), farm.z() + farm.size(), "农田 " + farm.key());
        }
        Set<String> workZoneKeys = new HashSet<>();
        for (WorkZoneSpec zone : workZones) {
            if (!workZoneKeys.add(zone.key()))
                throw new IllegalStateException("固定场景存在重复工作区键: " + zone.key());
            if (zone.minX() > zone.maxX() || zone.minY() > zone.maxY() || zone.minZ() > zone.maxZ())
                throw new IllegalStateException("固定场景工作区坐标颠倒: " + zone.key());
            requireInsideRadius(zone.minX(), zone.minZ(), "工作区 " + zone.key());
            requireInsideRadius(zone.maxX(), zone.maxZ(), "工作区 " + zone.key());
        }
        if (!workZoneKeys.containsAll(farmKeys))
            throw new IllegalStateException("固定场景中的每块农田都必须拥有同名工作区");
        for (Placement placement : placements) {
            if (!settlementKeys.contains(placement.settlementKey()))
                throw new IllegalStateException("建筑引用未知聚落: " + placement.templateId());
            requireInsideRadius(placement.x(), placement.z(), "建筑 " + placement.templateId());
        }
        for (WarehouseSpec warehouse : warehouses) {
            if (!settlementKeys.contains(warehouse.settlementKey()))
                throw new IllegalStateException("仓库引用未知聚落: " + warehouse.name());
            requireInsideRadius(warehouse.x(), warehouse.z(), "仓库 " + warehouse.name());
        }
        for (NpcSpec npc : npcs) {
            if (!settlementKeys.contains(npc.settlementKey()))
                throw new IllegalStateException("NPC 引用未知聚落: " + npc.name());
            if (npc.workZoneKey() != null && !workZoneKeys.contains(npc.workZoneKey()))
                throw new IllegalStateException("NPC 引用未知工作区: " + npc.name());
            requireInsideRadius(npc.x(), npc.z(), "NPC " + npc.name());
            if (npc.workplace() != null) {
                requireInsideRadius(npc.workplace().x(), npc.workplace().z(), "NPC 工作地点 " + npc.name());
            }
        }
        validateLabRegressionContracts();
        Set<String> householdKeys = new HashSet<>();
        for (HouseholdSpec household : households) {
            if (!householdKeys.add(household.key()))
                throw new IllegalStateException("固定场景存在重复家庭键: " + household.key());
            if (!settlementKeys.contains(household.settlementKey()))
                throw new IllegalStateException("家庭引用未知聚落: " + household.key());
            if (household.headIndex() < 0 || household.headIndex() >= npcs.size()
                    || household.memberIndex() < 0 || household.memberIndex() >= npcs.size()
                    || household.headIndex() == household.memberIndex()) {
                throw new IllegalStateException("家庭成员索引无效: " + household.key());
            }
        }
    }

    /** 防止紧凑实验场在后续调整布局时重新引入已经定位过的测试干扰。 */
    private void validateLabRegressionContracts() {
        if ("farmer_lab".equals(id)) {
            FarmSpec farm = farm("wheat_plot");
            if (farm == null || farm.size() != 9) {
                throw new IllegalStateException("农民实验场必须保留标准 9x9 农田");
            }
            int centerX = farm.x() + farm.size() / 2;
            int centerZ = farm.z() + farm.size() / 2;
            long waterCount = terrain.stream()
                    .filter(block -> block.x() >= farm.x() && block.x() < farm.x() + farm.size())
                    .filter(block -> block.z() >= farm.z() && block.z() < farm.z() + farm.size())
                    .filter(block -> block.kind() == TerrainKind.WATER)
                    .count();
            boolean centeredWater = terrain.stream().anyMatch(block -> block.x() == centerX
                    && block.z() == centerZ && block.kind() == TerrainKind.WATER);
            if (waterCount != 1 || !centeredWater) {
                throw new IllegalStateException("农民实验场的 9x9 农田必须且只能在中央放置一格水");
            }
        }
        if ("worker_lab".equals(id)) {
            WorkZoneSpec zone = workZone("resource_zone");
            if (zone == null || zone.minY() < 0 || zone.maxY() < 8) {
                throw new IllegalStateException("工人实验场工作区必须排除广场层并覆盖三层施工架测试高度");
            }
            boolean includesWarehouse = warehouses.stream().anyMatch(warehouse ->
                    warehouse.x() >= zone.minX() && warehouse.x() <= zone.maxX()
                            && warehouse.z() >= zone.minZ() && warehouse.z() <= zone.maxZ()
                            && zone.minY() <= 0 && zone.maxY() >= 0);
            if (includesWarehouse) {
                throw new IllegalStateException("工人实验场工作区不得包含产物箱");
            }
        }
    }

    private void requireInsideRadius(int x, int z, String label) {
        if (Math.abs(x) > radius || Math.abs(z) > radius)
            throw new IllegalStateException(label + " 超出固定场景半径 " + radius);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
}
