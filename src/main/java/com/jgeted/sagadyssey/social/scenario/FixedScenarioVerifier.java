package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;
import com.jgeted.sagadyssey.core.structure.StructureConfig;
import com.jgeted.sagadyssey.npc.entity.FarmMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcResidenceFixtures;
import com.jgeted.sagadyssey.social.life.NpcWorkAuthority;
import com.jgeted.sagadyssey.social.life.NpcWorkZoneRecord;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.settlement.BuildingRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** 对已经部署的固定场景执行只读运行时验收。 */
public final class FixedScenarioVerifier {
    private FixedScenarioVerifier() { }

    public record Result(int checks, List<String> issues, List<String> warnings) {
        public Result {
            issues = List.copyOf(issues);
            warnings = List.copyOf(warnings);
        }

        public boolean passed() { return issues.isEmpty(); }
    }

    public static Result verify(MinecraftServer server, ServerLevel level,
                                FixedScenarioDeployment deployment,
                                FixedScenarioDefinition definition) {
        State state = new State();
        check(state, deployment.phase() == FixedScenarioDeployment.Phase.COMPLETE,
                "部署尚未完成，当前阶段=" + deployment.phase().name());
        check(state, deployment.definitionRevision() == definition.revision(),
                "场景版本过旧: 已部署 r" + deployment.definitionRevision()
                        + "，当前 r" + definition.revision() + "，请执行 repair confirm");
        if (ScenarioLabController.isLab(definition.id())) {
            check(state, ScenarioLabController.profiles(definition.id()).contains(deployment.testProfile()),
                    "实验场测试档未初始化或无效: " + deployment.testProfile());
        }
        checkSettlements(server, deployment, definition, state);
        checkFarms(level, deployment, definition, state);
        checkStructures(server, level, deployment, definition, state);
        checkWorksites(level, deployment, definition, state);
        checkWarehouses(server, level, deployment, definition, state);
        checkNpcs(server, level, deployment, definition, state);
        checkHouseholds(server, level, deployment, definition, state);
        return new Result(state.checks, state.issues, state.warnings);
    }

    private static void checkSettlements(MinecraftServer server, FixedScenarioDeployment deployment,
                                         FixedScenarioDefinition definition, State state) {
        SettlementRegistryData settlements = SettlementRegistryData.get(server);
        for (FixedScenarioDefinition.SettlementSpec spec : definition.settlements()) {
            UUID id = FixedScenarioData.settlementId(deployment, spec.key());
            SettlementRecord record = settlements.byId(id);
            check(state, record != null, "聚落未登记: " + spec.name());
            if (record == null) continue;
            check(state, record.dimension().equals(deployment.dimension()), "聚落维度错误: " + spec.name());
            check(state, record.center().equals(deployment.anchor().offset(spec.x(), 0, spec.z())),
                    "聚落中心错误: " + spec.name());
            check(state, record.factionId().equals(spec.factionId()), "聚落阵营错误: " + spec.name());
        }
    }

    private static void checkFarms(ServerLevel level, FixedScenarioDeployment deployment,
                                   FixedScenarioDefinition definition, State state) {
        boolean irrigationTest = "farmer_lab".equals(definition.id())
                && "irrigate".equals(deployment.testProfile());
        for (FixedScenarioDefinition.FarmSpec farm : definition.farms()) {
            int center = farm.size() / 2;
            int farmland = 0;
            for (int x = 0; x < farm.size(); x++) {
                for (int z = 0; z < farm.size(); z++) {
                    BlockPos ground = deployment.anchor().offset(farm.x() + x, -1, farm.z() + z);
                    load(level, ground);
                    if (x == center && z == center) {
                        boolean validCenter = level.getBlockState(ground).is(Blocks.WATER)
                                || irrigationTest && level.getBlockState(ground).is(Blocks.FARMLAND);
                        check(state, validCenter, irrigationTest
                                ? "灌溉测试的中央格既不是待开挖耕地也不是已完成水源: " + farm.key()
                                : "农田中央水源缺失: " + farm.key());
                    } else if (level.getBlockState(ground).is(Blocks.FARMLAND)) {
                        farmland++;
                    }
                }
            }
            int expectedFarmland = farm.size() * farm.size() - 1;
            check(state, farmland == expectedFarmland,
                    "农田耕地不完整: " + farm.key() + "，当前=" + farmland + "，应为=" + expectedFarmland);
            checkFarmBoundary(level, deployment, farm, state);
        }
    }

