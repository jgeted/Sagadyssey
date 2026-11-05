package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.npc.work.NpcWorkstationContainers;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.life.NpcWorkAuthority;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 为紧凑实验场提供可重复、可观察的确定性初始状态。 */
public final class ScenarioLabController {
    private static final Set<String> LABS = Set.of(
            "blacksmith_lab", "farmer_lab", "worker_lab", "trader_lab",
            "guard_lab", "medic_lab", "bard_lab", "household_lab");

    private ScenarioLabController() { }

    public record ApplyResult(boolean applied, String profile, int resetNpcs, List<String> warnings) {
        public ApplyResult {
            warnings = List.copyOf(warnings);
        }
    }

    public static boolean isLab(String scenarioId) {
        return scenarioId != null && LABS.contains(scenarioId);
    }

    /** 实验场角色必须保留固定岗位，不能被普通招募流程改写。 */
    public static boolean isLabNpc(MinecraftServer server, UUID npcId) {
        if (server == null || npcId == null) return false;
        return FixedScenarioData.get(server).deployments().stream()
                .anyMatch(deployment -> matchesLabNpc(deployment, npcId));
    }

    static boolean matchesLabNpc(FixedScenarioDeployment deployment, UUID npcId) {
        if (deployment == null || npcId == null || !isLab(deployment.scenarioId())) return false;
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        if (definition == null) return false;
        for (int index = 0; index < definition.npcs().size(); index++) {
            if (FixedScenarioData.npcId(deployment, index).equals(npcId)) return true;
        }
        return false;
    }

    public static String defaultProfile(String scenarioId) {
        return switch (scenarioId) {
            case "blacksmith_lab" -> "smelt";
            case "farmer_lab" -> "harvest";
            case "worker_lab" -> "mine";
            case "trader_lab" -> "logistics";
            case "guard_lab" -> "patrol";
            case "medic_lab" -> "treat";
            case "bard_lab" -> "perform";
            case "household_lab" -> "care";
            default -> "";
        };
    }

    public static List<String> profiles(String scenarioId) {
        return switch (scenarioId) {
            case "blacksmith_lab" -> List.of("smelt", "forge", "repair");
            case "farmer_lab" -> List.of("harvest", "sow", "irrigate");
            case "worker_lab" -> List.of("mine", "overhead", "logging");
            case "trader_lab" -> List.of("logistics", "route");
            case "guard_lab" -> List.of("patrol", "combat");
            case "medic_lab" -> List.of("treat", "healthy");
            case "bard_lab" -> List.of("perform");
            case "household_lab" -> List.of("care", "sleep");
            default -> List.of();
        };
    }

    public static String profileDescription(String scenarioId, String profile) {
        return switch (scenarioId + ":" + profile) {
            case "blacksmith_lab:smelt" -> "从原料箱领取原铁和煤，装填熔炉并收取铁锭";
            case "blacksmith_lab:forge" -> "使用随身铁锭和煤在铁砧或锻造台制作铁制工具";
            case "blacksmith_lab:repair" -> "消耗铁锭维修背包中的受损铁镐";
            case "farmer_lab:harvest" -> "收获整块成熟小麦，并把产物送往收获箱";
            case "farmer_lab:sow" -> "在中央有水的 9×9 空耕地上连续播种";
            case "farmer_lab:irrigate" -> "农田先无水，农民使用水桶在 9×9 中央自行放置水源";
            case "worker_lab:mine" -> "开采面前可直接触及的煤、铁和石头";
            case "worker_lab:overhead" -> "用随身圆石搭三层阶梯施工架，开采高处矿物后回收全部垫脚块";
            case "worker_lab:logging" -> "砍伐工作区内的树干并清理相邻树叶";
            case "trader_lab:logistics" -> "商人和运输员领取两座市场之间的煤与小麦订单";
            case "trader_lab:route" -> "不创建订单，只观察商人在两座市场间巡回展示货物";
            case "guard_lab:patrol" -> "不生成敌人，只观察哨所守卫的巡逻边界";
            case "guard_lab:combat" -> "在警戒范围内生成训练僵尸，观察守卫的自主索敌";
            case "medic_lab:treat" -> "医师携带白色羊毛，治疗生命值约三成的盟友";
            case "medic_lab:healthy" -> "双方满血，确认医师不会制造虚假治疗行为";
            case "bard_lab:perform" -> "吟游诗人演奏并恢复附近两名盟友的士气";
            case "household_lab:care" -> "饱腹成员把随身熟食送入共享食品柜，照料饥饿亲属";
            case "household_lab:sleep" -> "清空紧急需求；配合 /time set night 观察各自回家睡觉";
            default -> "未知测试档";
        };
    }

