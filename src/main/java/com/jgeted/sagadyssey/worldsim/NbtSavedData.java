package com.jgeted.sagadyssey.worldsim;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Registers the mod's existing compound serializers with the current saved-data API. */
public abstract class NbtSavedData extends SavedData {
    public abstract CompoundTag save(CompoundTag tag, HolderLookup.Provider registries);

    public static final class Factory<T extends NbtSavedData> {
        private final Supplier<T> constructor;
        private final BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer;
        private final Map<String, SavedDataType<T>> types = new HashMap<>();

        public Factory(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer) {
            this.constructor = constructor;
            this.deserializer = deserializer;
        }

        public Supplier<T> constructor() {
            return constructor;
        }

        public BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer() {
            return deserializer;
        }

        public synchronized SavedDataType<T> type(String id) {
            return types.computeIfAbsent(id, name -> new SavedDataType<>(
                    name,
                    level -> constructor.get(),
                    level -> {
                        HolderLookup.Provider registries = level == null ? null : level.registryAccess();
                        return CompoundTag.CODEC.xmap(
                                tag -> deserializer.apply(tag, registries),
                                data -> data.save(new CompoundTag(), registries));
                    }));
        }
    }
}