    private static void checkFarmBoundary(ServerLevel level, FixedScenarioDeployment deployment,
                                          FixedScenarioDefinition.FarmSpec farm, State state) {
        int minX = farm.x() - 1;
        int maxX = farm.x() + farm.size();
        int minZ = farm.z() - 1;
        int maxZ = farm.z() + farm.size();
        int gateX = farm.x() + farm.size() / 2;
        int invalid = 0;
        for (int offset = -1; offset <= farm.size(); offset++) {
            invalid += boundaryBlockValid(level, deployment.anchor().offset(farm.x() + offset, 0, minZ),
                    farm.x() + offset == gateX) ? 0 : 1;
            invalid += boundaryBlockValid(level, deployment.anchor().offset(farm.x() + offset, 0, maxZ),
                    false) ? 0 : 1;
            invalid += boundaryBlockValid(level, deployment.anchor().offset(minX, 0, farm.z() + offset),
                    false) ? 0 : 1;
            invalid += boundaryBlockValid(level, deployment.anchor().offset(maxX, 0, farm.z() + offset),
                    false) ? 0 : 1;
        }
        check(state, invalid == 0, "农田围栏或北门损坏: " + farm.key() + "，异常方块=" + invalid);
        BlockPos composter = deployment.anchor().offset(maxX + 1, 0, farm.z() + farm.size() / 2);
        load(level, composter);
        check(state, level.getBlockState(composter).is(Blocks.COMPOSTER), "农田堆肥桶缺失: " + farm.key());
    }

    private static boolean boundaryBlockValid(ServerLevel level, BlockPos position, boolean gate) {
        load(level, position);
        return gate ? level.getBlockState(position).is(Blocks.OAK_FENCE_GATE)
                : level.getBlockState(position).is(Blocks.OAK_FENCE);
    }

