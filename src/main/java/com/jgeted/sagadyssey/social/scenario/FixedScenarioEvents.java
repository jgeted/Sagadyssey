package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;
import com.jgeted.sagadyssey.core.structure.StructureConfig;
import com.jgeted.sagadyssey.npc.entity.FarmMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcResidenceFixtures;
import com.jgeted.sagadyssey.social.life.NpcWorkShift;
import com.jgeted.sagadyssey.social.life.NpcLongTermGoal;
import com.jgeted.sagadyssey.social.life.NpcMemoryRecord;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdFixtures;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.household.HouseholdRole;
import com.jgeted.sagadyssey.social.politics.PoliticalRelation;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.verifier.VerifierSnapshot;
import com.jgeted.sagadyssey.worldsim.verifier.VerifierSnapshotCapture;
import com.jgeted.sagadyssey.worldsim.verifier.VerifierSnapshotStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 在主线程上分批部署固定场景，任务进度持久化且不会使用异步世界写入。 */
public final class FixedScenarioEvents {
    private static final int TERRAIN_BLOCKS_PER_TICK = 384;
    private static final int NPCS_PER_TICK = 4;

    private FixedScenarioEvents() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        FixedScenarioData data = FixedScenarioData.get(server);
        if (server.overworld().getGameTime() % 200L == 0L) tickScenarioEconomy(server, data);
        // 同一 tick 只推进一个任务，防止多个维度同时部署造成方块更新尖峰。
        FixedScenarioDeployment deployment = data.deployments().stream()
                .filter(FixedScenarioDeployment::active).findFirst().orElse(null);
        if (deployment == null) return;
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        ServerLevel level = level(server, deployment.dimension());
        if (definition == null || level == null) {
            deployment.fail("场景定义或目标维度不存在");
            data.changed();
            return;
        }

        try {
            process(server, level, data, deployment, definition);
        } catch (RuntimeException exception) {
            Sagadyssey.LOGGER.error("固定场景部署失败: {}", deployment.id(), exception);
            deployment.fail(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            data.changed();
            notifyRequester(server, deployment, "§c固定世界生成失败：" + deployment.error());
        }
    }

    private static void process(MinecraftServer server, ServerLevel level, FixedScenarioData data,
                                FixedScenarioDeployment deployment, FixedScenarioDefinition definition) {
        switch (deployment.phase()) {
            case SETTLEMENTS -> processSettlements(server, data, deployment, definition);
            case TERRAIN -> processTerrain(level, data, deployment, definition);
            case STRUCTURES -> processStructure(server, level, data, deployment, definition);
            case WAREHOUSES -> processWarehouse(server, level, data, deployment, definition);
            case NPCS -> processNpcs(level, data, deployment, definition);
            case FINALIZE -> finalizeScenario(server, level, data, deployment, definition);
            default -> { }
        }
    }

    private static void processSettlements(MinecraftServer server, FixedScenarioData data,
                                           FixedScenarioDeployment deployment,
                                           FixedScenarioDefinition definition) {
        if (deployment.cursor() >= definition.settlements().size()) {
            deployment.nextPhase();
            data.changed();
            notifyRequester(server, deployment, "§e固定世界：开始铺设道路、广场和带水农田");
            return;
        }
        var spec = definition.settlements().get(deployment.cursor());
        UUID id = FixedScenarioData.settlementId(deployment, spec.key());
        SettlementRegistryData.get(server).createWithId(server, id, spec.name(), spec.type(),
                deployment.dimension(), deployment.anchor().offset(spec.x(), 0, spec.z()), spec.radius(),
                spec.factionId(), null, server.overworld().getGameTime());
        deployment.advance();
        data.changed();
    }

    private static void processTerrain(ServerLevel level, FixedScenarioData data,
                                       FixedScenarioDeployment deployment,
                                       FixedScenarioDefinition definition) {
        List<FixedScenarioDefinition.TerrainBlock> terrain = definition.terrain();
        int end = Math.min(terrain.size(), deployment.cursor() + TERRAIN_BLOCKS_PER_TICK);
        for (int i = deployment.cursor(); i < end; i++) placeTerrain(level, deployment.anchor(), terrain.get(i));
        while (deployment.cursor() < end) deployment.advance();
        if (deployment.cursor() >= terrain.size()) {
            deployment.nextPhase();
            notifyRequester(level.getServer(), deployment, "§e固定世界：开始分批放置建筑");
        }
        data.changed();
    }

