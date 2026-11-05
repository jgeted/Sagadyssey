package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Read-only scan of currently loaded v1 state. It never writes inventories or identities. */
public final class MigrationDryRun {
    private MigrationDryRun() { }

    public static MigrationBaselineReport capture(MinecraftServer server) {
        List<UUID> npcIds = new ArrayList<>();
        List<MigrationBaselineReport.ContainerSnapshot> containers = new ArrayList<>();
        Map<String, Long> assets = new TreeMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof NpcBase npc)) continue;
                UUID characterId = npc.getCharacterId();
                npcIds.add(characterId);
                captureContainer(characterId + ":personal_v2", npc.getPersonalInventory(),
                        containers, assets);
                captureContainer(characterId + ":transport", npc.getTransportInventory(),
                        containers, assets);
                List<ItemStack> migrationOverflow = npc.getInventoryMigrationOverflowSnapshot();
                for (int index = 0; index < migrationOverflow.size(); index++) {
                    captureStack(characterId + ":migration_overflow:" + index,
                            migrationOverflow.get(index), assets);
                }
                containers.add(new MigrationBaselineReport.ContainerSnapshot(
                        characterId + ":migration_overflow", 0L,
                        migrationOverflow.size(), migrationOverflow.size()));
                int equipped = 0;
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = npc.getItemBySlot(slot);
                    if (!stack.isEmpty()) equipped++;
                    captureStack(characterId + ":equipment:" + slot.getSerializedName(), stack, assets);
                }
                containers.add(new MigrationBaselineReport.ContainerSnapshot(
                        characterId + ":equipment", 0L, EquipmentSlot.values().length, equipped));
            }
        }
        int registered = CharacterRegistryData.get(server).allCharacters().size();
        return MigrationBaselineReport.inspect(server.getWorldData().getLevelName(),
                WorldSimulationData.get(server).sourceSchemaVersion(), npcIds, registered,
                containers, assets);
    }

    private static void captureContainer(String id, Container container,
                                         List<MigrationBaselineReport.ContainerSnapshot> containers,
                                         Map<String, Long> assets) {
        int nonEmpty = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) nonEmpty++;
            captureStack(id + ':' + slot, stack, assets);
        }
        containers.add(new MigrationBaselineReport.ContainerSnapshot(id, 0L,
                container.getContainerSize(), nonEmpty));
    }

    private static void captureStack(String location, ItemStack stack, Map<String, Long> assets) {
        if (stack.isEmpty()) return;
        String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        assets.merge(itemKey, (long) stack.getCount(), Long::sum);
    }
}
