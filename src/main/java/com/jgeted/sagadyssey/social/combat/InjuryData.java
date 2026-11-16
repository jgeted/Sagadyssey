package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InjuryData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_injuries";
    private static final Factory<InjuryData> FACTORY = new Factory<>(InjuryData::new, InjuryData::load);
    private static final TriageAssessment UNINJURED = new TriageAssessment(0, 0, 0, 0, 0, 0, false, false);
    private final Map<UUID, WoundRecord> wounds = new HashMap<>();
    private final Map<UUID, List<UUID>> woundsByPatient = new HashMap<>();
    private final Map<UUID, TreatmentRecord> treatments = new HashMap<>();

    public static InjuryData get(MinecraftServer server) {
        return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(server, FACTORY, DATA_NAME);
    }

    boolean add(WoundRecord wound) {
        if (wounds.putIfAbsent(wound.id(), wound) != null) return false;
        index(wound);
        setDirty();
        return true;
    }

    private void index(WoundRecord wound) {
        woundsByPatient.computeIfAbsent(wound.patientCharacterId(), ignored -> new ArrayList<>()).add(wound.id());
    }

    @Nullable public WoundRecord wound(UUID id) { return wounds.get(id); }
    @Nullable public TreatmentRecord treatment(UUID id) { return treatments.get(id); }
    public List<WoundRecord> wounds() { return List.copyOf(wounds.values()); }
    public List<TreatmentRecord> treatments() { return List.copyOf(treatments.values()); }

    public List<WoundRecord> woundsOf(UUID characterId, long now) {
        progress(characterId, now);
        var ids = woundsByPatient.get(characterId);
        return ids == null ? List.of() : ids.stream().map(wounds::get).toList();
    }

    public void progress(UUID characterId, long now) {
        var ids = woundsByPatient.get(characterId);
        if (ids == null) return;
        boolean changed = false;
        for (UUID id : ids) {
            WoundRecord wound = wounds.get(id);
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
        progress(characterId, now);
        return observeTriage(characterId);
    }

    /** 读取既有伤势而不推进时间；动员等预览复用同一健康规则，不建立另一套轻重伤算法。 */
    public TriageAssessment observeTriage(UUID characterId) {
        var ids = woundsByPatient.get(characterId);
        if (ids == null) return UNINJURED;
        int count = 0, trauma = 0, bleeding = 0, infection = 0, mobility = 0, work = 0;
        boolean mortal = false;
        for (UUID id : ids) {
            var wound = wounds.get(id);
            if (!wound.status().active() && wound.status() != WoundStatus.FATAL) continue;
            count++;
            trauma += wound.trauma();
            bleeding += wound.bleeding();
            infection += wound.infection();
            int limbPenalty = wound.trauma() / 2 + wound.severity().ordinal() * 10;
            switch (wound.region()) {
                case LEFT_LEG, RIGHT_LEG -> mobility += limbPenalty;
                case LEFT_ARM, RIGHT_ARM -> work += limbPenalty;
                case HEAD, TORSO -> work += wound.trauma() / 4;
            }
            mortal |= wound.status() == WoundStatus.FATAL
                    || wound.severity() == WoundSeverity.CRITICAL && wound.status() == WoundStatus.ACTIVE;
        }
        mortal |= bleeding >= 80 || infection >= 80;
        return new TriageAssessment(count, Math.min(100, trauma), Math.min(100, bleeding), Math.min(100, infection),
                Math.min(100, mobility), Math.min(100, work), mortal || trauma >= 85 || mobility >= 90, mortal);
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
        data.wounds.values().forEach(data::index);
        ListTag treatments = MinecraftApi.listOfType(tag, "Treatments", Tag.TAG_COMPOUND);
        for (int index = 0; index < treatments.size(); index++) {
            TreatmentRecord value = TreatmentRecord.load(treatments.getCompoundOrEmpty(index));
            data.treatments.put(value.id(), value);
        }
        return data;
    }
}
