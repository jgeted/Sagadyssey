package com.jgeted.sagadyssey.social.security;

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
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

public final class SecurityData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_security";
    private static final Factory<SecurityData> FACTORY = new Factory<>(SecurityData::new, SecurityData::load);
    private final Map<UUID, SecurityIncident> incidents = new HashMap<>();
    private final Map<UUID, PatrolDispatch> dispatches = new HashMap<>();

    public static SecurityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    boolean addIncident(SecurityIncident value) {
        if (incidents.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public SecurityIncident incident(UUID id) { return incidents.get(id); }
    public List<SecurityIncident> incidents() { return List.copyOf(incidents.values()); }

    void updateIncident(SecurityIncident value) {
        if (!incidents.containsKey(value.id())) throw new IllegalArgumentException("unknown incident");
        incidents.put(value.id(), value);
        setDirty();
    }

    boolean addDispatch(PatrolDispatch value) {
        if (dispatches.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    public List<PatrolDispatch> dispatchesFor(UUID incidentId) {
        return dispatches.values().stream().filter(value -> value.incidentId().equals(incidentId)).toList();
    }

    public List<PatrolDispatch> dispatches() { return List.copyOf(dispatches.values()); }

    public List<SecurityIncident> incidentsAbout(SubjectRef subject) {
        return incidents.values().stream().filter(value -> subject.equals(value.victim())
                || subject.equals(value.allegedOffender())
                || subject.equals(value.reportedBy())).toList();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag incidentTags = new ListTag();
        incidents.values().forEach(value -> incidentTags.add(value.save()));
        tag.put("Incidents", incidentTags);
        ListTag dispatchTags = new ListTag();
        dispatches.values().forEach(value -> dispatchTags.add(value.save()));
        tag.put("Dispatches", dispatchTags);
        return tag;
    }

    private static SecurityData load(CompoundTag tag, HolderLookup.Provider registries) {
        SecurityData data = new SecurityData();
        ListTag incidents = MinecraftApi.listOfType(tag, "Incidents", Tag.TAG_COMPOUND);
        for (int index = 0; index < incidents.size(); index++) {
            SecurityIncident value = SecurityIncident.load(incidents.getCompoundOrEmpty(index));
            data.incidents.put(value.id(), value);
        }
        ListTag dispatches = MinecraftApi.listOfType(tag, "Dispatches", Tag.TAG_COMPOUND);
        for (int index = 0; index < dispatches.size(); index++) {
            PatrolDispatch value = PatrolDispatch.load(dispatches.getCompoundOrEmpty(index));
            data.dispatches.put(value.id(), value);
        }
        return data;
    }
}