    static void placeTerrain(ServerLevel level, BlockPos anchor,
                             FixedScenarioDefinition.TerrainBlock terrain) {
        BlockPos ground = anchor.offset(terrain.x(), terrain.y() - 1, terrain.z());
        if (!level.hasChunkAt(ground)) level.getChunkAt(ground);
        switch (terrain.kind()) {
            case ROAD -> level.setBlock(ground, Blocks.GRAVEL.defaultBlockState(), 2);
            case PLAZA -> level.setBlock(ground, Blocks.COBBLESTONE.defaultBlockState(), 2);
            case WATER -> {
                level.setBlock(ground, Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(ground.above(), Blocks.LILY_PAD.defaultBlockState(), 3);
            }
            case FARMLAND_MATURE -> placeFarmland(level, ground, CropBlock.MAX_AGE);
            case FARMLAND_GROWING -> placeFarmland(level, ground,
                    3 + Math.floorMod(terrain.x() * 13 + terrain.z() * 7, 4));
            case FARMLAND_EMPTY -> placeFarmland(level, ground, -1);
            case FENCE -> level.setBlock(ground.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);
            case FARM_GATE -> level.setBlock(ground.above(), Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING, Direction.NORTH), 3);
            case COMPOSTER -> level.setBlock(ground.above(), Blocks.COMPOSTER.defaultBlockState(), 3);
            case STONE -> level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
            case COAL_ORE -> level.setBlock(ground, Blocks.COAL_ORE.defaultBlockState(), 2);
            case IRON_ORE -> level.setBlock(ground, Blocks.IRON_ORE.defaultBlockState(), 2);
            case FURNACE -> level.setBlock(ground.above(), Blocks.FURNACE.defaultBlockState(), 3);
            case BLAST_FURNACE -> level.setBlock(ground.above(), Blocks.BLAST_FURNACE.defaultBlockState(), 3);
            case ANVIL -> level.setBlock(ground.above(), Blocks.ANVIL.defaultBlockState(), 3);
            case SMITHING_TABLE -> level.setBlock(ground.above(), Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            case BELL -> level.setBlock(ground.above(), Blocks.BELL.defaultBlockState(), 3);
        }
    }

    private static void placeFarmland(ServerLevel level, BlockPos ground, int wheatAge) {
        level.setBlock(ground, Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 2);
        BlockPos crop = ground.above();
        if (wheatAge < 0) {
            if (!level.getBlockState(crop).isAir()) level.setBlock(crop, Blocks.AIR.defaultBlockState(), 2);
            return;
        }
        level.setBlock(crop, Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, Math.min(CropBlock.MAX_AGE, wheatAge)), 2);
    }

    private static void processStructure(MinecraftServer server, ServerLevel level, FixedScenarioData data,
                                         FixedScenarioDeployment deployment,
                                         FixedScenarioDefinition definition) {
        if (deployment.cursor() >= definition.placements().size()) {
            deployment.nextPhase();
            data.changed();
            notifyRequester(server, deployment, "§e固定世界：开始建立实体仓库和初始库存");
            return;
        }
        var placement = definition.placements().get(deployment.cursor());
        StructureConfig config = SagadysseyStructures.getConfig(placement.templateId());
        String templatePath = config == null ? placement.templateId() : config.getNbtFileName();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, templatePath);
        Optional<StructureTemplate> template = level.getStructureManager().get(location);
        if (template.isEmpty()) throw new IllegalStateException("找不到建筑模板 " + location);

