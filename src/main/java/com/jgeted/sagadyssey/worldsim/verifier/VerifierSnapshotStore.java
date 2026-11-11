package com.jgeted.sagadyssey.worldsim.verifier;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;

/** World-persistent storage for a small, bounded set of operator verification snapshots. */
public final class VerifierSnapshotStore extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_verifier_snapshots_v1";
    public static final Factory<VerifierSnapshotStore> FACTORY =
            new Factory<>(VerifierSnapshotStore::new, VerifierSnapshotStore::load);
    private static final int MAX_SNAPSHOTS = 16;
    private final LinkedHashMap<String, VerifierSnapshot> snapshots = new LinkedHashMap<>();

    public static VerifierSnapshotStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public static void put(MinecraftServer server, VerifierSnapshot snapshot) {
        VerifierSnapshotStore data = get(server);
        data.snapshots.remove(snapshot.name());
        data.snapshots.put(snapshot.name(), snapshot);
        while (data.snapshots.size() > MAX_SNAPSHOTS) {
            data.snapshots.remove(data.snapshots.keySet().iterator().next());
        }
        data.setDirty();
    }

    @Nullable
    public static VerifierSnapshot get(MinecraftServer server, String name) {
        return get(server).snapshots.get(name);
    }

    public static List<VerifierSnapshot> list(MinecraftServer server) {
        return List.copyOf(get(server).snapshots.values());
    }

    public static boolean remove(MinecraftServer server, String name) {
        VerifierSnapshotStore data = get(server);
        if (data.snapshots.remove(name) == null) return false;
        data.setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        snapshots.values().forEach(snapshot -> list.add(snapshot.save()));
        tag.put("Snapshots", list);
        return tag;
    }

    private static VerifierSnapshotStore load(CompoundTag tag, HolderLookup.Provider registries) {
        VerifierSnapshotStore data = new VerifierSnapshotStore();
        ListTag list = MinecraftApi.listOfType(tag, "Snapshots", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                VerifierSnapshot snapshot = VerifierSnapshot.load(list.getCompoundOrEmpty(index));
                data.snapshots.remove(snapshot.name());
                data.snapshots.put(snapshot.name(), snapshot);
                if (data.snapshots.size() > MAX_SNAPSHOTS) {
                    data.snapshots.remove(data.snapshots.keySet().iterator().next());
                }
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
