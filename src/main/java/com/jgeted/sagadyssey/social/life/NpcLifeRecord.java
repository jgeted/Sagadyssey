package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * NPC 的生活与劳动登记。这里刻意把出生关系、私人住宅和当前工作权威分开。
 * 记录是不可变值；每次状态变化由 {@link NpcLifeData} 替换并标记世界数据为 dirty。
 */
public record NpcLifeRecord(
        UUID npcId,
        String dimension,
        @Nullable UUID originSettlementId,
        @Nullable UUID institutionWorkplaceSettlementId,
        @Nullable BlockPos institutionWorkplace,
        String residenceName,
        @Nullable BlockPos bedPosition,
        @Nullable BlockPos privateStoragePosition,
        NpcWorkAuthority authority,
        @Nullable UUID authorityId,
        @Nullable UUID activeWorkplaceSettlementId,
        @Nullable BlockPos activeWorkplace,
        NpcWorkShift shift,
        int courage,
        int duty,
        int ambition,
        int sociability,
        int conscience,
        int faith,
        int fatigue,
        NpcLongTermGoal longTermGoal,
        List<NpcRelationRecord> relations,
        List<NpcMemoryRecord> memories,
        String lastDecision,
        String lastDecisionReason,
        long lastDecisionAt,
        long decisionLockedUntil) {

    public NpcLifeRecord {
        dimension = dimension == null ? "" : dimension;
        residenceName = residenceName == null || residenceName.isBlank() ? "未分配住宅" : residenceName;
        authority = authority == null ? NpcWorkAuthority.NONE : authority;
        shift = shift == null ? NpcWorkShift.DAY : shift;
        courage = clampTrait(courage);
        duty = clampTrait(duty);
        ambition = clampTrait(ambition);
        sociability = clampTrait(sociability);
        conscience = clampTrait(conscience);
        faith = clampTrait(faith);
        fatigue = Math.max(0, Math.min(1000, fatigue));
        longTermGoal = longTermGoal == null ? NpcLongTermGoal.SURVIVE : longTermGoal;
        relations = relations == null ? List.of() : List.copyOf(relations);
        memories = memories == null ? List.of() : List.copyOf(memories);
        lastDecision = lastDecision == null ? "尚未决策" : lastDecision;
        lastDecisionReason = lastDecisionReason == null ? "" : lastDecisionReason;
    }

    public static NpcLifeRecord institution(UUID npcId, String dimension, UUID settlementId,
                                            @Nullable BlockPos workplace, String residenceName,
                                            @Nullable BlockPos bed, @Nullable BlockPos privateStorage,
                                            NpcWorkShift shift, long gameTime) {
        long seed = npcId.getMostSignificantBits() ^ Long.rotateLeft(npcId.getLeastSignificantBits(), 19);
        return new NpcLifeRecord(npcId, dimension, settlementId, settlementId, workplace,
                residenceName, bed, privateStorage, NpcWorkAuthority.INSTITUTION, settlementId,
                settlementId, workplace, shift,
                trait(seed, 0), trait(seed, 13), trait(seed, 29), trait(seed, 43),
                trait(seed, 53), trait(seed, 61),
                120, NpcLongTermGoal.SURVIVE, List.of(), List.of(),
                "登记生活", "接受聚落岗位与私人床位登记", gameTime, gameTime + 100L);
    }

    /** A permanent accepted move replaces the fallback workplace, preserving origin and personal history. */
    public NpcLifeRecord relocatedInstitution(UUID settlement, BlockPos workplace, BlockPos bed, long now) {
        return new NpcLifeRecord(npcId,dimension,originSettlementId,settlement,workplace,
                "接纳林工住宅",bed,null,NpcWorkAuthority.INSTITUTION,settlement,settlement,workplace,shift,
                courage,duty,ambition,sociability,conscience,faith,fatigue,longTermGoal,relations,memories,
                "迁入并接替林工作业","原私仓与离任者遗产保留原主；新产出贡献新家庭",now,now+200);
    }

    public NpcLifeRecord recruited(UUID playerCharacterId, long gameTime) {
        return copy(NpcWorkAuthority.SERVICE, playerCharacterId, null, null, fatigue,
                "接受招募", "原机构岗位和仓库权限暂停；出生地与私人财产保留", gameTime,
                gameTime + 200L);
    }

    public NpcLifeRecord dismissed(long gameTime) {
        NpcWorkAuthority restored = institutionWorkplaceSettlementId == null
                ? NpcWorkAuthority.NONE : NpcWorkAuthority.INSTITUTION;
        return copy(restored, institutionWorkplaceSettlementId, institutionWorkplaceSettlementId,
                institutionWorkplace, fatigue, "结束雇佣",
                restored == NpcWorkAuthority.INSTITUTION ? "恢复原机构岗位" : "当前没有可恢复的机构岗位",
                gameTime, gameTime + 200L);
    }

    public NpcLifeRecord withDecision(String decision, String reason, long gameTime,
                                      long lockedUntil, int newFatigue) {
        return copy(authority, authorityId, activeWorkplaceSettlementId, activeWorkplace,
                newFatigue, decision, reason, gameTime, lockedUntil);
    }

    public NpcLifeRecord withFatigue(int newFatigue) {
        return copy(authority, authorityId, activeWorkplaceSettlementId, activeWorkplace,
                newFatigue, lastDecision, lastDecisionReason, lastDecisionAt, decisionLockedUntil);
    }

    public NpcLifeRecord withResidence(String newResidenceName, @Nullable BlockPos bed,
                                       @Nullable BlockPos privateStorage, long gameTime) {
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, newResidenceName,
                bed, privateStorage, authority, authorityId, activeWorkplaceSettlementId,
                activeWorkplace, shift, courage, duty, ambition, sociability, conscience, faith, fatigue,
                longTermGoal, relations, memories,
                "住宅设施重登记", "原床位或私人储物失效，已分配替代设施",
                gameTime, gameTime + 200L);
    }

    public NpcLifeRecord withAssignment(NpcWorkAuthority newAuthority, @Nullable UUID newAuthorityId,
                                        @Nullable UUID workplaceSettlement, @Nullable BlockPos workplace,
                                        String decision, String reason, long gameTime) {
        return copy(newAuthority, newAuthorityId, workplaceSettlement, workplace, fatigue,
                decision, reason, gameTime, gameTime + 200L);
    }

    public NpcLifeRecord withShift(NpcWorkShift newShift, long gameTime) {
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, residenceName,
                bedPosition, privateStoragePosition, authority, authorityId,
                activeWorkplaceSettlementId, activeWorkplace, newShift, courage, duty,
                ambition, sociability, conscience, faith, fatigue, longTermGoal, relations, memories,
                "调整排班", "新的排班=" + newShift.name(), gameTime, gameTime + 100L);
    }

    public NpcLifeRecord withGoal(NpcLongTermGoal goal, long gameTime) {
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, residenceName,
                bedPosition, privateStoragePosition, authority, authorityId,
                activeWorkplaceSettlementId, activeWorkplace, shift, courage, duty,
                ambition, sociability, conscience, faith, fatigue, goal, relations, memories,
                "重新评估人生方向", "长期目标=" + goal.displayName(), gameTime, gameTime + 200L);
    }

    public NpcLifeRecord withRelation(NpcRelationRecord relation) {
        List<NpcRelationRecord> updated = new ArrayList<>(relations);
        updated.removeIf(existing -> existing.otherCharacterId().equals(relation.otherCharacterId()));
        updated.add(relation);
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, residenceName,
                bedPosition, privateStoragePosition, authority, authorityId,
                activeWorkplaceSettlementId, activeWorkplace, shift, courage, duty,
                ambition, sociability, conscience, faith, fatigue, longTermGoal, updated, memories,
                lastDecision, lastDecisionReason, lastDecisionAt, decisionLockedUntil);
    }

    public NpcLifeRecord withMemory(NpcMemoryRecord memory, long gameTime) {
        List<NpcMemoryRecord> updated = new ArrayList<>();
        for (NpcMemoryRecord existing : memories) {
            if (!existing.active(gameTime)) continue;
            boolean replacesRecent = existing.type().equals(memory.type())
                    && Objects.equals(existing.subjectCharacterId(), memory.subjectCharacterId())
                    && gameTime - existing.happenedAt() < 200L;
            if (!replacesRecent) updated.add(existing);
        }
        updated.add(memory);
        updated.sort((a, b) -> Long.compare(b.happenedAt(), a.happenedAt()));
        if (updated.size() > 16) updated = new ArrayList<>(updated.subList(0, 16));
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, residenceName,
                bedPosition, privateStoragePosition, authority, authorityId,
                activeWorkplaceSettlementId, activeWorkplace, shift, courage, duty,
                ambition, sociability, conscience, faith, fatigue, longTermGoal, relations, updated,
                lastDecision, lastDecisionReason, lastDecisionAt, decisionLockedUntil);
    }

    private NpcLifeRecord copy(NpcWorkAuthority newAuthority, @Nullable UUID newAuthorityId,
                               @Nullable UUID newWorkplaceSettlement, @Nullable BlockPos newWorkplace,
                               int newFatigue, String decision, String reason,
                               long decisionAt, long lockedUntil) {
        return new NpcLifeRecord(npcId, dimension, originSettlementId,
                institutionWorkplaceSettlementId, institutionWorkplace, residenceName,
                bedPosition, privateStoragePosition, newAuthority, newAuthorityId,
                newWorkplaceSettlement, newWorkplace, shift, courage, duty, ambition,
                sociability, conscience, faith, newFatigue, longTermGoal, relations, memories,
                decision, reason, decisionAt, lockedUntil);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Npc", UUIDUtil.CODEC, npcId);
        tag.putString("Dimension", dimension);
        putUuid(tag, "OriginSettlement", originSettlementId);
        putUuid(tag, "InstitutionSettlement", institutionWorkplaceSettlementId);
        putPos(tag, "InstitutionWorkplace", institutionWorkplace);
        tag.putString("ResidenceName", residenceName);
        putPos(tag, "Bed", bedPosition);
        putPos(tag, "PrivateStorage", privateStoragePosition);
        tag.putString("Authority", authority.name());
        putUuid(tag, "AuthorityId", authorityId);
        putUuid(tag, "ActiveSettlement", activeWorkplaceSettlementId);
        putPos(tag, "ActiveWorkplace", activeWorkplace);
        tag.putString("Shift", shift.name());
        tag.putInt("Courage", courage);
        tag.putInt("Duty", duty);
        tag.putInt("Ambition", ambition);
        tag.putInt("Sociability", sociability);
        tag.putInt("Conscience", conscience);
        tag.putInt("Faith", faith);
        tag.putInt("Fatigue", fatigue);
        tag.putString("LongTermGoal", longTermGoal.name());
        ListTag relationList = new ListTag();
        relations.forEach(relation -> relationList.add(relation.save()));
        tag.put("Relations", relationList);
        ListTag memoryList = new ListTag();
        memories.forEach(memory -> memoryList.add(memory.save()));
        tag.put("Memories", memoryList);
        tag.putString("LastDecision", lastDecision);
        tag.putString("LastDecisionReason", lastDecisionReason);
        tag.putLong("LastDecisionAt", lastDecisionAt);
        tag.putLong("DecisionLockedUntil", decisionLockedUntil);
        return tag;
    }

    @Nullable
    public static NpcLifeRecord load(CompoundTag tag) {
        if (!tag.read("Npc", UUIDUtil.CODEC).isPresent()) return null;
        List<NpcRelationRecord> relations = new ArrayList<>();
        ListTag relationList = MinecraftApi.listOfType(tag, "Relations", Tag.TAG_COMPOUND);
        for (int index = 0; index < relationList.size(); index++) {
            CompoundTag relation = relationList.getCompoundOrEmpty(index);
            if (relation.read("Other", UUIDUtil.CODEC).isPresent()) relations.add(NpcRelationRecord.load(relation));
        }
        List<NpcMemoryRecord> memories = new ArrayList<>();
        ListTag memoryList = MinecraftApi.listOfType(tag, "Memories", Tag.TAG_COMPOUND);
        for (int index = 0; index < memoryList.size(); index++) {
            memories.add(NpcMemoryRecord.load(memoryList.getCompoundOrEmpty(index)));
        }
        return new NpcLifeRecord(tag.read("Npc", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Dimension", ""),
                getUuid(tag, "OriginSettlement"), getUuid(tag, "InstitutionSettlement"),
                getPos(tag, "InstitutionWorkplace"), tag.getStringOr("ResidenceName", ""),
                getPos(tag, "Bed"), getPos(tag, "PrivateStorage"),
                NpcWorkAuthority.parse(tag.getStringOr("Authority", "")), getUuid(tag, "AuthorityId"),
                getUuid(tag, "ActiveSettlement"), getPos(tag, "ActiveWorkplace"),
                NpcWorkShift.parse(tag.getStringOr("Shift", "")), tag.getIntOr("Courage", 0),
                tag.getIntOr("Duty", 0), tag.getIntOr("Ambition", 0), tag.getIntOr("Sociability", 0),
                tag.contains("Conscience") ? tag.getIntOr("Conscience", 0) : 50,
                tag.contains("Faith") ? tag.getIntOr("Faith", 0) : 50,
                tag.getIntOr("Fatigue", 0), NpcLongTermGoal.parse(tag.getStringOr("LongTermGoal", "")),
                relations, memories, tag.getStringOr("LastDecision", ""),
                tag.getStringOr("LastDecisionReason", ""), tag.getLongOr("LastDecisionAt", 0L),
                tag.getLongOr("DecisionLockedUntil", 0L));
    }

    private static int trait(long seed, int rotation) {
        return 25 + (int) Math.floorMod(Long.rotateLeft(seed, rotation), 51L);
    }

    private static int clampTrait(int value) { return Math.max(0, Math.min(100, value)); }
    private static void putUuid(CompoundTag tag, String key, @Nullable UUID value) {
        if (value != null) tag.store(key, UUIDUtil.CODEC, value);
    }
    private static UUID getUuid(CompoundTag tag, String key) { return tag.read(key, UUIDUtil.CODEC).isPresent() ? tag.read(key, UUIDUtil.CODEC).orElseThrow() : null; }
    private static void putPos(CompoundTag tag, String key, @Nullable BlockPos value) {
        if (value != null) tag.putLong(key, value.asLong());
    }
    private static BlockPos getPos(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockPos.of(tag.getLongOr(key, 0L)) : null;
    }
}