    public static ApplyResult apply(ServerLevel level, FixedScenarioDeployment deployment,
                                    FixedScenarioDefinition definition, String requestedProfile) {
        String profile = requestedProfile == null ? ""
                : requestedProfile.toLowerCase(Locale.ROOT);
        if (!isLab(definition.id()) || !profiles(definition.id()).contains(profile)) {
            return new ApplyResult(false, profile, 0,
                    List.of("场景或测试档不存在"));
        }

        List<String> warnings = new ArrayList<>();
        List<String> playerManaged = playerManagedNpcs(level, deployment, definition);
        if (!playerManaged.isEmpty()) {
            return new ApplyResult(false, profile, 0, List.of(
                    "以下实验 NPC 已被玩家招募或签约，重置已取消以保护其配置与物资: "
                            + String.join("、", playerManaged)));
        }
        restoreTerrain(level, deployment, definition);
        restoreWarehouses(level, deployment, definition, warnings);
        LogisticsData.get(level.getServer()).cancelOpenOrdersCreatedBy(
                deployment.id(), level.getGameTime());

        int resetNpcs = resetNpcs(level, deployment, definition, warnings);
        switch (definition.id()) {
            case "blacksmith_lab" -> configureBlacksmith(level, deployment, definition, profile, warnings);
            case "farmer_lab" -> configureFarmer(level, deployment, definition, profile, warnings);
            case "worker_lab" -> configureWorker(level, deployment, definition, profile, warnings);
            case "trader_lab" -> configureTrader(level, deployment, definition, profile, warnings);
            case "guard_lab" -> configureGuard(level, deployment, profile, warnings);
            case "medic_lab" -> configureMedic(level, deployment, profile, warnings);
            case "bard_lab" -> configureBard(level, deployment, warnings);
            case "household_lab" -> configureHousehold(level, deployment, definition, profile, warnings);
            default -> { }
        }
        refreshSchedules(level, deployment, definition);
        deployment.setTestProfile(profile);
        return new ApplyResult(true, profile, resetNpcs, warnings);
    }

    public static UUID trainingHostileId(FixedScenarioDeployment deployment) {
        return UUID.nameUUIDFromBytes((deployment.id() + "|training_hostile")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void restoreTerrain(ServerLevel level, FixedScenarioDeployment deployment,
                                       FixedScenarioDefinition definition) {
        for (FixedScenarioDefinition.TerrainBlock block : definition.terrain()) {
            FixedScenarioEvents.placeTerrain(level, deployment.anchor(), block);
            if (block.kind() == FixedScenarioDefinition.TerrainKind.FURNACE
                    || block.kind() == FixedScenarioDefinition.TerrainKind.BLAST_FURNACE) {
                BlockPos position = terrainPosition(deployment, block.x(), block.y(), block.z());
                Container furnace = NpcWorkstationContainers.asFurnace(level.getBlockEntity(position));
                if (furnace != null) {
                    furnace.clearContent();
                    furnace.setChanged();
                }
            }
        }
    }

    private static void restoreWarehouses(ServerLevel level, FixedScenarioDeployment deployment,
                                          FixedScenarioDefinition definition, List<String> warnings) {
        for (FixedScenarioDefinition.WarehouseSpec warehouse : definition.warehouses()) {
            BlockPos position = deployment.anchor().offset(warehouse.x(), 0, warehouse.z());
            if (!level.hasChunkAt(position)) level.getChunkAt(position);
            Container container = WarehouseContainers.asContainer(level.getBlockEntity(position));
            if (container == null) {
                warnings.add("仓库容器缺失: " + warehouse.name());
                continue;
            }
            container.clearContent();
            FixedScenarioEvents.fillInitialStock(container, warehouse.initialStock());
        }
    }

    private static int resetNpcs(ServerLevel level, FixedScenarioDeployment deployment,
                                 FixedScenarioDefinition definition, List<String> warnings) {
        int reset = 0;
        for (int index = 0; index < definition.npcs().size(); index++) {
            FixedScenarioDefinition.NpcSpec spec = definition.npcs().get(index);
            if (!(level.getEntity(FixedScenarioData.npcId(deployment, index)) instanceof NpcBase npc)) {
                warnings.add("NPC 未加载: " + spec.name());
                continue;
            }
            if (npc.isOwned()) {
                warnings.add("NPC 已被玩家招募，未覆盖玩家配置: " + spec.name());
                continue;
            }
            npc.getNavigation().stop();
            npc.setTarget(null);
            npc.getEquipmentInventory().clearContent();
            npc.getTransportInventory().clearContent();
            for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                npc.setItemSlot(slot, ItemStack.EMPTY);
            }
            BlockPos position = FixedScenarioEvents.findSpawnPosition(level,
                    deployment.anchor().offset(spec.x(), 0, spec.z()));
            FixedScenarioEvents.applyNpcSpec(npc, deployment, definition, spec, position);
            // applyNpcSpec 在职业未变化时不会再次发放初始物资；实验场已经清空了
            // 私人物品栏，因此必须显式恢复完整职业配置，避免商人与运输员空手。
            npc.applyInitialEquipment(spec.profession());
            npc.setHealth(npc.getMaxHealth());
            npc.setMoral(50);
            NpcLifeData.get(level.getServer()).resetTransientState(
                    npc.getCharacterId(), 100, 120, level.getGameTime());
            npc.reportWorkActivity("实验场已重置", deployment.anchor(), "等待测试档初始化");
            reset++;
        }
        return reset;
    }

    private static void configureBlacksmith(ServerLevel level, FixedScenarioDeployment deployment,
                                            FixedScenarioDefinition definition, String profile,
                                            List<String> warnings) {
        NpcBase blacksmith = npc(level, deployment, 0, warnings);
        Container stock = warehouse(level, deployment, definition, 0, warnings);
        if (blacksmith == null) return;
        if (stock != null) stock.clearContent();
        switch (profile) {
            case "smelt" -> {
                if (stock != null) FixedScenarioEvents.fillInitialStock(stock,
                        Map.of("minecraft:raw_iron", 24, "minecraft:coal", 12));
            }
            case "forge" -> addBag(blacksmith,
                    new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.COAL, 4));
            case "repair" -> {
                ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
                damaged.setDamageValue(damaged.getMaxDamage() * 3 / 4);
                addBag(blacksmith, damaged, new ItemStack(Items.IRON_INGOT, 4));
            }
            default -> { }
        }
    }

