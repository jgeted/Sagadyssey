package com.jgeted.sagadyssey.social.institution;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable memberships, offices, treaties, and overlapping jurisdiction claims. */
public final class InstitutionData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_institutions";
    private static final Factory<InstitutionData> FACTORY =
            new Factory<>(InstitutionData::new, InstitutionData::load);
    private final Map<UUID, InstitutionRecord> institutions = new HashMap<>();
    private final Map<UUID, InstitutionMembership> memberships = new HashMap<>();
    private final Map<UUID, InstitutionOffice> offices = new HashMap<>();
    private final Map<String, InstitutionRelation> relations = new HashMap<>();
    private final Map<UUID, JurisdictionClaim> claims = new HashMap<>();

    public static InstitutionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public boolean addInstitution(InstitutionRecord value) {
        if (institutions.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public InstitutionRecord institution(UUID id) { return institutions.get(id); }
    public List<InstitutionRecord> institutions() { return List.copyOf(institutions.values()); }

    public boolean addMembership(InstitutionMembership value) {
        boolean duplicate = memberships.values().stream().anyMatch(existing ->
                existing.institutionId().equals(value.institutionId())
                        && existing.member().equals(value.member()) && existing.active());
        if (duplicate || !institutions.containsKey(value.institutionId())) return false;
        memberships.put(value.id(), value);
        setDirty();
        return true;
    }

    @Nullable public InstitutionMembership membership(UUID institutionId, SubjectRef member) {
        return memberships.values().stream().filter(value -> value.active()
                && value.institutionId().equals(institutionId) && value.member().equals(member))
                .findFirst().orElse(null);
    }

    public List<InstitutionMembership> memberships(UUID institutionId) {
        return memberships.values().stream().filter(value ->
                value.institutionId().equals(institutionId)).toList();
    }

    public List<InstitutionMembership> memberships() { return List.copyOf(memberships.values()); }

    public List<InstitutionOffice> offices() { return List.copyOf(offices.values()); }

    public List<InstitutionMembership> membershipsOf(SubjectRef member) {
        return memberships.values().stream().filter(value -> value.active()
                && value.member().equals(member)).toList();
    }

    public void putMembership(InstitutionMembership value) {
        if (!memberships.containsKey(value.id())) throw new IllegalArgumentException("unknown membership");
        memberships.put(value.id(), value);
        setDirty();
    }

    public boolean addOffice(InstitutionOffice value) {
        if (!institutions.containsKey(value.institutionId()) || offices.putIfAbsent(value.id(), value) != null) {
            return false;
        }
        offices.put(value.id(), value);
        setDirty();
        return true;
    }

    public List<InstitutionOffice> officesHeld(UUID institutionId, SubjectRef holder, long now) {
        return offices.values().stream().filter(value -> value.institutionId().equals(institutionId)
                && value.holder().equals(holder) && value.activeAt(now)).toList();
    }

    public List<InstitutionOffice> officesHeldBy(SubjectRef holder, long now) {
        return offices.values().stream().filter(value -> value.holder().equals(holder)
                && value.activeAt(now)).toList();
    }

    public boolean revokeOffice(UUID officeId) {
        InstitutionOffice office = offices.get(officeId);
        if (office == null || office.revoked()) return false;
        offices.put(officeId, office.revoke());
        setDirty();
        return true;
    }

    public void setRelation(InstitutionRelation value) {
        relations.put(pair(value.sourceInstitutionId(), value.targetInstitutionId()), value);
        if (value.kind().symmetric()) {
            relations.put(pair(value.targetInstitutionId(), value.sourceInstitutionId()),
                    new InstitutionRelation(value.targetInstitutionId(), value.sourceInstitutionId(),
                            value.kind(), value.establishedAt(), value.expiresAt(), value.basis()));
        } else if (value.kind() == InstitutionRelationKind.VASSALAGE) {
            relations.put(pair(value.targetInstitutionId(), value.sourceInstitutionId()),
                    new InstitutionRelation(value.targetInstitutionId(), value.sourceInstitutionId(),
                            InstitutionRelationKind.OVERLORD, value.establishedAt(), value.expiresAt(), value.basis()));
        }
        setDirty();
    }

    @Nullable public InstitutionRelation relation(UUID source, UUID target, long now) {
        InstitutionRelation value = relations.get(pair(source, target));
        return value != null && value.activeAt(now) ? value : null;
    }

    public boolean addClaim(JurisdictionClaim claim) {
        if (!institutions.containsKey(claim.institutionId()) || claims.putIfAbsent(claim.id(), claim) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    public List<JurisdictionClaim> jurisdiction(UUID settlementId,
                                                OfficeAuthority authority, long now) {
        return claims.values().stream().filter(value -> value.settlementId().equals(settlementId)
                && value.authority() == authority && value.activeAt(now))
                .sorted((left, right) -> Integer.compare(right.priority(), left.priority())).toList();
    }

    public boolean jurisdictionContested(UUID settlementId, OfficeAuthority authority, long now) {
        List<JurisdictionClaim> active = jurisdiction(settlementId, authority, now);
        return active.size() > 1 && active.get(0).priority() == active.get(1).priority();
    }

    public InstitutionAuthorityVerdict authority(SubjectRef actor, UUID institutionId,
                                                  OfficeAuthority authority, long now) {
        InstitutionRecord institution = institutions.get(institutionId);
        if (institution == null || !institution.active()) {
            return new InstitutionAuthorityVerdict(false, authority, List.of(), "组织不存在或已解散");
        }
        if (actor.equals(SubjectRef.institution(institutionId))) {
            return new InstitutionAuthorityVerdict(true, authority, List.of(), "机构主体直接行动");
        }
        List<UUID> sources = officesHeld(institutionId, actor, now).stream()
                .filter(office -> office.authorities().contains(authority))
                .map(InstitutionOffice::id).toList();
        return sources.isEmpty()
                ? new InstitutionAuthorityVerdict(false, authority, List.of(), "未持有有效职权")
                : new InstitutionAuthorityVerdict(true, authority, sources, "由当前职位授权");
    }

    private static String pair(UUID source, UUID target) { return source + "|" + target; }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Institutions", saveList(institutions.values().stream().map(InstitutionRecord::save).toList()));
        tag.put("Memberships", saveList(memberships.values().stream().map(InstitutionMembership::save).toList()));
        tag.put("Offices", saveList(offices.values().stream().map(InstitutionOffice::save).toList()));
        tag.put("Relations", saveList(relations.values().stream().distinct().map(InstitutionRelation::save).toList()));
        tag.put("Claims", saveList(claims.values().stream().map(JurisdictionClaim::save).toList()));
        return tag;
    }

    private static ListTag saveList(List<CompoundTag> values) {
        ListTag list = new ListTag();
        list.addAll(values);
        return list;
    }

    private static InstitutionData load(CompoundTag tag, HolderLookup.Provider registries) {
        InstitutionData data = new InstitutionData();
        for (CompoundTag value : compounds(tag, "Institutions")) {
            InstitutionRecord record = InstitutionRecord.load(value);
            data.institutions.put(record.id(), record);
        }
        for (CompoundTag value : compounds(tag, "Memberships")) {
            InstitutionMembership record = InstitutionMembership.load(value);
            data.memberships.put(record.id(), record);
        }
        for (CompoundTag value : compounds(tag, "Offices")) {
            InstitutionOffice record = InstitutionOffice.load(value);
            data.offices.put(record.id(), record);
        }
        for (CompoundTag value : compounds(tag, "Relations")) data.setRelation(InstitutionRelation.load(value));
        for (CompoundTag value : compounds(tag, "Claims")) {
            JurisdictionClaim record = JurisdictionClaim.load(value);
            data.claims.put(record.id(), record);
        }
        return data;
    }

    private static List<CompoundTag> compounds(CompoundTag root, String key) {
        ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) result.add(list.getCompound(index));
        return result;
    }
}
