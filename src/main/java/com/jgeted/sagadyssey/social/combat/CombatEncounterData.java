package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CombatEncounterData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_combat_encounters";
    private static final Factory<CombatEncounterData> FACTORY =
            new Factory<>(CombatEncounterData::new, CombatEncounterData::load);
    private final Map<UUID, CombatEncounter> encounters = new HashMap<>();
    private final Map<UUID, java.util.Set<UUID>> activeByCharacter = new HashMap<>();
    private final Map<UUID, CombatImpact> settlement = new HashMap<>();
    private final Map<UUID,List<CombatImpact>> frozenByEncounter = new HashMap<>();

    public static CombatEncounterData get(MinecraftServer server) {
        var storage=server.overworld().getDataStorage();var data=storage.get(FACTORY,DATA_NAME);if(data!=null)return data;
        if(java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(DATA_NAME+".dat"))) throw new IllegalStateException("unreadable combat ledger; explicit recovery required");
        data=new CombatEncounterData();storage.set(DATA_NAME,data);return data;
    }

    public boolean add(CombatEncounter encounter) {
        if (encounters.containsKey(encounter.id())) return false;
        if (encounters.size() >= 4096) throw new IllegalStateException("encounter ledger full; explicit archival required");
        if (encounters.putIfAbsent(encounter.id(), encounter) != null) return false;
        index(encounter);
        setDirty();
        return true;
    }

    @Nullable public CombatEncounter encounter(UUID id) { return encounters.get(id); }
    public List<CombatEncounter> encounters() { return List.copyOf(encounters.values()); }

    void update(CombatEncounter encounter) {
        if (!encounters.containsKey(encounter.id())) throw new IllegalArgumentException("unknown encounter");
        encounters.put(encounter.id(), encounter);
        index(encounter);
        setDirty();
    }

    private void index(CombatEncounter encounter) {
        for (var side : encounter.sides()) for (var character : side.members()) {
            var ids=activeByCharacter.computeIfAbsent(character, ignored -> new java.util.HashSet<>());
            if (encounter.status()==EncounterStatus.ENDED) ids.remove(encounter.id());
            else ids.add(encounter.id());
        }
    }

    public boolean settling(UUID encounter) {
        return frozenByEncounter.containsKey(encounter);
    }

    /** Freeze context, counts and timestamp once. Replaying another SavedData inbox uses exactly this snapshot. */
    public List<CombatImpact> freeze(UUID encounter, java.util.function.Supplier<List<CombatImpact>> snapshot) {
        var existing=frozenByEncounter.get(encounter);
        if (existing!=null) return existing;
        var values=List.copyOf(snapshot.get());
        if (values.isEmpty() || values.stream().anyMatch(i -> !encounter.equals(i.encounter())))
            throw new IllegalArgumentException("invalid encounter settlement snapshot");
        if (settlement.size()+values.size()>4096) throw new IllegalStateException("settlement snapshot ledger full");
        values.forEach(i -> CombatImpact.put(settlement,i));frozenByEncounter.put(encounter,values);setDirty();return values;
    }

    @Nullable
    public CombatEncounter activeFor(UUID characterId, long now) {
        return activeByCharacter.getOrDefault(characterId, java.util.Set.of()).stream().map(encounters::get).filter(value -> value.activeAt(now)
                && value.sideOf(characterId) != null).findFirst().orElse(null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        encounters.values().forEach(value -> list.add(value.save()));
        tag.put("Encounters", list);
        tag.putInt("Schema",1);
        tag.put("CombatImpacts",CombatImpact.save(settlement));
        return tag;
    }

    public static CombatEncounterData load(CompoundTag tag, HolderLookup.Provider registries) {
        CombatEncounterData data = new CombatEncounterData();
        if (tag.contains("Schema") && (!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1)) throw new IllegalArgumentException("unknown combat schema");
        if (!(tag.get("Encounters") instanceof ListTag rows)||rows.size()>4096||!rows.isEmpty()&&rows.getElementType()!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid combat ledger");
        ListTag list = tag.getList("Encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CombatEncounter value = CombatEncounter.load(list.getCompound(index));
            if(data.encounters.putIfAbsent(value.id(), value)!=null) throw new IllegalArgumentException("duplicate encounter");
            data.index(value);
        }
        CombatImpact.load(tag,data.settlement);
        if(data.settlement.values().stream().anyMatch(i -> !data.encounters.containsKey(i.encounter()))) throw new IllegalArgumentException("orphan combat settlement");
        data.settlement.values().forEach(i -> data.frozenByEncounter.computeIfAbsent(i.encounter(),ignored -> new java.util.ArrayList<>()).add(i));
        data.frozenByEncounter.replaceAll((id,values)->List.copyOf(values));
        return data;
    }
}
