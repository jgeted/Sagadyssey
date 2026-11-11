package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
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

public final class GuildData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_guild_standards";
    private static final Factory<GuildData> FACTORY = new Factory<>(GuildData::new, GuildData::load);
    private final Map<UUID, CraftQualification> qualifications = new HashMap<>();
    private final Map<UUID, GuildQualityStandard> standards = new HashMap<>();
    private final Map<UUID, GuildInspection> inspections = new HashMap<>();

    public static GuildData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    boolean addQualification(CraftQualification value) {
        if (qualifications.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Nullable
    public CraftQualification qualification(UUID institutionId, SubjectRef artisan,
                                            String craftId, long now) {
        String normalized = craftId.trim().toLowerCase(java.util.Locale.ROOT);
        return qualifications.values().stream().filter(value -> value.institutionId().equals(institutionId)
                && value.artisan().equals(artisan) && value.craftId().equals(normalized)
                && value.activeAt(now)).max(java.util.Comparator.comparing(CraftQualification::rank))
                .orElse(null);
    }

    boolean addStandard(GuildQualityStandard value) {
        if (standards.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public GuildQualityStandard standard(UUID id) { return standards.get(id); }

    boolean addInspection(GuildInspection value) {
        if (inspections.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    public List<GuildInspection> inspections() { return List.copyOf(inspections.values()); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag qualificationTags = new ListTag();
        qualifications.values().forEach(value -> qualificationTags.add(value.save()));
        tag.put("Qualifications", qualificationTags);
        ListTag standardTags = new ListTag();
        standards.values().forEach(value -> standardTags.add(value.save()));
        tag.put("Standards", standardTags);
        ListTag inspectionTags = new ListTag();
        inspections.values().forEach(value -> inspectionTags.add(value.save()));
        tag.put("Inspections", inspectionTags);
        return tag;
    }

    private static GuildData load(CompoundTag tag, HolderLookup.Provider registries) {
        GuildData data = new GuildData();
        ListTag qualifications = MinecraftApi.listOfType(tag, "Qualifications", Tag.TAG_COMPOUND);
        for (int index = 0; index < qualifications.size(); index++) {
            CraftQualification value = CraftQualification.load(qualifications.getCompoundOrEmpty(index));
            data.qualifications.put(value.id(), value);
        }
        ListTag standards = MinecraftApi.listOfType(tag, "Standards", Tag.TAG_COMPOUND);
        for (int index = 0; index < standards.size(); index++) {
            GuildQualityStandard value = GuildQualityStandard.load(standards.getCompoundOrEmpty(index));
            data.standards.put(value.id(), value);
        }
        ListTag inspections = MinecraftApi.listOfType(tag, "Inspections", Tag.TAG_COMPOUND);
        for (int index = 0; index < inspections.size(); index++) {
            GuildInspection value = GuildInspection.load(inspections.getCompoundOrEmpty(index));
            data.inspections.put(value.id(), value);
        }
        return data;
    }
}
