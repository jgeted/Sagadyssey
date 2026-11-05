package com.jgeted.sagadyssey.social.justice;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
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

public final class JusticeData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_justice";
    private static final Factory<JusticeData> FACTORY = new Factory<>(JusticeData::new, JusticeData::load);
    private final Map<UUID, DisputeRecord> disputes = new HashMap<>();
    private final Map<UUID, ProtectionOrder> protections = new HashMap<>();

    public static JusticeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    boolean file(DisputeRecord value) {
        if (disputes.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public DisputeRecord dispute(UUID id) { return disputes.get(id); }

    void update(DisputeRecord value) {
        if (!disputes.containsKey(value.id())) throw new IllegalArgumentException("unknown dispute");
        disputes.put(value.id(), value);
        setDirty();
    }

    boolean addProtection(ProtectionOrder value) {
        if (protections.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    public List<ProtectionOrder> activeProtections(SubjectRef protectedSubject, long now) {
        return protections.values().stream().filter(value -> value.protectedSubject()
                .equals(protectedSubject) && value.activeAt(now)).toList();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag disputeTags = new ListTag();
        disputes.values().forEach(value -> disputeTags.add(value.save()));
        tag.put("Disputes", disputeTags);
        ListTag protectionTags = new ListTag();
        protections.values().forEach(value -> protectionTags.add(value.save()));
        tag.put("Protections", protectionTags);
        return tag;
    }

    private static JusticeData load(CompoundTag tag, HolderLookup.Provider registries) {
        JusticeData data = new JusticeData();
        ListTag disputes = tag.getList("Disputes", Tag.TAG_COMPOUND);
        for (int index = 0; index < disputes.size(); index++) {
            DisputeRecord value = DisputeRecord.load(disputes.getCompound(index));
            data.disputes.put(value.id(), value);
        }
        ListTag protections = tag.getList("Protections", Tag.TAG_COMPOUND);
        for (int index = 0; index < protections.size(); index++) {
            ProtectionOrder value = ProtectionOrder.load(protections.getCompound(index));
            data.protections.put(value.id(), value);
        }
        return data;
    }
}