    private static void configureFarmer(ServerLevel level, FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, String profile,
                                        List<String> warnings) {
        NpcBase farmer = npc(level, deployment, 0, warnings);
        if (farmer == null || definition.farms().isEmpty()) return;
        FixedScenarioDefinition.FarmSpec farm = definition.farms().getFirst();
        int center = farm.size() / 2;
        for (int x = 0; x < farm.size(); x++) {
            for (int z = 0; z < farm.size(); z++) {
                BlockPos ground = deployment.anchor().offset(farm.x() + x, -1, farm.z() + z);
                BlockPos crop = ground.above();
                boolean water = !"irrigate".equals(profile) && x == center && z == center;
                if (water) {
                    level.setBlock(ground, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(crop, Blocks.LILY_PAD.defaultBlockState(), Block.UPDATE_ALL);
                    continue;
                }
                level.setBlock(ground, Blocks.FARMLAND.defaultBlockState().setValue(
                        FarmBlock.MOISTURE, "irrigate".equals(profile) ? 0 : FarmBlock.MAX_MOISTURE),
                        Block.UPDATE_ALL);
                if ("harvest".equals(profile)) {
                    level.setBlock(crop, Blocks.WHEAT.defaultBlockState()
                            .setValue(CropBlock.AGE, CropBlock.MAX_AGE), Block.UPDATE_ALL);
                } else {
                    level.setBlock(crop, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        if ("irrigate".equals(profile)) addBag(farmer, new ItemStack(Items.WATER_BUCKET));
        else addBag(farmer, new ItemStack(Items.WHEAT_SEEDS, 64),
                new ItemStack(Items.WHEAT_SEEDS, 32));
    }

    private static void configureWorker(ServerLevel level, FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, String profile,
                                        List<String> warnings) {
        NpcBase worker = npc(level, deployment, 0, warnings);
        if (worker == null) return;
        for (int x = 3; x <= 7; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 1; y <= 9; y++) {
                    level.setBlock(terrainPosition(deployment, x, y, z),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        if ("overhead".equals(profile)) {
            // terrainPosition 会把 y 转成 anchor + y - 1。使用 y=9 后，
            // 地面不可触及，必须搭出三层实体阶梯才能完成开采。
            level.setBlock(terrainPosition(deployment, 5, 9, 0),
                    Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(terrainPosition(deployment, 6, 9, 1),
                    Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
            addBag(worker, new ItemStack(Items.COBBLESTONE, 16));
        } else if ("logging".equals(profile)) {
            for (int y = 1; y <= 4; y++) level.setBlock(terrainPosition(deployment, 5, y, 0),
                    Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
            for (int x = 4; x <= 6; x++) {
                for (int z = -1; z <= 1; z++) level.setBlock(terrainPosition(deployment, x, 4, z),
                        Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
            }
            addBag(worker, new ItemStack(Items.COBBLESTONE, 8));
        } else {
            for (int x = 3; x <= 7; x++) {
                for (int z = -2; z <= 2; z++) {
                    int value = Math.floorMod(x * 19 + z * 11, 7);
                    level.setBlock(terrainPosition(deployment, x, 1, z),
                            (value == 0 ? Blocks.IRON_ORE : value <= 2 ? Blocks.COAL_ORE : Blocks.STONE)
                                    .defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            addBag(worker, new ItemStack(Items.COBBLESTONE, 8));
        }
    }

    private static void configureTrader(ServerLevel level, FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, String profile,
                                        List<String> warnings) {
        if (!"logistics".equals(profile) || definition.warehouses().size() < 2) return;
        boolean carrierAvailable = false;
        for (int index = 0; index < definition.npcs().size(); index++) {
            if (level.getEntity(FixedScenarioData.npcId(deployment, index)) instanceof NpcBase npc
                    && !npc.isOwned() && (npc.getProfession() == NpcProfession.TRADER
                    || npc.getProfession() == NpcProfession.COURIER)) {
                carrierAvailable = true;
                break;
            }
        }
        if (!carrierAvailable) {
            warnings.add("没有已加载的商人或运输员，未创建无法执行的物流订单");
            return;
        }
        LogisticsData logistics = LogisticsData.get(level.getServer());
        WarehouseRecord west = warehouseRecord(level, deployment, definition, 0);
        WarehouseRecord east = warehouseRecord(level, deployment, definition, 1);
        if (west == null || east == null) {
            warnings.add("市场登记缺失，无法创建物流订单");
            return;
        }
        logistics.createOrder(west.id(), east.id(), "minecraft:coal", 8,
                deployment.id(), level.getGameTime());
        logistics.createOrder(east.id(), west.id(), "minecraft:wheat", 8,
                deployment.id(), level.getGameTime());
    }

    private static void configureGuard(ServerLevel level, FixedScenarioDeployment deployment,
                                       String profile, List<String> warnings) {
        NpcBase guard = npc(level, deployment, 0, warnings);
        UUID hostileId = trainingHostileId(deployment);
        if (level.getEntity(hostileId) != null) level.getEntity(hostileId).discard();
        if (guard == null || !"combat".equals(profile)) return;
        guard.setPos(deployment.anchor().getX() - 3.5D, deployment.anchor().getY(),
                deployment.anchor().getZ() + 0.5D);
        guard.setCommand(NpcCommand.PATROL);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            warnings.add("无法创建训练僵尸");
            return;
        }
        zombie.setUUID(hostileId);
        zombie.setCustomName(Component.literal("训练敌怪"));
        zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
        zombie.setPos(deployment.anchor().getX() + 4.5D, deployment.anchor().getY(),
                deployment.anchor().getZ() + 0.5D);
        if (!level.addFreshEntity(zombie)) warnings.add("训练僵尸 UUID 冲突");
    }

    private static void configureMedic(ServerLevel level, FixedScenarioDeployment deployment,
                                       String profile, List<String> warnings) {
        NpcBase medic = npc(level, deployment, 0, warnings);
        NpcBase patient = npc(level, deployment, 1, warnings);
        if (medic != null) addBag(medic, new ItemStack(Items.WHITE_WOOL, 8));
        if (patient != null && "treat".equals(profile)) {
            patient.setHealth(Math.max(1.0F, patient.getMaxHealth() * 0.30F));
        }
    }

    private static void configureBard(ServerLevel level, FixedScenarioDeployment deployment,
                                      List<String> warnings) {
        NpcBase bard = npc(level, deployment, 0, warnings);
        NpcBase first = npc(level, deployment, 1, warnings);
        NpcBase second = npc(level, deployment, 2, warnings);
        if (bard != null) bard.setMoral(50);
        if (first != null) first.setMoral(10);
        if (second != null) second.setMoral(15);
    }

    private static void configureHousehold(ServerLevel level, FixedScenarioDeployment deployment,
                                           FixedScenarioDefinition definition, String profile,
                                           List<String> warnings) {
        NpcBase donor = npc(level, deployment, 0, warnings);
        NpcBase recipient = npc(level, deployment, 1, warnings);
        if (donor == null || recipient == null) return;
        long now = level.getGameTime();
        NpcLifeData lives = NpcLifeData.get(level.getServer());
        if ("care".equals(profile)) {
            lives.setHunger(donor.getUUID(), 100, now);
            lives.setHunger(recipient.getUUID(), 820, now);
            addBag(donor, new ItemStack(Items.BREAD, 4));
        } else {
            lives.setHunger(donor.getUUID(), 80, now);
            lives.setHunger(recipient.getUUID(), 80, now);
            lives.updateFatigue(donor.getUUID(), 900);
            lives.updateFatigue(recipient.getUUID(), 900);
        }
        for (FixedScenarioDefinition.HouseholdSpec householdSpec : definition.households()) {
            UUID householdId = UUID.nameUUIDFromBytes((deployment.id() + "|household|" + householdSpec.key())
                    .getBytes(StandardCharsets.UTF_8));
            HouseholdData households = HouseholdData.get(level.getServer());
            households.resetCareState(householdId);
            var household = households.byId(householdId);
            if (household == null || household.sharedStorage() == null) {
                warnings.add("家庭共享储物未登记: " + householdSpec.name());
                continue;
            }
            Container pantry = WarehouseContainers.asContainer(
                    level.getBlockEntity(household.sharedStorage()));
            if (pantry == null) {
                warnings.add("家庭共享储物容器缺失: " + householdSpec.name());
                continue;
            }
            pantry.clearContent();
            FixedScenarioEvents.fillInitialStock(pantry, householdSpec.initialStock());
        }
    }

    private static void addBag(NpcBase npc, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) npc.addToBag(stack);
        }
    }

    private static void refreshSchedules(ServerLevel level, FixedScenarioDeployment deployment,
                                         FixedScenarioDefinition definition) {
        NpcLifeData lives = NpcLifeData.get(level.getServer());
        for (int index = 0; index < definition.npcs().size(); index++) {
            if (level.getEntity(FixedScenarioData.npcId(deployment, index)) instanceof NpcBase npc) {
                NpcSchedulePlanner.refresh(npc, lives, level);
            }
        }
    }

    private static NpcBase npc(ServerLevel level, FixedScenarioDeployment deployment,
                               int index, List<String> warnings) {
        if (level.getEntity(FixedScenarioData.npcId(deployment, index)) instanceof NpcBase npc) {
            if (npc.isOwned()) {
                warnings.add("测试 NPC 已被玩家招募，拒绝修改: " + npc.getNpcName());
                return null;
            }
            return npc;
        }
        warnings.add("测试 NPC 未加载: index=" + index);
        return null;
    }

    private static List<String> playerManagedNpcs(ServerLevel level,
                                                   FixedScenarioDeployment deployment,
                                                   FixedScenarioDefinition definition) {
        NpcLifeData lives = NpcLifeData.get(level.getServer());
        List<String> names = new ArrayList<>();
        for (int index = 0; index < definition.npcs().size(); index++) {
            UUID id = FixedScenarioData.npcId(deployment, index);
            boolean ownedEntity = level.getEntity(id) instanceof NpcBase npc && npc.isOwned();
            var life = lives.record(id);
        boolean playerAuthority = life != null && (life.authority() == NpcWorkAuthority.SERVICE
                    || life.authority() == NpcWorkAuthority.CONTRACT);
            if (ownedEntity || playerAuthority) names.add(definition.npcs().get(index).name());
        }
        return names;
    }

    private static Container warehouse(ServerLevel level, FixedScenarioDeployment deployment,
                                       FixedScenarioDefinition definition, int index,
                                       List<String> warnings) {
        if (index < 0 || index >= definition.warehouses().size()) return null;
        FixedScenarioDefinition.WarehouseSpec spec = definition.warehouses().get(index);
        BlockPos position = deployment.anchor().offset(spec.x(), 0, spec.z());
        Container result = WarehouseContainers.asContainer(level.getBlockEntity(position));
        if (result == null) warnings.add("测试仓库容器缺失: " + spec.name());
        return result;
    }

    private static WarehouseRecord warehouseRecord(ServerLevel level, FixedScenarioDeployment deployment,
                                                   FixedScenarioDefinition definition, int index) {
        FixedScenarioDefinition.WarehouseSpec spec = definition.warehouses().get(index);
        return LogisticsData.get(level.getServer()).warehouseAt(deployment.dimension(),
                deployment.anchor().offset(spec.x(), 0, spec.z()));
    }

    private static BlockPos terrainPosition(FixedScenarioDeployment deployment, int x, int y, int z) {
        return deployment.anchor().offset(x, y - 1, z);
    }
}
