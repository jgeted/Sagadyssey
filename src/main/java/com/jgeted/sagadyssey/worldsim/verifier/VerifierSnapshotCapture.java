package com.jgeted.sagadyssey.worldsim.verifier;

import com.jgeted.sagadyssey.knowledge.KnowledgeStateData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.trade.TradeSessionData;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.market.LocalMarketData;
import com.jgeted.sagadyssey.social.scenario.FixedScenarioData;
import com.jgeted.sagadyssey.social.scenario.FixedScenarioDeployment;
import com.jgeted.sagadyssey.social.security.SecurityData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.WorldSimulationData;
import com.jgeted.sagadyssey.worldsim.rights.ContainerContext;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Main-thread-only, read-only capture of currently observable physical and domain state. */
public final class VerifierSnapshotCapture {
    private VerifierSnapshotCapture() { }

    public static VerifierSnapshot capture(MinecraftServer server, @Nullable ServerPlayer requester,
                                            String name) {
        Map<String, VerifierSnapshot.ContainerState> containers = new TreeMap<>();
        Map<String, Long> assets = new TreeMap<>();
        Map<String, Long> domains = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> capturedBlocks = new HashSet<>();
        RightsStateData rights = RightsStateData.get(server);

        int loadedNpcs = captureEntities(server, rights, containers, assets, warnings);
        capturePlayers(server, rights, containers, assets, warnings);
        captureWarehouses(server, rights, containers, assets, warnings, capturedBlocks);
        captureHouseholdStorage(server, rights, containers, assets, warnings, capturedBlocks);
        capturePrivateStorage(server, rights, containers, assets, warnings, capturedBlocks);

        captureDomainCounts(server, rights, domains, loadedNpcs);

        String dimension = requester == null
                ? server.overworld().dimension().location().toString()
                : requester.serverLevel().dimension().location().toString();
        FixedScenarioDeployment deployment = FixedScenarioData.get(server).latestIn(dimension);
        String scenarioId = deployment == null ? "" : deployment.scenarioId();
        String profile = deployment == null ? "" : deployment.testProfile();
        int revision = deployment == null ? 0 : deployment.definitionRevision();
        long gameTime = server.overworld().getGameTime();
        return VerifierSnapshot.create(name, server.getWorldData().getLevelName(), gameTime,
                WorldSimulationData.get(server).sourceSchemaVersion(), dimension,
                scenarioId, profile, revision, containers, assets, domains, warnings);
    }