        BlockPos position = deployment.anchor().offset(placement.x(), 0, placement.z());
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(placement.rotation())
                .setIgnoreEntities(true);
        int removedMarkers = removeLegacyTemplateMarkers(level, template.get(), position, settings);
        if (removedMarkers > 0) {
            Sagadyssey.LOGGER.info("固定场景建筑 {} 清理了 {} 个旧结构标记", placement.templateId(), removedMarkers);
        }
        settings.addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
        BoundingBox box = template.get().getBoundingBox(settings, position);
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) level.getChunk(chunkX, chunkZ);
        }
        boolean placed = template.get().placeInWorld(level, position, position, settings,
                RandomSource.create(deployment.id().getLeastSignificantBits() + deployment.cursor()), 2);
        if (!placed) throw new IllegalStateException("模板没有放置任何方块: " + location);

        String category = config == null ? "unknown" : config.getCategory();
        String size = config == null ? "unknown" : config.getSize();
        SettlementRegistryData.get(server).registerScenarioBuilding(server, deployment.dimension(), position,
                placement.templateId(), category, size,
                FixedScenarioData.settlementId(deployment, placement.settlementKey()), level.getGameTime());
        deployment.advance();
        data.changed();
    }

    /** 只清理模板自身记录的结构方块坐标，不扫描或修改建筑范围内的其他方块。 */
    private static int removeLegacyTemplateMarkers(ServerLevel level, StructureTemplate template,
                                                   BlockPos position, StructurePlaceSettings settings) {
        int removed = 0;
        for (StructureTemplate.StructureBlockInfo marker
                : template.filterBlocks(position, settings, Blocks.STRUCTURE_BLOCK)) {
            BlockPos markerPos = marker.pos();
            if (!level.hasChunkAt(markerPos)) level.getChunkAt(markerPos);
            if (level.getBlockState(markerPos).is(Blocks.STRUCTURE_BLOCK)) {
                level.setBlock(markerPos, Blocks.AIR.defaultBlockState(), 3);
                removed++;
            }
        }
        return removed;
    }

    private static void processWarehouse(MinecraftServer server, ServerLevel level, FixedScenarioData data,
                                         FixedScenarioDeployment deployment,
                                         FixedScenarioDefinition definition) {
        if (deployment.cursor() >= definition.warehouses().size()) {
            deployment.nextPhase();
            data.changed();
            notifyRequester(server, deployment, "§e固定世界：开始生成初始居民");
            return;
        }
        var spec = definition.warehouses().get(deployment.cursor());
        BlockPos position = deployment.anchor().offset(spec.x(), 0, spec.z());
        LogisticsData logistics = LogisticsData.get(server);
        if (!level.hasChunkAt(position)) level.getChunkAt(position);
        var warehouse = logistics.warehouseAt(deployment.dimension(), position);
        Container container = WarehouseContainers.asContainer(level.getBlockEntity(position));
        if (container == null) {
            level.setBlock(position, (spec.chest() ? Blocks.CHEST : Blocks.BARREL).defaultBlockState(), 3);
            container = WarehouseContainers.asContainer(level.getBlockEntity(position));
            if (container == null) throw new IllegalStateException("无法在 " + position.toShortString() + " 建立仓库");
            fillInitialStock(container, spec.initialStock());
        }
        if (warehouse == null) {
            UUID institution = UUID.nameUUIDFromBytes((deployment.id() + "|institution|" + spec.settlementKey())
                    .getBytes(StandardCharsets.UTF_8));
            logistics.registerWarehouse(server, FixedScenarioData.settlementId(deployment, spec.settlementKey()),
                    spec.name(), deployment.dimension(), position, institution, level.getGameTime());
        }
        deployment.advance();
        data.changed();
    }

    static void fillInitialStock(Container container, Map<String, Integer> stock) {
        int slot = 0;
        for (var entry : stock.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            int remaining = Math.max(0, entry.getValue());
            while (remaining > 0 && slot < container.getContainerSize()) {
                ItemStack stack = new ItemStack(item, Math.min(remaining, item.getDefaultMaxStackSize()));
                container.setItem(slot++, stack);
                remaining -= stack.getCount();
            }
        }
        container.setChanged();
    }

    private static void processNpcs(ServerLevel level, FixedScenarioData data,
                                    FixedScenarioDeployment deployment,
                                    FixedScenarioDefinition definition) {
        int end = Math.min(definition.npcs().size(), deployment.cursor() + NPCS_PER_TICK);
        for (int index = deployment.cursor(); index < end; index++) spawnNpc(level, deployment,
                definition, definition.npcs().get(index), index);
        while (deployment.cursor() < end) deployment.advance();
        if (deployment.cursor() >= definition.npcs().size()) deployment.nextPhase();
        data.changed();
    }

    private static void spawnNpc(ServerLevel level, FixedScenarioDeployment deployment,
                                 FixedScenarioDefinition definition,
                                 FixedScenarioDefinition.NpcSpec spec, int index) {
        UUID id = FixedScenarioData.npcId(deployment, index);
        CharacterRegistryData characters = CharacterRegistryData.get(level.getServer());
        CharacterRecord registered = characters.byId(id);
        if (registered != null && !registered.alive()) {
            Sagadyssey.LOGGER.info("不复活固定场景中的已故人物 {} ({})", registered.name(), id);
            return;
        }
        BlockPos position = findSpawnPosition(level, deployment.anchor().offset(spec.x(), 0, spec.z()));
        if (!level.hasChunkAt(position)) level.getChunkAt(position);
        var existing = level.getEntity(id);
        if (existing != null) {
            if (!(existing instanceof NpcBase npc))
                throw new IllegalStateException("场景 NPC UUID 被其他实体占用: " + id);
            characters.ensureNpcCharacter(npc.getCharacterId(), spec.name(), spec.socialClass(), spec.office(),
                    spec.factionId(), level.getGameTime());
            applyInstitutionAssignment(npc, deployment, definition, spec);
            registerScenarioLife(level, npc, deployment, definition, spec, index);
            if (deployment.repair()) {
                if (npc.isOwned()) {
                    Sagadyssey.LOGGER.info("场景修复保留已招募 NPC {} 的玩家配置", spec.name());
                    return;
                }
                repairNpcWorkAssignment(npc, deployment, definition, spec, position);
                return;
            }
            applyNpcSpec(npc, deployment, definition, spec, position);
            return;
        }

        if (deployment.repair() && registered != null) {
            Sagadyssey.LOGGER.warn("场景修复没有复活缺失或未加载的 NPC: {} ({})", spec.name(), id);
            return;
        }

        NpcBase npc = new NpcBase(NpcEntityTypes.NPC_BASE.get(), level);
        npc.setUUID(id);
        applyNpcSpec(npc, deployment, definition, spec, position);
        registerScenarioLife(level, npc, deployment, definition, spec, index);
        npc.setPersistenceRequired();
        if (!level.addFreshEntity(npc)) throw new IllegalStateException("NPC UUID 冲突: " + id);
        characters.ensureNpcCharacter(npc.getCharacterId(), spec.name(), spec.socialClass(), spec.office(),
                spec.factionId(), level.getGameTime());
    }

    /** 修复工作区；旧版本迁移还会补齐岗位，并解除生产职业遗留的骑乘阻塞。 */
    private static void repairNpcWorkAssignment(NpcBase npc, FixedScenarioDeployment deployment,
                                                FixedScenarioDefinition definition,
                                                FixedScenarioDefinition.NpcSpec spec, BlockPos position) {
        if (deployment.previousDefinitionRevision() < 4) {
            boolean movedWorksite = spec.profession() == NpcProfession.WORKER
                    || spec.profession() == NpcProfession.BLACKSMITH
                    || spec.profession() == NpcProfession.TRADER
                    || spec.profession() == NpcProfession.COURIER;
            if (movedWorksite) {
                npc.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
                npc.setCommand(spec.command());
            } else if (spec.command() == NpcCommand.PATROL && npc.getCommand() == NpcCommand.STAY) {
                npc.setCommand(NpcCommand.PATROL);
            }
        }
        if (deployment.previousDefinitionRevision() < 5 && spec.profession() == NpcProfession.WORKER) {
            npc.setNpcName(spec.name());
        }
        if (deployment.previousDefinitionRevision() < 6
                && (spec.profession() == NpcProfession.WORKER
                || spec.profession() == NpcProfession.BLACKSMITH
                || spec.profession() == NpcProfession.FARMER)) {
            npc.dismountToBind();
            npc.setLeadMountMode(true);
        }
        if (spec.workZoneKey() == null) return;
        if (npc.getProfession() == NpcProfession.FARMER) {
            npc.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
            npc.setFarmMode(FarmMode.CROP);
        }
        applyWorkZone(npc, deployment, definition, spec.workZoneKey());
        if (spec.command() == NpcCommand.WORK) npc.setCommand(NpcCommand.WORK);
    }

    static void applyNpcSpec(NpcBase npc, FixedScenarioDeployment deployment,
                             FixedScenarioDefinition definition,
                             FixedScenarioDefinition.NpcSpec spec, BlockPos position) {
        npc.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.setProfession(spec.profession());
        npc.setNpcName(spec.name());
        npc.setIdentity(spec.socialClass(), spec.office());
        Faction faction = FactionRegistry.get(spec.factionId());
        npc.setFaction(faction != null ? faction : FactionRegistry.get("sagadyssey:wilderness"));
        applyInstitutionAssignment(npc, deployment, definition, spec);
        if (spec.workZoneKey() == null) {
            npc.clearWorkZone();
        } else {
            applyWorkZone(npc, deployment, definition, spec.workZoneKey());
            if (spec.profession() == NpcProfession.FARMER) {
                npc.setFarmMode(FarmMode.CROP);
            }
        }
        npc.setCommand(spec.command());
    }

    private static void applyWorkZone(NpcBase npc, FixedScenarioDeployment deployment,
                                      FixedScenarioDefinition definition, String workZoneKey) {
        FixedScenarioDefinition.WorkZoneSpec zone = definition.workZone(workZoneKey);
        if (zone == null) throw new IllegalStateException("找不到 NPC 工作区: " + workZoneKey);
        BlockPos min = deployment.anchor().offset(zone.minX(), zone.minY(), zone.minZ());
        BlockPos max = deployment.anchor().offset(zone.maxX(), zone.maxY(), zone.maxZ());
        npc.setWorkZone(min, max);
    }

    /** 固定居民属于聚落机构；招募后仓库权限仍优先使用玩家角色，不会继承机构权限。 */
    private static void applyInstitutionAssignment(NpcBase npc, FixedScenarioDeployment deployment,
                                                   FixedScenarioDefinition definition,
                                                   FixedScenarioDefinition.NpcSpec spec) {
        FixedScenarioDefinition.SettlementSpec settlement = definition.settlement(spec.settlementKey());
        if (settlement == null) throw new IllegalStateException("找不到 NPC 所属聚落: " + spec.settlementKey());
        npc.setHomeSettlementId(FixedScenarioData.settlementId(deployment, settlement.key()));
        if (npc.getCommand() == NpcCommand.PATROL || spec.command() == NpcCommand.PATROL) {
            BlockPos center = deployment.anchor().offset(settlement.x(), 0, settlement.z());
            npc.setPatrol(center, Math.max(8, Math.min(24, settlement.radius() / 4)));
        }
    }

    /**
     * r7 起，每位固定居民拥有独立生活档案、真正的私人床和私人储物桶。
     * 房屋采用小型实体住宅补齐，避免把聚落公共仓库误当成 NPC 私人物品。
     */
    private static void registerScenarioLife(ServerLevel level, NpcBase npc,
                                             FixedScenarioDeployment deployment,
                                             FixedScenarioDefinition definition,
                                             FixedScenarioDefinition.NpcSpec spec, int npcIndex) {
        NpcLifeData lifeData = NpcLifeData.get(level.getServer());
        lifeData.captureInstitutionWorkZone(npc);
        NpcLifeRecord existing = lifeData.record(npc.getCharacterId());

        FixedScenarioDefinition.SettlementSpec settlement = definition.settlement(spec.settlementKey());
        if (settlement == null) return;
        int residentIndex = 0;
        for (int index = 0; index < npcIndex; index++) {
            if (definition.npcs().get(index).settlementKey().equals(spec.settlementKey())) residentIndex++;
        }
        BlockPos center = deployment.anchor().offset(settlement.x(), 0, settlement.z());
        if (existing != null) {
            if (!deployment.repair()) return;
            BlockPos normalizedBed = NpcResidenceFixtures.normalizeBedHead(level, existing.bedPosition());
            boolean bedValid = normalizedBed != null
                    && level.getBlockState(normalizedBed).is(BlockTags.BEDS);
            boolean storageValid = existing.privateStoragePosition() != null
                    && WarehouseContainers.asContainer(level.getBlockEntity(
                    existing.privateStoragePosition())) != null;
            if (bedValid && storageValid) {
                NpcResidenceFixtures.ensureExistingRoomAccess(level, normalizedBed,
                        existing.privateStoragePosition());
                if (!normalizedBed.equals(existing.bedPosition())) {
                    lifeData.updateResidence(npc.getCharacterId(), existing.residenceName(), normalizedBed,
                            existing.privateStoragePosition(), level.getGameTime());
                }
                return;
            }
            NpcResidenceFixtures.Result replacement = NpcResidenceFixtures.ensurePrivateRoom(level,
                    center, settlement.name(), residentIndex, lifeData);
            registerResidenceBuilding(level, deployment, settlement, replacement);
            lifeData.updateResidence(npc.getCharacterId(), replacement.name(), replacement.bed(),
                    replacement.storage(), level.getGameTime());
            return;
        }
        NpcResidenceFixtures.Result residence = NpcResidenceFixtures.ensurePrivateRoom(level, center,
                settlement.name(), residentIndex, lifeData);
        registerResidenceBuilding(level, deployment, settlement, residence);
        BlockPos workplace = scenarioWorkplace(deployment, definition, spec, center);
        NpcWorkShift shift = scenarioShift(definition, spec, npcIndex);
        lifeData.registerInstitutional(npc, deployment.dimension(),
                FixedScenarioData.settlementId(deployment, settlement.key()), workplace,
                residence.name(), residence.bed(), residence.storage(), shift,
                level.getGameTime(), deployment.repair());
        if (npc.isOwned() && npc.getOwnerCharacterId() != null) {
            lifeData.onRecruited(npc, npc.getOwnerCharacterId(), level.getGameTime());
        }
    }

    private static void registerResidenceBuilding(ServerLevel level,
                                                  FixedScenarioDeployment deployment,
                                                  FixedScenarioDefinition.SettlementSpec settlement,
                                                  NpcResidenceFixtures.Result residence) {
        if (residence.origin() == null) return;
        SettlementRegistryData.get(level.getServer()).registerScenarioBuilding(level.getServer(),
                deployment.dimension(), residence.origin(), "generated_private_room",
                "residence", "tiny", FixedScenarioData.settlementId(deployment, settlement.key()),
                level.getGameTime());
    }

    private static BlockPos scenarioWorkplace(FixedScenarioDeployment deployment,
                                              FixedScenarioDefinition definition,
                                              FixedScenarioDefinition.NpcSpec spec,
                                              BlockPos settlementCenter) {
        if (spec.workplace() != null) {
            return deployment.anchor().offset(spec.workplace().x(), 0, spec.workplace().z());
        }
        if (spec.workZoneKey() != null) {
            FixedScenarioDefinition.WorkZoneSpec zone = definition.workZone(spec.workZoneKey());
            if (zone != null) return deployment.anchor().offset(
                    (zone.minX() + zone.maxX()) / 2, 0, (zone.minZ() + zone.maxZ()) / 2);
        }
        return spec.command() == NpcCommand.PATROL ? settlementCenter
                : deployment.anchor().offset(spec.x(), 0, spec.z());
    }

    private static NpcWorkShift scenarioShift(FixedScenarioDefinition definition,
                                              FixedScenarioDefinition.NpcSpec spec, int index) {
        if ("guard_lab".equals(definition.id())) return NpcWorkShift.DAY;
        if (spec.office() == NpcOffice.HIGH_RULER || spec.office() == NpcOffice.LOCAL_RULER) {
            return NpcWorkShift.FLEXIBLE;
        }
        boolean guard = spec.profession() == NpcProfession.WARRIOR
                || spec.profession() == NpcProfession.ARCHER
                || spec.profession() == NpcProfession.HEAVY;
        return guard && (index & 1) == 0 ? NpcWorkShift.NIGHT : NpcWorkShift.DAY;
    }

    static BlockPos findSpawnPosition(ServerLevel level, BlockPos desired) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = 0; dy <= 4; dy++) {
                        BlockPos feet = desired.offset(dx, dy, dz);
                        if (!level.hasChunkAt(feet)) level.getChunkAt(feet);
                        if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty())
                            return feet;
                    }
                }
            }
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                desired.getX(), desired.getZ());
        return new BlockPos(desired.getX(), surface, desired.getZ());
    }

    private static void finalizeScenario(MinecraftServer server, ServerLevel level,
                                         FixedScenarioData data,
                                         FixedScenarioDeployment deployment,
                                         FixedScenarioDefinition definition) {
        PoliticalStateData politics = PoliticalStateData.get(server);
        long gameTime = server.overworld().getGameTime();
        boolean bootstrapPolitics = !deployment.repair() || deployment.previousDefinitionRevision() < 3;
        if (bootstrapPolitics) initializeNpcPolitics(server, deployment, definition, politics, gameTime);
        if (!deployment.repair() || deployment.previousDefinitionRevision() < 8) {
            initializeNpcAgency(server, deployment, definition, gameTime);
        }
        if (!deployment.repair() || deployment.previousDefinitionRevision() < 9) {
            initializeHouseholds(server, level, deployment, definition, gameTime);
        }
        if (!deployment.repair() && "small_realm".equals(definition.id())) {
            politics.setRelation(server, "sagadyssey:kingdom", "sagadyssey:bandit",
                    PoliticalRelation.WAR, gameTime);
            politics.setRelation(server, "sagadyssey:kingdom", "sagadyssey:merchant_guild",
                    PoliticalRelation.FRIENDLY, gameTime);
        }
        WorldHistoryData.get(server).append(gameTime,
                deployment.repair() ? "fixed_world_repaired" : "fixed_world_generated", deployment.id(),
                definition.displayName(), deployment.dimension() + " " + deployment.anchor().toShortString(),
                deployment.repair() ? 25 : 95);
        deployment.nextPhase();
        data.changed();
        if (!deployment.repair() && ScenarioLabController.isLab(definition.id())) {
            ScenarioLabController.apply(level, deployment, definition,
                    ScenarioLabController.defaultProfile(definition.id()));
        }
        FixedScenarioVerifier.Result verification = FixedScenarioVerifier.verify(server,
                level, deployment, definition);
        if (!verification.passed()) {
            Sagadyssey.LOGGER.warn("固定场景 {} 完成，但自动验收发现 {} 个问题: {}",
                    deployment.id(), verification.issues().size(), verification.issues());
        }
        VerifierSnapshot automaticBaseline = null;
        if (!deployment.repair()) {
            ServerPlayer requester = deployment.requestedBy() == null ? null
                    : server.getPlayerList().getPlayer(deployment.requestedBy());
            automaticBaseline = VerifierSnapshotCapture.capture(server, requester, "before");
            VerifierSnapshotStore.put(server, automaticBaseline);
        }
        tickScenarioEconomy(server, data);
        VerifierSnapshot savedBaseline = automaticBaseline;
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6[世界生成] "
                + (verification.passed() ? "§a" : "§c") + definition.displayName() + " 已完成；自动验收"
                + (verification.passed() ? "通过" : "发现 " + verification.issues().size() + " 个问题")
                + (savedBaseline == null ? "" : "；已保存验收起点 before（重启保留）")
                + "。可使用 /saga scenario verify 和 /saga audit 复查。"), false);
    }

    /** 为固定人物种下方向与关系，不把这些背景写成永远不可改变的行为脚本。 */
    private static void initializeNpcAgency(MinecraftServer server,
                                            FixedScenarioDeployment deployment,
                                            FixedScenarioDefinition definition, long gameTime) {
        NpcLifeData life = NpcLifeData.get(server);
        NpcLongTermGoal[] smallRealmGoals = {
                NpcLongTermGoal.GAIN_STATUS,
                NpcLongTermGoal.DEFEND_HOME,
                NpcLongTermGoal.DEFEND_HOME,
                NpcLongTermGoal.PROTECT_FAMILY,
                NpcLongTermGoal.MASTER_CRAFT,
                NpcLongTermGoal.PROSPER,
                NpcLongTermGoal.PROSPER,
                NpcLongTermGoal.PROTECT_FAMILY,
                NpcLongTermGoal.PROSPER,
                NpcLongTermGoal.PROTECT_FAMILY,
                NpcLongTermGoal.MASTER_CRAFT,
                NpcLongTermGoal.DEFEND_HOME,
                NpcLongTermGoal.DEFEND_HOME,
                NpcLongTermGoal.PROTECT_FAMILY,
                NpcLongTermGoal.GAIN_STATUS,
                NpcLongTermGoal.PROSPER,
                NpcLongTermGoal.SURVIVE,
                NpcLongTermGoal.SERVE_FAITH,
                NpcLongTermGoal.SERVE_FAITH,
                NpcLongTermGoal.PROSPER
        };
        for (int index = 0; index < definition.npcs().size(); index++) {
            UUID npcId = FixedScenarioData.npcId(deployment, index);
            NpcLongTermGoal goal = "small_realm".equals(definition.id())
                    ? smallRealmGoals[Math.min(index, smallRealmGoals.length - 1)]
                    : defaultGoal(definition.npcs().get(index));
            life.setLongTermGoal(npcId, goal, gameTime);
            life.remember(npcId, new NpcMemoryRecord("background", null,
                    backgroundMemory(definition.npcs().get(index), goal),
                    goal == NpcLongTermGoal.SURVIVE ? -10 : 20, 45,
                    gameTime, -1L), gameTime);
        }

        if ("small_realm".equals(definition.id())) {
            link(life, deployment, 1, 2, NpcRelationKind.MENTOR, 65, "共同守卫河谷镇");
            link(life, deployment, 1, 3, NpcRelationKind.MENTOR, 58, "共同守卫河谷镇");
            link(life, deployment, 4, 10, NpcRelationKind.FRIEND, 45, "矿石与锻造相互依赖");
            link(life, deployment, 5, 6, NpcRelationKind.FRIEND, 55, "长期共同跑商");
            link(life, deployment, 5, 19, NpcRelationKind.RIVAL, 32, "竞争河谷南路市场");
            link(life, deployment, 7, 8, NpcRelationKind.FAMILY, 72, "村长照料的近亲");
            link(life, deployment, 8, 9, NpcRelationKind.FRIEND, 60, "共同经营麦田");
            link(life, deployment, 11, 12, NpcRelationKind.MENTOR, 68, "北境军中师徒");
            link(life, deployment, 11, 13, NpcRelationKind.MENTOR, 58, "北境军中师徒");
            link(life, deployment, 14, 15, NpcRelationKind.MENTOR, 50, "灰旗营地的旧部");
            link(life, deployment, 14, 16, NpcRelationKind.DEPENDENT, 42, "依赖头目分配战利品");
            link(life, deployment, 17, 18, NpcRelationKind.MENTOR, 70, "圣所中的师徒与同道");
        }
        if (!"small_realm".equals(definition.id())) {
            for (FixedScenarioDefinition.HouseholdSpec household : definition.households()) {
                link(life, deployment, household.headIndex(), household.memberIndex(),
                        household.memberRole() == HouseholdRole.KIN
                                ? NpcRelationKind.FAMILY : NpcRelationKind.DEPENDENT,
                        70, "共同生活于" + household.name());
            }
        }
    }

    private static NpcLongTermGoal defaultGoal(FixedScenarioDefinition.NpcSpec spec) {
        if (spec.office() == NpcOffice.HIGH_RULER || spec.office() == NpcOffice.SOVEREIGN
                || spec.office() == NpcOffice.LOCAL_RULER) return NpcLongTermGoal.GAIN_STATUS;
        return switch (spec.profession()) {
            case WARRIOR, ARCHER, HEAVY -> NpcLongTermGoal.DEFEND_HOME;
            case WORKER, BLACKSMITH -> NpcLongTermGoal.MASTER_CRAFT;
            case FARMER, TRADER, COURIER -> NpcLongTermGoal.PROSPER;
            case MEDIC -> NpcLongTermGoal.PROTECT_FAMILY;
            case BARD -> NpcLongTermGoal.SERVE_FAITH;
            case NONE -> NpcLongTermGoal.SURVIVE;
        };
    }

    private static void link(NpcLifeData life, FixedScenarioDeployment deployment,
                             int first, int second, NpcRelationKind kind,
                             int strength, String note) {
        life.linkRelationship(FixedScenarioData.npcId(deployment, first),
                FixedScenarioData.npcId(deployment, second), kind, strength, note);
    }

    private static String backgroundMemory(FixedScenarioDefinition.NpcSpec spec,
                                           NpcLongTermGoal goal) {
        return "在 " + spec.settlementKey() + " 以 " + spec.profession().name()
                + " 身份生活；目前希望" + goal.displayName();
    }

    /** 家庭关系由场景定义提供，小实验场不会继承裂冠河谷的固定成员索引。 */
    private static void initializeHouseholds(MinecraftServer server, ServerLevel level,
                                             FixedScenarioDeployment deployment,
                                             FixedScenarioDefinition definition, long gameTime) {
        for (FixedScenarioDefinition.HouseholdSpec household : definition.households()) {
            createScenarioHousehold(server, level, deployment, household.key(), household.name(),
                    household.settlementKey(), household.headIndex(), household.memberIndex(),
                    household.memberRole(), household.initialStock(), gameTime);
        }
    }

    private static void createScenarioHousehold(MinecraftServer server, ServerLevel level,
                                                FixedScenarioDeployment deployment, String key,
                                                String name, String settlementKey,
                                                int headIndex, int memberIndex, HouseholdRole memberRole,
                                                Map<String, Integer> initialStock, long gameTime) {
        HouseholdData households = HouseholdData.get(server);
        NpcLifeData lives = NpcLifeData.get(server);
        UUID id = UUID.nameUUIDFromBytes((deployment.id() + "|household|" + key)
                .getBytes(StandardCharsets.UTF_8));
        UUID headId = FixedScenarioData.npcId(deployment, headIndex);
        UUID memberId = FixedScenarioData.npcId(deployment, memberIndex);
        NpcLifeRecord headLife = lives.record(headId);
        BlockPos home = headLife != null && headLife.bedPosition() != null
                ? headLife.bedPosition() : deployment.anchor();
        HouseholdRecord household = households.createWithId(id, name, deployment.dimension(),
                FixedScenarioData.settlementId(deployment, settlementKey), headId, home, gameTime);
        if (household == null) return;
        households.addMember(id, memberId, memberRole, gameTime);
        household = households.byId(id);
        if (household == null) return;
        BlockPos storage = household.sharedStorage();
        if (storage != null && !level.hasChunkAt(storage)) level.getChunkAt(storage);
        boolean valid = storage != null && level.hasChunkAt(storage)
                && WarehouseContainers.asContainer(level.getBlockEntity(storage)) != null;
        if (valid) return;
        storage = HouseholdFixtures.ensureSharedPantry(level, home, households, lives);
        if (storage == null || !households.assignSharedStorage(id, deployment.dimension(), home, storage)) return;
        Container container = WarehouseContainers.asContainer(level.getBlockEntity(storage));
        if (container != null) fillInitialStock(container, initialStock);
    }

    /**
     * 固定场景的两座近距离市场只发布需求，不直接转移库存；
     * 商人或运输员必须从真实箱子取货、携带并送入另一只箱子。
     */
    private static void tickScenarioEconomy(MinecraftServer server, FixedScenarioData scenarioData) {
        for (FixedScenarioDeployment deployment : scenarioData.deployments()) {
            if (deployment.phase() != FixedScenarioDeployment.Phase.COMPLETE
                    || deployment.definitionRevision() < 4
                    || !"small_realm".equals(deployment.scenarioId())) continue;
            ServerLevel level = level(server, deployment.dimension());
            if (level == null) continue;
            BlockPos minePos = deployment.anchor().offset(160, 0, 30);
            BlockPos grainPos = deployment.anchor().offset(205, 0, 30);
            if (!level.hasChunkAt(minePos) || !level.hasChunkAt(grainPos)) continue;

            LogisticsData logistics = LogisticsData.get(server);
            WarehouseRecord mine = logistics.warehouseAt(deployment.dimension(), minePos);
            WarehouseRecord grain = logistics.warehouseAt(deployment.dimension(), grainPos);
            Container mineChest = WarehouseContainers.asContainer(level.getBlockEntity(minePos));
            Container grainChest = WarehouseContainers.asContainer(level.getBlockEntity(grainPos));
            if (mine == null || grain == null || mineChest == null || grainChest == null) continue;
            long gameTime = level.getGameTime();

            boolean coalDemand = countItem(grainChest, Items.COAL) < 16
                    && countItem(mineChest, Items.COAL) >= 32;
            if (coalDemand && createScenarioOrder(logistics, mine, grain, Items.COAL, 16,
                    deployment.id(), gameTime)) {
                createScenarioOrder(logistics, grain, mine, Items.EMERALD, 4, deployment.id(), gameTime);
            }

            boolean wheatDemand = countItem(mineChest, Items.WHEAT) < 24
                    && countItem(grainChest, Items.WHEAT) >= 48;
            if (wheatDemand && createScenarioOrder(logistics, grain, mine, Items.WHEAT, 24,
                    deployment.id(), gameTime)) {
                createScenarioOrder(logistics, mine, grain, Items.EMERALD, 3, deployment.id(), gameTime);
            }
        }
    }

    private static boolean createScenarioOrder(LogisticsData logistics, WarehouseRecord source,
                                               WarehouseRecord destination, Item item, int count,
                                               UUID issuer, long gameTime) {
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        if (logistics.hasOpenOrder(source.id(), destination.id(), itemId)) return false;
        return logistics.createOrder(source.id(), destination.id(), itemId, count, issuer, gameTime) != null;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /**
     * 只在首次生成或从旧场景版本迁移时建立初始政治结构。
     * 已有人持有的头衔和已有封臣关系不会被覆盖。
     */
    private static void initializeNpcPolitics(MinecraftServer server,
                                              FixedScenarioDeployment deployment,
                                              FixedScenarioDefinition definition,
                                              PoliticalStateData politics, long gameTime) {
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        Map<String, CharacterRecord> highRulers = new HashMap<>();
        Map<String, CharacterRecord> settlementHolders = new HashMap<>();

        for (int index = 0; index < definition.npcs().size(); index++) {
            FixedScenarioDefinition.NpcSpec spec = definition.npcs().get(index);
            CharacterRecord character = characters.byId(FixedScenarioData.npcId(deployment, index));
            if (character == null || !character.alive()) continue;

            if (spec.office() == NpcOffice.HIGH_RULER || spec.office() == NpcOffice.SOVEREIGN) {
                highRulers.putIfAbsent(spec.factionId(), character);
            }
            if (spec.office().precedence() < NpcOffice.LOCAL_RULER.precedence()) continue;

            FixedScenarioDefinition.SettlementSpec settlement = definition.settlement(spec.settlementKey());
            if (settlement == null) continue;
            UUID settlementId = FixedScenarioData.settlementId(deployment, settlement.key());
            UUID holderId = politics.titleHolder(settlementId);
            if (holderId == null) {
                politics.grantTitle(server, settlementId, settlement.name(), settlement.factionId(),
                        character.id(), character.name(), gameTime);
                holderId = character.id();
            }
            CharacterRecord holder = characters.byId(holderId);
            if (holder != null && holder.alive()) settlementHolders.put(settlement.key(), holder);

            if (spec.office() == NpcOffice.SOVEREIGN && politics.ruler(spec.factionId()) == null) {
                politics.appointRuler(server, spec.factionId(), character.id(), character.name(), gameTime);
            }
        }

        for (int index = 0; index < definition.npcs().size(); index++) {
            FixedScenarioDefinition.NpcSpec spec = definition.npcs().get(index);
            CharacterRecord character = characters.byId(FixedScenarioData.npcId(deployment, index));
            if (character == null || !character.alive() || politics.liegeOf(character.id()) != null) continue;
            CharacterRecord liege = null;
            if (spec.office() == NpcOffice.LOCAL_RULER) {
                liege = highRulers.get(spec.factionId());
            } else if (spec.office() == NpcOffice.SWORN) {
                liege = settlementHolders.get(spec.settlementKey());
                if (liege == null) liege = highRulers.get(spec.factionId());
            }
            if (liege != null && !liege.id().equals(character.id())) {
                politics.swearFealty(server, character.id(), character.name(),
                        liege.id(), liege.name(), gameTime);
            }
        }

        // “裂冠河谷”的既定开局是国王新丧；总管摄政但不自动成为国王。
        if ("small_realm".equals(definition.id())) {
            politics.declareVacancy(server, "sagadyssey:kingdom", "前任国王驾崩，王位空悬", gameTime);
        }
    }

    private static void notifyRequester(MinecraftServer server, FixedScenarioDeployment deployment, String message) {
        if (deployment.requestedBy() == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(deployment.requestedBy());
        if (player != null) player.sendSystemMessage(Component.literal(message));
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
