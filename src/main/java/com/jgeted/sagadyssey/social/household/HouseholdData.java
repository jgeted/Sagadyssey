package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 世界级家庭登记；保证一个人物同时只能属于一个家庭。 */
public final class HouseholdData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_households";
    private static final Factory<HouseholdData> FACTORY =
            new Factory<>(HouseholdData::new, HouseholdData::load);
   private final Map<UUID, HouseholdRecord> households = new HashMap<>();
    private final Map<UUID, com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts = new HashMap<>();
    public java.util.List<com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts() { return java.util.List.copyOf(combatImpacts.values()); }
    public void recordCombatImpact(com.jgeted.sagadyssey.social.combat.CombatImpact impact) {
        if(com.jgeted.sagadyssey.social.combat.CombatImpact.put(combatImpacts,impact))setDirty();
    }
    private final Map<UUID, UUID> householdByMember = new HashMap<>();
    private final Map<UUID, HouseholdCareRecord> careRecords = new HashMap<>();
    private final Map<UUID, HouseholdProvisionPlan> provisionPlans = new HashMap<>();

    public static HouseholdData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public List<HouseholdRecord> households() {
        return households.values().stream().sorted(Comparator.comparing(HouseholdRecord::name)).toList();
    }

    @Nullable public HouseholdRecord byId(UUID id) { return households.get(id); }
    @Nullable public HouseholdCareRecord care(UUID householdId) { return careRecords.get(householdId); }

    public HouseholdProvisionPlan provisionPlan(UUID householdId) {
        HouseholdRecord household = households.get(householdId);
        if (household == null) return null;
        return provisionPlans.computeIfAbsent(householdId, ignored ->
                HouseholdProvisionPlan.forMembers(householdId, household.members().size()));
    }

    public void setProvisionPlan(HouseholdProvisionPlan plan) {
        if (!households.containsKey(plan.householdId())) return;
        provisionPlans.put(plan.householdId(), plan);
        setDirty();
    }

    @Nullable
    public HouseholdRecord forMember(UUID characterId) {
        UUID id = householdByMember.get(characterId);
        return id == null ? null : households.get(id);
    }

    public boolean sharedStorageAssigned(String dimension, BlockPos position) {
        return households.values().stream().anyMatch(household -> dimension.equals(household.dimension())
                && position.equals(household.sharedStorage()));
    }

    @Nullable
    public HouseholdRecord create(String name, String dimension, @Nullable UUID settlementId,
                                  UUID headCharacterId, @Nullable BlockPos homeAnchor, long gameTime) {
        return createWithId(UUID.randomUUID(), name, dimension, settlementId,
                headCharacterId, homeAnchor, gameTime);
    }

    @Nullable
    public HouseholdRecord createWithId(UUID id, String name, String dimension,
                                        @Nullable UUID settlementId, UUID headCharacterId,
                                        @Nullable BlockPos homeAnchor, long gameTime) {
        HouseholdRecord existing = households.get(id);
        if (existing != null) return existing;
        if (householdByMember.containsKey(headCharacterId)) return null;
        HouseholdRecord record = new HouseholdRecord(id, name, dimension, settlementId,
                headCharacterId, homeAnchor, null,
                List.of(new HouseholdMemberRecord(headCharacterId, HouseholdRole.HEAD, gameTime)), gameTime);
        households.put(id, record);
        householdByMember.put(headCharacterId, id);
        careRecords.put(id, HouseholdCareRecord.create(id));
        provisionPlans.put(id, HouseholdProvisionPlan.forMembers(id, 1));
        setDirty();
        return record;
    }

    public boolean addMember(UUID householdId, UUID characterId, HouseholdRole role, long gameTime) {
        HouseholdRecord household = households.get(householdId);
        UUID currentHousehold = householdByMember.get(characterId);
        if (household == null || role == HouseholdRole.HEAD
                || currentHousehold != null && !currentHousehold.equals(householdId)
                || !household.contains(characterId)
                && household.members().size() >= HouseholdRecord.MAX_MEMBERS) return false;
        households.put(householdId, household.withMember(characterId, role, gameTime));
        householdByMember.put(characterId, householdId);
        resizeProvisionPlan(householdId);
        setDirty();
        return true;
    }

    public boolean removeMember(UUID householdId, UUID characterId) {
        HouseholdRecord household = households.get(householdId);
        if (household == null || household.headCharacterId().equals(characterId)
                || !household.contains(characterId)) return false;
        households.put(householdId, household.withoutMember(characterId));
        householdByMember.remove(characterId);
        resizeProvisionPlan(householdId);
        setDirty();
        return true;
    }

    /** Commit a consenting non-head member exactly once; inventories and provision targets do not move. */
    public boolean transferMember(UUID person, UUID expectedSource, UUID destination, long now) {
        var from=households.get(expectedSource);var to=households.get(destination);
        if(from==null||to==null||expectedSource.equals(destination)
                ||!expectedSource.equals(householdByMember.get(person))||from.headCharacterId().equals(person)
                ||!from.contains(person)||to.contains(person)||to.members().size()>=HouseholdRecord.MAX_MEMBERS)return false;
        households.put(expectedSource,from.withoutMember(person));
        households.put(destination,to.withMember(person,HouseholdRole.KIN,now));
        householdByMember.put(person,destination);setDirty();return true;
    }

    public boolean setHead(UUID householdId, UUID characterId, long gameTime) {
        HouseholdRecord household = households.get(householdId);
        if (household == null || !household.contains(characterId)) return false;
        households.put(householdId, household.withHead(characterId, gameTime));
        setDirty();
        return true;
    }

    /**
     * 将同一家庭中的一组成员独立成新户。此操作只改变户籍关系；房屋和仓储不会被复制，
     * 新家庭必须随后取得自己的实体住所与储物容器。
     */
    @Nullable
    public HouseholdRecord split(UUID sourceHouseholdId, UUID newHouseholdId, String newName,
                                 UUID newHeadCharacterId, List<UUID> movingCharacterIds,
                                 long gameTime) {
        HouseholdRecord source = households.get(sourceHouseholdId);
        if (source == null || households.containsKey(newHouseholdId)
                || source.headCharacterId().equals(newHeadCharacterId)
                || movingCharacterIds == null) return null;

        Set<UUID> moving = new HashSet<>(movingCharacterIds);
        if (moving.isEmpty() || moving.size() != movingCharacterIds.size()
                || !moving.contains(newHeadCharacterId)
                || moving.contains(source.headCharacterId())
                || moving.stream().anyMatch(characterId -> !source.contains(characterId))) return null;

        List<HouseholdMemberRecord> staying = source.members().stream()
                .filter(member -> !moving.contains(member.characterId())).toList();
        if (staying.isEmpty() || staying.stream().noneMatch(member ->
                member.characterId().equals(source.headCharacterId()))) return null;

        List<HouseholdMemberRecord> leaving = source.members().stream()
                .filter(member -> moving.contains(member.characterId()))
                .map(member -> new HouseholdMemberRecord(member.characterId(),
                        member.characterId().equals(newHeadCharacterId) ? HouseholdRole.HEAD
                                : member.role() == HouseholdRole.HEAD ? HouseholdRole.KIN : member.role(),
                        member.joinedAt()))
                .toList();
        HouseholdRecord updatedSource = new HouseholdRecord(source.id(), source.name(),
                source.dimension(), source.settlementId(), source.headCharacterId(),
                source.homeAnchor(), source.sharedStorage(), staying, source.createdAt());
        HouseholdRecord created = new HouseholdRecord(newHouseholdId, newName, source.dimension(),
                source.settlementId(), newHeadCharacterId, null, null, leaving, gameTime);

        households.put(sourceHouseholdId, updatedSource);
        households.put(newHouseholdId, created);
        moving.forEach(characterId -> householdByMember.put(characterId, newHouseholdId));
        careRecords.put(newHouseholdId, HouseholdCareRecord.create(newHouseholdId));
        provisionPlans.put(newHouseholdId,
                HouseholdProvisionPlan.forMembers(newHouseholdId, leaving.size()));
        resizeProvisionPlan(sourceHouseholdId);
        setDirty();
        return created;
    }

    private void resizeProvisionPlan(UUID householdId) {
        HouseholdRecord household = households.get(householdId);
        if (household != null) {
            provisionPlans.put(householdId,
                    HouseholdProvisionPlan.forMembers(householdId, household.members().size()));
        }
    }

    public boolean assignSharedStorage(UUID householdId, String dimension,
                                       BlockPos homeAnchor, @Nullable BlockPos storage) {
        HouseholdRecord household = households.get(householdId);
        if (household == null || !household.dimension().equals(dimension)
                || storage != null && households.values().stream().anyMatch(other ->
                !other.id().equals(householdId) && dimension.equals(other.dimension())
                        && storage.equals(other.sharedStorage()))) return false;
        households.put(householdId, household.withHome(homeAnchor, storage));
        setDirty();
        return true;
    }

    /** Compare the old residence before committing an existing household to a new settlement. */
    public boolean relocate(UUID id, UUID expectedSettlement, BlockPos expectedStorage,
                            String dimension, UUID destination, BlockPos anchor, BlockPos storage) {
        var home=households.get(id);
        if(home==null||!java.util.Objects.equals(home.settlementId(),expectedSettlement)
                ||!java.util.Objects.equals(home.sharedStorage(),expectedStorage)||!home.dimension().equals(dimension)
                ||households.values().stream().anyMatch(h->!h.id().equals(id)&&dimension.equals(h.dimension())&&storage.equals(h.sharedStorage())))return false;
        households.put(id,new HouseholdRecord(home.id(),home.name(),dimension,destination,home.headCharacterId(),anchor,storage,home.members(),home.createdAt()));setDirty();return true;
    }

    public boolean disband(UUID householdId) {
        HouseholdRecord household = households.remove(householdId);
        if (household == null) return false;
        household.members().forEach(member -> householdByMember.remove(member.characterId()));
        careRecords.remove(householdId);
        provisionPlans.remove(householdId);
        setDirty();
        return true;
    }

    public HouseholdCareRecord assessCare(UUID householdId, long day, int pantryFood,
                                          int hungry, int critical, String summary) {
        HouseholdCareRecord previous = careRecords.computeIfAbsent(householdId,
                HouseholdCareRecord::create);
        boolean shortage = critical > 0 && pantryFood <= 0;
        int shortageDays = shortage ? previous.shortageDays() + 1 : 0;
        HouseholdCareRecord updated = previous.assessed(day, pantryFood, hungry,
                critical, shortageDays, summary);
        careRecords.put(householdId, updated);
        setDirty();
        return updated;
    }

    public void recordFoodContribution(UUID householdId, UUID contributor, long gameTime) {
        HouseholdCareRecord previous = careRecords.computeIfAbsent(householdId,
                HouseholdCareRecord::create);
        careRecords.put(householdId, previous.contributed(contributor, gameTime));
        setDirty();
    }

    /** 清除隔离实验场的照料冷却，避免上一次贡献阻止下一次复测。 */
    public void resetCareState(UUID householdId) {
        if (!households.containsKey(householdId)) return;
        careRecords.put(householdId, HouseholdCareRecord.create(householdId));
        setDirty();
    }

    /** 户主死亡时由仍在世的同户成员继任；没有幸存者的家庭作为历史户籍保留。 */
    public void handleCharacterDeath(MinecraftServer server, UUID characterId, long gameTime) {
        HouseholdRecord household = forMember(characterId);
        if (household == null || !household.headCharacterId().equals(characterId)) return;
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        HouseholdMemberRecord successor = household.members().stream()
                .filter(member -> !member.characterId().equals(characterId))
                .filter(member -> {
                    CharacterRecord character = characters.byId(member.characterId());
                    return character != null && character.alive();
                })
                .min(Comparator.comparingInt(member -> successionPriority(member.role())))
                .orElse(null);
        if (successor == null) return;
        households.put(household.id(), household.withHead(successor.characterId(), gameTime));
        WorldHistoryData.get(server).append(gameTime, "household_head_succeeded", household.id(),
                household.name(), successor.characterId().toString(), 18);
        setDirty();
    }

    private static int successionPriority(HouseholdRole role) {
        return switch (role) {
            case SPOUSE -> 0;
            case CHILD -> 1;
            case KIN -> 2;
            case DEPENDENT -> 3;
            case SERVANT -> 4;
            case LODGER -> 5;
            case HEAD -> 6;
        };
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("CombatImpacts",com.jgeted.sagadyssey.social.combat.CombatImpact.save(combatImpacts));
        ListTag list = new ListTag();
        households.values().forEach(household -> list.add(household.save()));
        tag.put("Households", list);
        ListTag careList = new ListTag();
        careRecords.values().forEach(record -> careList.add(record.save()));
        tag.put("CareRecords", careList);
        ListTag planList = new ListTag();
        provisionPlans.values().forEach(plan -> planList.add(plan.save()));
        tag.put("ProvisionPlans", planList);
        return tag;
    }

    private static HouseholdData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseholdData data = new HouseholdData();
        com.jgeted.sagadyssey.social.combat.CombatImpact.load(tag,data.combatImpacts);
        ListTag list = tag.getList("Households", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            HouseholdRecord household = HouseholdRecord.load(list.getCompound(index));
            if (household == null) continue;
            // 损坏存档中重复出现的人物只保留首次登记，避免一人多户。
            Set<UUID> localMembers = new HashSet<>();
            List<HouseholdMemberRecord> unique = household.members().stream()
                    .filter(member -> localMembers.add(member.characterId()))
                    .filter(member -> !data.householdByMember.containsKey(member.characterId())).toList();
            if (unique.stream().noneMatch(member -> member.characterId().equals(
                    household.headCharacterId()))) continue;
            HouseholdRecord repaired = new HouseholdRecord(household.id(), household.name(),
                    household.dimension(), household.settlementId(), household.headCharacterId(),
                    household.homeAnchor(), household.sharedStorage(), unique, household.createdAt());
            data.households.put(repaired.id(), repaired);
            unique.forEach(member -> data.householdByMember.put(member.characterId(), repaired.id()));
        }
        ListTag careList = tag.getList("CareRecords", Tag.TAG_COMPOUND);
        for (int index = 0; index < careList.size(); index++) {
            CompoundTag careTag = careList.getCompound(index);
            if (!careTag.hasUUID("Household")) continue;
            HouseholdCareRecord care = HouseholdCareRecord.load(careTag);
            if (data.households.containsKey(care.householdId())) {
                data.careRecords.put(care.householdId(), care);
            }
        }
        data.households.keySet().forEach(id -> data.careRecords.computeIfAbsent(
                id, HouseholdCareRecord::create));
        ListTag planList = tag.getList("ProvisionPlans", Tag.TAG_COMPOUND);
        for (int index = 0; index < planList.size(); index++) {
            CompoundTag planTag = planList.getCompound(index);
            if (!planTag.hasUUID("Household")) continue;
            HouseholdProvisionPlan plan = HouseholdProvisionPlan.load(planTag);
            if (data.households.containsKey(plan.householdId())) {
                data.provisionPlans.put(plan.householdId(), plan);
            }
        }
        data.households.forEach((id, household) -> data.provisionPlans.computeIfAbsent(id,
                ignored -> HouseholdProvisionPlan.forMembers(id, household.members().size())));
        return data;
    }
}
