package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event-backed individual morale. Squad state is an aggregation, never a second hidden score. */
public final class CombatMoraleData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_combat_morale";
    private static final Factory<CombatMoraleData> FACTORY =
            new Factory<>(CombatMoraleData::new, CombatMoraleData::load);
    private final Map<UUID, MoraleProfile> profiles = new HashMap<>();
    private final Map<UUID, MoraleEvent> events = new HashMap<>();

    public static CombatMoraleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public MoraleProfile profile(UUID characterId, long now) {
        MoraleProfile current = profiles.computeIfAbsent(characterId,
                id -> new MoraleProfile(id, 50, 50, now, 0));
        MoraleProfile recovered = current.recover(now);
        if (!recovered.equals(current)) {
            profiles.put(characterId, recovered);
            setDirty();
        }
        return recovered;
    }

    public MoraleProfile ensure(UUID characterId, int baseline, int current, long now) {
        MoraleProfile existing = profiles.get(characterId);
        if (existing != null) return existing;
        MoraleProfile created = new MoraleProfile(characterId, baseline, current, now, 0);
        profiles.put(characterId, created);
        setDirty();
        return created;
    }

    public MoraleProfile record(MoraleEvent event) {
        if (events.containsKey(event.id())) return profiles.getOrDefault(event.characterId(),
                new MoraleProfile(event.characterId(), 50, 50, event.occurredAt(), 0));
        MoraleProfile current = profile(event.characterId(), event.occurredAt());
        MoraleProfile updated = current.apply(event.delta(), event.occurredAt());
        events.put(event.id(), event);
        profiles.put(event.characterId(), updated);
        setDirty();
        return updated;
    }

    public MoraleProfile record(UUID eventId, UUID characterId, UUID encounterId,
                                MoraleEventType type, int magnitude, UUID sourceRecordId,
                                long now, String note) {
        int sign = Integer.signum(type.defaultDelta());
        int delta = sign * Math.max(1, Math.min(100, Math.abs(magnitude)));
        return record(new MoraleEvent(eventId, characterId, encounterId, type, delta,
                sourceRecordId, now, note));
    }

    public int groupAverage(List<UUID> characters, long now) {
        if (characters == null || characters.isEmpty()) return 50;
        return (int) Math.round(characters.stream().mapToInt(id -> profile(id, now).current())
                .average().orElse(50.0D));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag profileTags = new ListTag();
        profiles.values().forEach(value -> profileTags.add(value.save()));
        tag.put("Profiles", profileTags);
        ListTag eventTags = new ListTag();
        events.values().forEach(value -> eventTags.add(value.save()));
        tag.put("Events", eventTags);
        return tag;
    }

    private static CombatMoraleData load(CompoundTag tag, HolderLookup.Provider registries) {
        CombatMoraleData data = new CombatMoraleData();
        ListTag profiles = tag.getList("Profiles", Tag.TAG_COMPOUND);
        for (int index = 0; index < profiles.size(); index++) {
            MoraleProfile value = MoraleProfile.load(profiles.getCompound(index));
            data.profiles.put(value.characterId(), value);
        }
        ListTag events = tag.getList("Events", Tag.TAG_COMPOUND);
        for (int index = 0; index < events.size(); index++) {
            MoraleEvent value = MoraleEvent.load(events.getCompound(index));
            data.events.put(value.id(), value);
        }
        return data;
    }
}
