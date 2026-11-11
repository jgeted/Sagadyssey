package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InjuryData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_injuries";
    private static final Factory<InjuryData> FACTORY = new Factory<>(InjuryData::new, InjuryData::load);
    private final Map<UUID, WoundRecord> wounds = new HashMap<>();
    private final Map<UUID, TreatmentRecord> treatments = new HashMap<>();

    public static InjuryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    boolean add(WoundRecord wound) {
        if (wounds.putIfAbsent(wound.id(), wound) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public WoundRecord wound(UUID id) { return wounds.get(id); }
    @Nullable public TreatmentRecord treatment(UUID id) { return treatments.get(id); }
    public List<WoundRecord> wounds() { return List.copyOf(wounds.values()); }
    public List<TreatmentRecord> treatments() { return List.copyOf(treatments.values()); }

    public List<WoundRecord> woundsOf(UUID characterId, long now) {
        progress(characterId, now);
        return wounds.values().stream().filter(value ->
                value.patientCharacterId().equals(characterId)).toList();
    }

    public void progress(UUID characterId, long now) {
        boolean changed = false;
        for (WoundRecord wound : List.copyOf(wounds.values())) {
            if (!wound.patientCharacterId().equals(characterId)) continue;
            WoundRecord progressed = wound.progress(now);
            if (!progressed.equals(wound)) {
                wounds.put(wound.id(), progressed);
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    boolean recordTreatment(TreatmentRecord treatment, WoundRecord updated) {
        if (treatments.containsKey(treatment.id())) return false;
        if (!wounds.containsKey(updated.id())) throw new IllegalArgumentException("unknown wound");
        treatments.put(treatment.id(), treatment);
        wounds.put(updated.id(), updated);
        setDirty();
        return true;
    }

    public TriageAssessment triage(UUID characterId, long now) {
        List<WoundRecord> active = woundsOf(characterId, now).stream()
                .filter(value -> value.status().active() || value.status() == WoundStatus.FATAL).toList();
        int trauma = Math.min(100, active.stream().mapToInt(WoundRecord::trauma).sum());
        int bleeding = Math.min(100, active.stream().mapToInt(WoundRecord::bleeding).sum());
        int infection = Math.min(100, active.stream().mapToInt(WoundRecord::infection).sum());
        int mobility = Math.min(100, active.stream().mapToInt(value ->
                value.region() == BodyRegion.LEFT_LEG || value.region() == BodyRegion.RIGHT_LEG
                        ? value.trauma() / 2 + value.severity().ordinal() * 10 : 0).sum());
        int work = Math.min(100, active.stream().mapToInt(value -> switch (value.region()) {
            case LEFT_ARM, RIGHT_ARM -> value.trauma() / 2 + value.severity().ordinal() * 10;
            case HEAD, TORSO -> value.trauma() / 4;
            default -> 0;
        }).sum());
        boolean mortal = active.stream().anyMatch(value -> value.status() == WoundStatus.FATAL
                || value.severity() == WoundSeverity.CRITICAL && value.status() == WoundStatus.ACTIVE)
                || bleeding >= 80 || infection >= 80;
        boolean incapacitated = mortal || trauma >= 85 || mobility >= 90;
        return new TriageAssessment(active.size(), trauma, bleeding, infection,
                mobility, work, incapacitated, mortal);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag woundTags = new ListTag();
        wounds.values().forEach(value -> woundTags.add(value.save()));
        tag.put("Wounds", woundTags);
        ListTag treatmentTags = new ListTag();
        treatments.values().forEach(value -> treatmentTags.add(value.save()));
        tag.put("Treatments", treatmentTags);
        return tag;
    }

    private static InjuryData load(CompoundTag tag, HolderLookup.Provider registries) {
        InjuryData data = new InjuryData();
        ListTag wounds = MinecraftApi.listOfType(tag, "Wounds", Tag.TAG_COMPOUND);
        for (int index = 0; index < wounds.size(); index++) {
            WoundRecord value = WoundRecord.load(wounds.getCompoundOrEmpty(index));
            data.wounds.put(value.id(), value);
        }
        ListTag treatments = MinecraftApi.listOfType(tag, "Treatments", Tag.TAG_COMPOUND);
        for (int index = 0; index < treatments.size(); index++) {
            TreatmentRecord value = TreatmentRecord.load(treatments.getCompoundOrEmpty(index));
            data.treatments.put(value.id(), value);
        }
        return data;
    }
}