    private static int captureEntities(MinecraftServer server, RightsStateData rights,
                                       Map<String, VerifierSnapshot.ContainerState> containers,
                                       Map<String, Long> totals, List<String> warnings) {
        int loadedNpcs = 0;
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (var entity : level.getAllEntities()) {
                if (entity instanceof NpcBase npc) {
                    loadedNpcs++;
                    UUID characterId = npc.getCharacterId();
                    UUID personalId = ContainerIds.npcPersonal(characterId);
                    captureContainer(personalId.toString(), "npc_personal",
                            "npc:" + characterId + ":personal", revision(rights, personalId),
                            npc.getPersonalInventory(), containers, totals, warnings);
                    UUID cargoId = ContainerIds.npcCargo(characterId);
                    captureContainer(cargoId.toString(), "npc_cargo",
                            "npc:" + characterId + ":cargo", revision(rights, cargoId),
                            npc.getTransportInventory(), containers, totals, warnings);

                    List<ItemStack> equipment = new ArrayList<>();
                    for (EquipmentSlot slot : EquipmentSlot.values()) equipment.add(npc.getItemBySlot(slot));
                    captureStacks("npc-equipment:" + characterId, "npc_equipment",
                            dimension + ":entity:" + npc.getUUID(), -1L, equipment,
                            containers, totals, warnings);
                    captureStacks("npc-overflow:" + characterId, "migration_overflow",
                            dimension + ":entity:" + npc.getUUID(), -1L,
                            npc.getInventoryMigrationOverflowSnapshot(), containers, totals, warnings);
                } else if (entity instanceof ItemEntity itemEntity && itemEntity.isAlive()) {
                    captureStacks("drop:" + itemEntity.getUUID(), "world_drop",
                            dimension + ':' + itemEntity.blockPosition().asLong(), -1L,
                            List.of(itemEntity.getItem()), containers, totals, warnings);
                }
            }
        }
        return loadedNpcs;
    }

    private static void capturePlayers(MinecraftServer server, RightsStateData rights,
                                       Map<String, VerifierSnapshot.ContainerState> containers,
                                       Map<String, Long> totals, List<String> warnings) {
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CharacterRecord character = characters.current(player.getUUID());
            UUID subjectId = character == null ? player.getUUID() : character.id();
            UUID containerId = ContainerIds.playerPersonal(subjectId);
            captureContainer(containerId.toString(), "player_personal",
                    player.serverLevel().dimension().location() + ":player:" + player.getUUID(),
                    revision(rights, containerId), player.getInventory(), containers, totals, warnings);
            if (character == null) warnings.add("online_player_without_character:" + player.getUUID());
        }
    }

    private static void captureWarehouses(MinecraftServer server, RightsStateData rights,
                                          Map<String, VerifierSnapshot.ContainerState> containers,
                                          Map<String, Long> totals, List<String> warnings,
                                          Set<String> capturedBlocks) {
        for (var warehouse : LogisticsData.get(server).warehouses()) {
            ServerLevel level = level(server, warehouse.dimension());
            String physical = blockKey(warehouse.dimension(), warehouse.position());
            if (!capturedBlocks.add(physical)) {
                warnings.add("duplicate_block_container_reference:" + physical);
                continue;
            }
            if (level == null || !level.hasChunkAt(warehouse.position())) {
                warnings.add("unloaded_warehouse:" + warehouse.id());
                continue;
            }
            Container container = WarehouseContainers.asContainer(
                    level.getBlockEntity(warehouse.position()));
            if (container == null) {
                warnings.add("missing_warehouse_container:" + warehouse.id());
                continue;
            }
            captureContainer(warehouse.id().toString(), "warehouse",
                    warehouse.dimension() + ':' + warehouse.position().asLong(),
                    revision(rights, warehouse.id()), container, containers, totals, warnings);
        }
    }

    private static void captureHouseholdStorage(MinecraftServer server, RightsStateData rights,
                                                Map<String, VerifierSnapshot.ContainerState> containers,
                                                Map<String, Long> totals, List<String> warnings,
                                                Set<String> capturedBlocks) {
        for (var household : HouseholdData.get(server).households()) {
            if (household.sharedStorage() == null) continue;
            UUID id = ContainerIds.block("household_storage", household.dimension(),
                    household.sharedStorage());
            captureBlock(server, rights, id, "household_storage", household.dimension(),
                    household.sharedStorage(), containers, totals, warnings, capturedBlocks);
        }
    }

    private static void capturePrivateStorage(MinecraftServer server, RightsStateData rights,
                                              Map<String, VerifierSnapshot.ContainerState> containers,
                                              Map<String, Long> totals, List<String> warnings,
                                              Set<String> capturedBlocks) {
        for (var life : NpcLifeData.get(server).records()) {
            if (life.privateStoragePosition() == null) continue;
            UUID id = ContainerIds.block("private_storage", life.dimension(),
                    life.privateStoragePosition());
            captureBlock(server, rights, id, "private_storage", life.dimension(),
                    life.privateStoragePosition(), containers, totals, warnings, capturedBlocks);
        }
    }

    private static void captureBlock(MinecraftServer server, RightsStateData rights, UUID id,
                                     String kind, String dimension, BlockPos position,
                                     Map<String, VerifierSnapshot.ContainerState> containers,
                                     Map<String, Long> totals, List<String> warnings,
                                     Set<String> capturedBlocks) {
        String physical = blockKey(dimension, position);
        if (!capturedBlocks.add(physical)) {
            warnings.add("duplicate_block_container_reference:" + physical);
            return;
        }
        ServerLevel level = level(server, dimension);
        if (level == null || !level.hasChunkAt(position)) {
            warnings.add("unloaded_" + kind + ':' + id);
            return;
        }
        Container container = WarehouseContainers.asContainer(level.getBlockEntity(position));
        if (container == null) {
            warnings.add("missing_" + kind + ':' + id);
            return;
        }
        captureContainer(id.toString(), kind, dimension + ':' + position.asLong(),
                revision(rights, id), container, containers, totals, warnings);
    }

    private static void captureContainer(String id, String kind, String locator, long revision,
                                         Container container,
                                         Map<String, VerifierSnapshot.ContainerState> containers,
                                         Map<String, Long> totals, List<String> warnings) {
        List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) stacks.add(container.getItem(slot));
        captureStacks(id, kind, locator, revision, stacks, containers, totals, warnings);
    }

    private static void captureStacks(String id, String kind, String locator, long revision,
                                      List<ItemStack> stacks,
                                      Map<String, VerifierSnapshot.ContainerState> containers,
                                      Map<String, Long> totals, List<String> warnings) {
        if (containers.containsKey(id)) {
            warnings.add("duplicate_container_id:" + id);
            return;
        }
        Map<String, Long> contents = new TreeMap<>();
        int occupied = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            occupied++;
            String asset = assetKey(ItemFingerprint.of(stack));
            contents.merge(asset, (long) stack.getCount(), Long::sum);
            totals.merge(asset, (long) stack.getCount(), Long::sum);
        }
        VerifierSnapshot.ContainerState state = new VerifierSnapshot.ContainerState(
                kind, locator, revision, stacks.size(), occupied, contents);
        containers.put(id, state);
    }

    private static void captureDomainCounts(MinecraftServer server, RightsStateData rights,
                                            Map<String, Long> counts, int loadedNpcs) {
        InstitutionData institutions = InstitutionData.get(server);
        SecurityData security = SecurityData.get(server);
        InjuryData injuries = InjuryData.get(server);
        counts.put("loaded_npcs", (long) loadedNpcs);
        counts.put("online_players", (long) server.getPlayerCount());
        counts.put("characters", (long) CharacterRegistryData.get(server).allCharacters().size());
        counts.put("settlements", (long) SettlementRegistryData.get(server).allSettlements().size());
        counts.put("households", (long) HouseholdData.get(server).households().size());
        counts.put("institutions", (long) institutions.institutions().size());
        counts.put("institution_memberships", (long) institutions.memberships().size());
        counts.put("institution_offices", (long) institutions.offices().size());
        counts.put("service_relationships",
                (long) ServiceRelationshipData.get(server).relationships().size());
        counts.put("beliefs", (long) KnowledgeStateData.get(server).beliefs().size());
        counts.put("rights_contexts", (long) rights.contexts().size());
        counts.put("rights_grants", (long) rights.grants().size());
        counts.put("asset_claims", (long) rights.claims().size());
        counts.put("asset_reservations", (long) rights.reservations().size());
        counts.put("warehouses", (long) LogisticsData.get(server).warehouses().size());
        counts.put("logistics_orders", (long) LogisticsData.get(server).orders().size());
        counts.put("trade_sessions", (long) TradeSessionData.get(server).sessions().size());
        counts.put("market_trades", (long) LocalMarketData.get(server).trades().size());
        counts.put("transfer_audits", (long) TransferLedgerData.get(server).audits().size());
        counts.put("history_events", (long) WorldHistoryData.get(server).recent(Integer.MAX_VALUE).size());
        counts.put("security_incidents", (long) security.incidents().size());
        counts.put("patrol_dispatches", (long) security.dispatches().size());
        counts.put("combat_encounters", (long) CombatEncounterData.get(server).encounters().size());
        counts.put("wounds", (long) injuries.wounds().size());
        counts.put("treatments", (long) injuries.treatments().size());
        counts.put("scenario_deployments", (long) FixedScenarioData.get(server).deployments().size());
    }

    private static long revision(RightsStateData rights, UUID containerId) {
        ContainerContext context = rights.context(containerId);
        return context == null ? -1L : context.revision();
    }

    private static String assetKey(ItemFingerprint fingerprint) {
        return fingerprint.itemId() + '@' + Integer.toUnsignedString(fingerprint.componentsHash(), 16);
    }

    private static String blockKey(String dimension, BlockPos position) {
        return dimension + ':' + position.asLong();
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return server.getLevel(key);
    }
}
