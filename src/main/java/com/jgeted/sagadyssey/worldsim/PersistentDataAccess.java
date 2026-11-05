package com.jgeted.sagadyssey.worldsim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;

/** Do not replace an unreadable existing ledger with an empty writable instance. */
public final class PersistentDataAccess {
    private PersistentDataAccess() {}

    public static <T extends SavedData> T getOrCreate(
            MinecraftServer server, SavedData.Factory<T> factory, String name) {
        var storage = server.overworld().getDataStorage();
        T existing = storage.get(factory, name);
        if (existing != null) return existing;
        var directory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        if (Files.exists(directory.resolve(name + ".dat"))
                || Files.exists(directory.resolve(name + ".dat_old")))
            throw new IllegalStateException("Unreadable ledger " + name
                    + "; original data retained, recovery required");
        T created = factory.constructor().get();
        storage.set(name, created);
        return created;
    }

    public static ListTag compounds(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Expected compound list: " + key);
        return list;
    }
}