    private static void checkStructures(MinecraftServer server, ServerLevel level,
                                        FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, State state) {
        SettlementRegistryData settlements = SettlementRegistryData.get(server);
        int legacyMarkers = 0;
        for (FixedScenarioDefinition.Placement placement : definition.placements()) {
            BlockPos position = deployment.anchor().offset(placement.x(), 0, placement.z());
            BuildingRecord building = settlements.buildingAt(deployment.dimension(), position,
                    placement.templateId());
            check(state, building != null, "建筑未登记: " + placement.templateId());
            if (building != null) {
                UUID expectedSettlement = FixedScenarioData.settlementId(deployment, placement.settlementKey());
                check(state, expectedSettlement.equals(building.settlementId()),
                        "建筑归属错误: " + placement.templateId());
            }

            StructureConfig config = SagadysseyStructures.getConfig(placement.templateId());
            String templatePath = config == null ? placement.templateId() : config.getNbtFileName();
            Identifier location = Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, templatePath);
            Optional<StructureTemplate> template = level.getStructureManager().get(location);
            check(state, template.isPresent(), "建筑模板缺失: " + location);
            if (template.isEmpty()) continue;
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(placement.rotation());
            for (StructureTemplate.StructureBlockInfo marker
                    : template.get().filterBlocks(position, settings, Blocks.STRUCTURE_BLOCK)) {
                load(level, marker.pos());
                if (level.getBlockState(marker.pos()).is(Blocks.STRUCTURE_BLOCK)) legacyMarkers++;
            }
        }
        check(state, legacyMarkers == 0, "场景仍残留结构方块: " + legacyMarkers + " 个");
    }

    private static void checkWarehouses(MinecraftServer server, ServerLevel level,
                                        FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, State state) {
        LogisticsData logistics = LogisticsData.get(server);
        for (FixedScenarioDefinition.WarehouseSpec spec : definition.warehouses()) {
            BlockPos position = deployment.anchor().offset(spec.x(), 0, spec.z());
            var warehouse = logistics.warehouseAt(deployment.dimension(), position);
            check(state, warehouse != null, "仓库未登记: " + spec.name());
            if (warehouse != null) {
                UUID expectedSettlement = FixedScenarioData.settlementId(deployment, spec.settlementKey());
                check(state, expectedSettlement.equals(warehouse.settlementId()), "仓库归属错误: " + spec.name());
            }
            load(level, position);
            check(state, WarehouseContainers.asContainer(level.getBlockEntity(position)) != null,
                    "仓库实体容器缺失: " + spec.name());
            if (spec.chest()) {
                check(state, level.getBlockState(position).is(Blocks.CHEST),
                        "测试市场应使用箱子: " + spec.name());
            }
        }
    }

    private static void checkWorksites(ServerLevel level, FixedScenarioDeployment deployment,
                                       FixedScenarioDefinition definition, State state) {
        int mineable = 0;
        int declaredMineable = 0;
        for (FixedScenarioDefinition.TerrainBlock terrain : definition.terrain()) {
            BlockPos ground = deployment.anchor().offset(
                    terrain.x(), terrain.y() - 1, terrain.z());
            BlockPos placed = switch (terrain.kind()) {
                case FURNACE, BLAST_FURNACE, ANVIL, SMITHING_TABLE, BELL -> ground.above();
                default -> ground;
            };
            switch (terrain.kind()) {
                case FURNACE -> checkBlock(level, placed, Blocks.FURNACE,
                        "熔炉缺失: " + placed.toShortString(), state);
                case BLAST_FURNACE -> checkBlock(level, placed, Blocks.BLAST_FURNACE,
                        "高炉缺失: " + placed.toShortString(), state);
                case ANVIL -> checkBlock(level, placed, Blocks.ANVIL,
                        "铁砧缺失: " + placed.toShortString(), state);
                case SMITHING_TABLE -> checkBlock(level, placed, Blocks.SMITHING_TABLE,
                        "锻造台缺失: " + placed.toShortString(), state);
                case BELL -> checkBlock(level, placed, Blocks.BELL,
                        "场景钟缺失: " + placed.toShortString(), state);
                case STONE, COAL_ORE, IRON_ORE -> {
                    declaredMineable++;
                    load(level, placed);
                    if (level.getBlockState(placed).is(Blocks.COAL_ORE)
                            || level.getBlockState(placed).is(Blocks.IRON_ORE)
                            || level.getBlockState(placed).is(Blocks.STONE)) mineable++;
                }
                default -> { }
            }
        }
        boolean alternateWorkerProfile = "worker_lab".equals(definition.id())
                && ("overhead".equals(deployment.testProfile())
                || "logging".equals(deployment.testProfile()));
        if (declaredMineable > 0 && mineable == 0 && !alternateWorkerProfile) {
            state.warnings.add("场景定义的矿物资源已采空；可用实验场 reset 或场景 repair 恢复");
        }
    }

    private static void checkBlock(ServerLevel level, BlockPos position,
                                   net.minecraft.world.level.block.Block block,
                                   String issue, State state) {
        load(level, position);
        check(state, level.getBlockState(position).is(block), issue);
    }

    private static void checkNpcs(MinecraftServer server, ServerLevel level,
                                  FixedScenarioDeployment deployment,
                                  FixedScenarioDefinition definition, State state) {
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        NpcLifeData lifeData = NpcLifeData.get(server);
        Set<BlockPos> assignedBeds = new HashSet<>();
        Set<BlockPos> assignedStorage = new HashSet<>();
        for (int index = 0; index < definition.npcs().size(); index++) {
            FixedScenarioDefinition.NpcSpec spec = definition.npcs().get(index);
            UUID id = FixedScenarioData.npcId(deployment, index);
            CharacterRecord character = characters.byId(id);
            check(state, character != null, "NPC 未进入社会角色注册表: " + spec.name());
            if (character != null && !character.alive()) {
                state.warnings.add("NPC 人物已经死亡，场景不会将其复活: " + spec.name());
                continue;
            }
            var entity = level.getEntity(id);
            if (entity == null) {
                state.warnings.add("NPC 当前未加载，无法核对: " + spec.name());
                continue;
            }
            check(state, entity instanceof NpcBase, "场景 NPC UUID 被其他实体占用: " + spec.name());
            if (!(entity instanceof NpcBase npc)) continue;
            NpcLifeRecord life = lifeData.record(id);
            check(state, life != null, "NPC 生活档案缺失: " + spec.name());
            if (life != null) {
                check(state, life.bedPosition() != null, "NPC 私人床未分配: " + spec.name());
                if (life.bedPosition() != null) {
                    BlockPos normalizedBed = NpcResidenceFixtures.normalizeBedHead(level, life.bedPosition());
                    load(level, life.bedPosition());
                    var bedState = level.getBlockState(life.bedPosition());
                    check(state, bedState.is(BlockTags.BEDS),
                            "NPC 私人床实体缺失: " + spec.name());
                    check(state, normalizedBed != null && normalizedBed.equals(life.bedPosition())
                                    && bedState.getBlock() instanceof BedBlock
                                    && bedState.getValue(BedBlock.PART) == BedPart.HEAD,
                            "NPC 私人床必须登记床头（可用 repair confirm 修复）: " + spec.name());
                    check(state, normalizedBed != null && hasBedsideSpace(level, normalizedBed),
                            "NPC 床边没有可站立位置: " + spec.name());
                    check(state, normalizedBed != null && hasResidenceDoor(level, normalizedBed),
                            "NPC 私宅附近没有可用房门: " + spec.name());
                    check(state, normalizedBed != null && assignedBeds.add(normalizedBed),
                            "NPC 私人床重复分配: " + spec.name());
                }
                check(state, life.privateStoragePosition() != null, "NPC 私人储物未分配: " + spec.name());
                if (life.privateStoragePosition() != null) {
                    load(level, life.privateStoragePosition());
                    check(state, WarehouseContainers.asContainer(level.getBlockEntity(
                            life.privateStoragePosition())) != null, "NPC 私人储物容器缺失: " + spec.name());
                    check(state, assignedStorage.add(life.privateStoragePosition()),
                            "NPC 私人储物重复分配: " + spec.name());
                }
                check(state, !life.memories().isEmpty(), "NPC 背景记忆缺失: " + spec.name());
            }
            if (npc.isOwned()) {
                if (life != null) {
            check(state, life.authority() == NpcWorkAuthority.SERVICE,
                            "已招募 NPC 的当前权威不是玩家: " + spec.name());
                    check(state, life.activeWorkplaceSettlementId() == null,
                            "已招募 NPC 仍保留机构工作权限: " + spec.name());
                }
                NpcWorkZoneRecord zone = lifeData.workZone(id);
                if (zone != null) {
                    check(state, zone.activeMin() == null && zone.activeMax() == null
                                    || npc.hasWorkZone(),
                            "已招募 NPC 的玩家工作区登记与实体不一致: " + spec.name());
                }
                state.warnings.add("NPC 已被玩家招募，保留玩家配置: " + spec.name());
                continue;
            }
            UUID expectedHome = FixedScenarioData.settlementId(deployment, spec.settlementKey());
            check(state, expectedHome.equals(npc.getHomeSettlementId()),
                    "NPC 所属聚落错误: " + spec.name());
            if (life != null) {
                check(state, life.authority() == NpcWorkAuthority.INSTITUTION,
                        "机构居民的当前权威错误: " + spec.name());
                check(state, expectedHome.equals(life.activeWorkplaceSettlementId()),
                        "机构居民工作聚落错误: " + spec.name());
                if (spec.workplace() != null) {
                    BlockPos expectedWorkplace = deployment.anchor().offset(
                            spec.workplace().x(), 0, spec.workplace().z());
                    check(state, expectedWorkplace.equals(life.activeWorkplace()),
                            "机构居民工作地点错误: " + spec.name());
                }
            }
            if (spec.command() == NpcCommand.PATROL) {
                FixedScenarioDefinition.SettlementSpec settlement = definition.settlement(spec.settlementKey());
                BlockPos expectedCenter = deployment.anchor().offset(settlement.x(), 0, settlement.z());
                check(state, expectedCenter.equals(npc.getPatrolCenter()) && npc.getPatrolRadius() >= 4,
                        "NPC 巡逻区域错误: " + spec.name());
            }
            if (deployment.repair()) {
                if (spec.workZoneKey() != null) checkWorkZone(npc, deployment, definition, spec, state);
                state.warnings.add("修复模式保留 NPC 的动态成长状态: " + spec.name());
                continue;
            }
            check(state, spec.name().equals(npc.getNpcName()), "NPC 名称错误: " + spec.name());
            check(state, npc.getProfession() == spec.profession(), "NPC 职业错误: " + spec.name());
            check(state, npc.getSocialClass() == spec.socialClass(), "NPC 阶层错误: " + spec.name());
            check(state, npc.getOffice() == spec.office(), "NPC 职务错误: " + spec.name());
            check(state, npc.getCommand() == spec.command(), "NPC 命令错误: " + spec.name());
            check(state, npc.getFaction() != null && spec.factionId().equals(npc.getFaction().id()),
                    "NPC 阵营错误: " + spec.name());
            checkWorkZone(npc, deployment, definition, spec, state);
        }
    }

    private static void checkHouseholds(MinecraftServer server, ServerLevel level,
                                        FixedScenarioDeployment deployment,
                                        FixedScenarioDefinition definition, State state) {
        HouseholdData households = HouseholdData.get(server);
        for (FixedScenarioDefinition.HouseholdSpec household : definition.households()) {
            checkScenarioHousehold(households, level, deployment, household.key(),
                    household.headIndex(), household.memberIndex(), state);
        }
    }

    private static void checkScenarioHousehold(HouseholdData households, ServerLevel level,
                                               FixedScenarioDeployment deployment, String key,
                                               int headIndex, int memberIndex, State state) {
        UUID id = UUID.nameUUIDFromBytes((deployment.id() + "|household|" + key)
                .getBytes(StandardCharsets.UTF_8));
        HouseholdRecord household = households.byId(id);
        check(state, household != null, "固定家庭未登记: " + key);
        if (household == null) return;
        UUID originalHead = FixedScenarioData.npcId(deployment, headIndex);
        CharacterRecord originalHeadCharacter = CharacterRegistryData.get(level.getServer()).byId(originalHead);
        check(state, originalHeadCharacter != null && !originalHeadCharacter.alive()
                        ? household.contains(household.headCharacterId())
                        : household.headCharacterId().equals(originalHead),
                "固定家庭户主或继任者错误: " + key);
        check(state, household.contains(FixedScenarioData.npcId(deployment, memberIndex)),
                "固定家庭成员缺失: " + key);
        check(state, household.sharedStorage() != null, "固定家庭共享储物未登记: " + key);
        if (household.sharedStorage() != null) {
            load(level, household.sharedStorage());
            check(state, WarehouseContainers.asContainer(level.getBlockEntity(
                    household.sharedStorage())) != null, "固定家庭共享储物容器缺失: " + key);
        }
    }

    private static void checkWorkZone(NpcBase npc, FixedScenarioDeployment deployment,
                                      FixedScenarioDefinition definition,
                                      FixedScenarioDefinition.NpcSpec spec, State state) {
        if (spec.workZoneKey() == null) {
            check(state, !npc.hasWorkZone(), "NPC 不应拥有固定工作区: " + spec.name());
            return;
        }
        FixedScenarioDefinition.WorkZoneSpec zone = definition.workZone(spec.workZoneKey());
        if (zone == null) {
            check(state, false, "NPC 工作区定义缺失: " + spec.name());
            return;
        }
        BlockPos expectedMin = deployment.anchor().offset(zone.minX(), zone.minY(), zone.minZ());
        BlockPos expectedMax = deployment.anchor().offset(zone.maxX(), zone.maxY(), zone.maxZ());
        check(state, npc.hasWorkZone(), "NPC 没有工作区: " + spec.name());
        if (npc.hasWorkZone()) {
            check(state, expectedMin.equals(npc.getWorkZoneMin()) && expectedMax.equals(npc.getWorkZoneMax()),
                    "NPC 工作区错误: " + spec.name());
            check(state, npc.getProfession() != NpcProfession.FARMER
                            || npc.getFarmMode() == FarmMode.CROP,
                    "麦田农民没有使用农作物模式: " + spec.name());
        }
    }

    private static void load(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position)) level.getChunkAt(position);
    }

    private static boolean hasBedsideSpace(ServerLevel level, BlockPos bed) {
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos stand = bed.relative(direction);
            if (level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()
                    && level.getBlockState(stand.above()).getCollisionShape(level, stand.above()).isEmpty()
                    && !level.getBlockState(stand.below()).getCollisionShape(level, stand.below()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasResidenceDoor(ServerLevel level, BlockPos bed) {
        for (BlockPos position : BlockPos.betweenClosed(bed.offset(-5, -1, -5), bed.offset(5, 3, 5))) {
            var state = level.getBlockState(position);
            if (state.getBlock() instanceof DoorBlock
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) return true;
        }
        return false;
    }

    private static void check(State state, boolean valid, String issue) {
        state.checks++;
        if (!valid) state.issues.add(issue);
    }

    private static final class State {
        private int checks;
        private final List<String> issues = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }
}
