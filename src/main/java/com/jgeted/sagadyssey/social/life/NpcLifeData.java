package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.service.ServiceStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 世界级 NPC 生活档案：服务端唯一真相，不依赖实体是否正在加载。 */
public final class NpcLifeData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_npc_life";
    private static final Factory<NpcLifeData> FACTORY = new Factory<>(NpcLifeData::new, NpcLifeData::load);
   private final Map<UUID, NpcLifeRecord> records = new HashMap<>();
    private final Map<UUID, com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts = new HashMap<>();
    public java.util.List<com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts() { return java.util.List.copyOf(combatImpacts.values()); }
    public void recordCombatImpact(com.jgeted.sagadyssey.social.combat.CombatImpact impact) {
        if(com.jgeted.sagadyssey.social.combat.CombatImpact.put(combatImpacts,impact))setDirty();
    }
    private final Map<UUID, NpcWorkZoneRecord> workZones = new HashMap<>();
    private final Map<UUID, NpcEmploymentContract> contracts = new HashMap<>();
    private final Map<UUID, NpcNeedsRecord> needs = new HashMap<>();
    private final Map<UUID, NpcScheduleRecord> schedules = new HashMap<>();
    private final Map<UUID, Map<String, NpcActionDecisionRecord>> actionDecisions = new HashMap<>();

    public static NpcLifeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public static NpcLifeData from(NpcBase npc) {
        return npc.level() instanceof ServerLevel level ? get(level.getServer()) : null;
    }

    @Nullable public NpcLifeRecord record(UUID npcId) { return records.get(npcId); }
    @Nullable public NpcWorkZoneRecord workZone(UUID npcId) { return workZones.get(npcId); }
    @Nullable public NpcEmploymentContract contract(UUID npcId) { return contracts.get(npcId); }
    @Nullable public NpcNeedsRecord needs(UUID npcId) { return needs.get(npcId); }
    @Nullable public NpcScheduleRecord schedule(UUID npcId) { return schedules.get(npcId); }
    @Nullable public NpcActionDecisionRecord actionDecision(UUID npcId, String action) {
        Map<String, NpcActionDecisionRecord> decisions = actionDecisions.get(npcId);
        return decisions == null ? null : decisions.get(action);
    }
    public Collection<NpcLifeRecord> records() { return java.util.List.copyOf(records.values()); }
    public Collection<NpcScheduleRecord> schedules() { return java.util.List.copyOf(schedules.values()); }
    public Collection<NpcEmploymentContract> contracts() { return java.util.List.copyOf(contracts.values()); }

    public NpcNeedsRecord ensureNeeds(UUID npcId, long gameTime) {
        NpcNeedsRecord existing = needs.get(npcId);
        if (existing != null) return existing;
        NpcNeedsRecord created = NpcNeedsRecord.create(npcId, gameTime);
        needs.put(npcId, created);
        setDirty();
        return created;
    }

    public void increaseHunger(UUID npcId, int amount, long gameTime) {
        NpcNeedsRecord current = ensureNeeds(npcId, gameTime);
        int updated = Math.min(NpcNeedsRecord.MAX_HUNGER,
                current.hunger() + Math.max(0, amount));
        if (updated == current.hunger()) return;
        needs.put(npcId, current.withHunger(updated, gameTime));
        setDirty();
    }

    public void setHunger(UUID npcId, int value, long gameTime) {
        NpcNeedsRecord current = ensureNeeds(npcId, gameTime);
        needs.put(npcId, current.withHunger(value, gameTime));
        setDirty();
    }

    public void feed(UUID npcId, int restored, String source, long gameTime) {
        NpcNeedsRecord current = ensureNeeds(npcId, gameTime);
        if (restored <= 0) return;
        needs.put(npcId, current.fed(restored, source, gameTime));
        setDirty();
    }

    public void updateSchedule(UUID npcId, NpcScheduleActivity activity, String reason,
                               long gameTime, long reviewAt) {
        NpcScheduleRecord existing = schedules.get(npcId);
        if (existing != null && existing.activity() == activity
                && existing.reason().equals(reason) && existing.reviewAt() == reviewAt) return;
        schedules.put(npcId, new NpcScheduleRecord(npcId, activity, reason,
                existing != null && existing.activity() == activity
                        ? existing.updatedAt() : gameTime, reviewAt));
        setDirty();
    }

    public void captureInstitutionWorkZone(NpcBase npc) {
        boolean existed = workZones.containsKey(npc.getCharacterId());
        ensureInstitutionWorkZone(npc);
        if (!npc.isOwned() || existed) return;
        NpcWorkZoneRecord zone = workZones.get(npc.getCharacterId());
        if (zone != null && (zone.activeMin() != null || zone.activeMax() != null)) {
            workZones.put(npc.getCharacterId(), zone.recruited());
            npc.clearWorkZone();
            setDirty();
        }
    }

    public boolean bedAssigned(BlockPos position, String dimension) {
        return records.values().stream().anyMatch(record -> record.dimension().equals(dimension)
                && position.equals(record.bedPosition()));
    }

    public boolean privateStorageAssigned(BlockPos position, String dimension) {
        return records.values().stream().anyMatch(record -> record.dimension().equals(dimension)
                && position.equals(record.privateStoragePosition()));
    }

    public void registerInstitutional(NpcBase npc, String dimension, UUID settlementId,
                                      @Nullable BlockPos workplace, String residenceName,
                                      @Nullable BlockPos bed, @Nullable BlockPos privateStorage,
                                      NpcWorkShift shift, long gameTime, boolean repair) {
        NpcLifeRecord existing = records.get(npc.getCharacterId());
        ensureInstitutionWorkZone(npc);
        // 生成事件创建的“未分配住宅”档案可以被场景中的正式居民登记升级。
        if (existing != null && existing.originSettlementId() != null
                && (!repair || npc.isOwned())) return;
        records.put(npc.getCharacterId(), NpcLifeRecord.institution(npc.getCharacterId(), dimension, settlementId,
                workplace, residenceName, bed, privateStorage, shift, gameTime));
        ensureNeeds(npc.getCharacterId(), gameTime);
        setDirty();
    }

    /** 为任意生成/加载的 NPC 建立最低限度的永久生活档案。 */
    public NpcLifeRecord ensureRegistered(NpcBase npc, long gameTime) {
        NpcLifeRecord existing = records.get(npc.getCharacterId());
        if (existing != null) {
            ensureNeeds(npc.getCharacterId(), gameTime);
            return existing;
        }
        NpcLifeRecord created = ensureLegacy(npc, gameTime);
        records.put(npc.getCharacterId(), created);
        ensureNeeds(npc.getCharacterId(), gameTime);
        ensureInstitutionWorkZone(npc);
        setDirty();
        return created;
    }

    /** 招募只切换工作权威；不会篡改籍贯、住宅或私人财物。 */
    public void onRecruited(NpcBase npc, UUID playerCharacterId, long gameTime) {
        NpcLifeRecord record = ensureLegacy(npc, gameTime);
        ensureInstitutionWorkZone(npc);
        NpcWorkZoneRecord zone = workZones.get(npc.getCharacterId());
        if (zone != null) workZones.put(npc.getCharacterId(), zone.recruited());
        npc.clearWorkZone();
        records.put(npc.getCharacterId(), record.recruited(playerCharacterId, gameTime));
        if (npc.level() instanceof ServerLevel level) {
            ServiceRelationshipData services = ServiceRelationshipData.get(level.getServer());
            if (!services.hasActiveBetween(playerCharacterId, npc.getCharacterId(), gameTime)) {
                services.beginVoluntaryEmployment(playerCharacterId, npc.getCharacterId(), gameTime);
            }
        }
        ensureNeeds(npc.getCharacterId(), gameTime);
        setDirty();
    }

    public void onDismissed(NpcBase npc, long gameTime) {
        NpcLifeRecord record = records.get(npc.getCharacterId());
        if (record == null) return;
        NpcEmploymentContract contract = contracts.get(npc.getCharacterId());
        boolean dismissedWithDebt = contract != null && contract.status().ongoing()
                && (contract.unpaidEmeralds() > 0 || contract.paymentDue(gameTime));
        if (contract != null && contract.status().ongoing()) {
            contracts.put(npc.getCharacterId(), contract.ended(dismissedWithDebt
                            ? NpcContractStatus.BREACHED : NpcContractStatus.CANCELLED,
                    dismissedWithDebt ? "在到期工资未结清时解散 NPC，视为签约方违约"
                            : "NPC 被解散，合同随指挥关系结束", gameTime));
        }
        NpcWorkZoneRecord zone = workZones.get(npc.getCharacterId());
        if (zone != null) {
            zone = zone.dismissed();
            workZones.put(npc.getCharacterId(), zone);
            if (zone.activeMin() != null && zone.activeMax() != null) {
                npc.setWorkZone(zone.activeMin(), zone.activeMax());
            } else {
                npc.clearWorkZone();
            }
        }
        records.put(npc.getCharacterId(), record.dismissed(gameTime));
        if (npc.level() instanceof ServerLevel level) {
            ServiceRelationshipData.get(level.getServer()).endActiveForAgent(npc.getCharacterId(),
                    dismissedWithDebt ? ServiceStatus.BREACHED : ServiceStatus.CANCELLED,
                    dismissedWithDebt ? "欠薪状态下被单方面解除" : "双方服务关系已解除", gameTime);
        }
        if (dismissedWithDebt) {
            adjustRelationship(npc.getCharacterId(), contract.employerCharacterId(),
                    NpcRelationKind.DEPENDENT, -30, "未结清工资便遭解散");
            remember(npc.getCharacterId(), new NpcMemoryRecord("contract_breached",
                    contract.employerCharacterId(), "在工资未结清时被解散", -90, 90,
                    gameTime, gameTime + 168_000L), gameTime);
        }
        setDirty();
    }

    public void onNpcDeath(UUID npcId, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract == null || !contract.status().ongoing()) return;
        contracts.put(npcId, contract.ended(NpcContractStatus.CANCELLED,
                "NPC 死亡，合同终止", gameTime));
        setDirty();
    }

    /** 人物永久死亡会结束其工作权威；同账号下一世不会继承这些合同或岗位。 */
    public void onAuthorityDeath(MinecraftServer server, UUID characterId, long gameTime) {
        boolean changed = false;
        for (NpcLifeRecord record : java.util.List.copyOf(records.values())) {
            if (!characterId.equals(record.authorityId())
                    || record.authority() != NpcWorkAuthority.SERVICE
                    && record.authority() != NpcWorkAuthority.CONTRACT) continue;
            NpcEmploymentContract contract = contracts.get(record.npcId());
            if (contract != null && contract.status().ongoing()) {
                contracts.put(record.npcId(), contract.ended(NpcContractStatus.CANCELLED,
                        "签约人物死亡，合同终止且不会由下一世继承", gameTime));
            }
            records.put(record.npcId(), record.dismissed(gameTime));
            NpcWorkZoneRecord zone = workZones.get(record.npcId());
            if (zone != null) {
                zone = zone.dismissed();
                workZones.put(record.npcId(), zone);
                NpcBase npc = CharacterRegistryData.get(server)
                        .findLoadedNpc(server, record.npcId());
                if (npc != null) {
                    if (zone.activeMin() != null && zone.activeMax() != null) {
                        npc.setWorkZone(zone.activeMin(), zone.activeMax());
                    } else {
                        npc.clearWorkZone();
                    }
                }
            }
            changed = true;
        }
        if (changed) setDirty();
    }

    public void updateDecision(UUID npcId, String decision, String reason,
                               long gameTime, long lockedUntil, int fatigue) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return;
        records.put(npcId, record.withDecision(decision, reason, gameTime, lockedUntil, fatigue));
        setDirty();
    }

    public void updateActionDecision(NpcActionDecisionRecord decision) {
        actionDecisions.computeIfAbsent(decision.npcId(), ignored -> new HashMap<>())
                .put(decision.action(), decision);
        setDirty();
    }

    /**
     * 清除一次可重复测试不应继承的短期状态，同时保留身份、关系、住宅、岗位与长期记忆。
     * 场景控制器只会对仍由原机构管理的 NPC 调用此方法。
     */
    public void resetTransientState(UUID npcId, int hunger, int fatigue, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return;
        NpcNeedsRecord currentNeeds = ensureNeeds(npcId, gameTime);
        needs.put(npcId, currentNeeds.withHunger(hunger, gameTime));
        records.put(npcId, record.withDecision("实验场已重置", "清除旧测试的短期决策与疲劳",
                gameTime, gameTime, fatigue));
        actionDecisions.remove(npcId);
        schedules.remove(npcId);
        setDirty();
    }

    public void updateFatigue(UUID npcId, int fatigue) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || record.fatigue() == fatigue) return;
        records.put(npcId, record.withFatigue(fatigue));
        setDirty();
    }

    public void suspendForMigration(NpcBase npc, long now) {
        var r=records.get(npc.getCharacterId());
        if(r==null||r.authority()!=NpcWorkAuthority.INSTITUTION)throw new IllegalStateException("No institutional departure");
        records.put(npc.getCharacterId(),r.withAssignment(NpcWorkAuthority.NONE,null,null,null,
                "同意步行迁居","旧岗位暂停；成员、财产与原住处在抵达提交前保留",now));
        workZones.put(npc.getCharacterId(),new NpcWorkZoneRecord(npc.getCharacterId(),null,null,null,null));
        npc.clearWorkZone();setDirty();
    }

    public void commitWorkerMigration(NpcBase npc, UUID destination, BlockPos bed, BlockPos min, BlockPos max, long now) {
        var r=records.get(npc.getCharacterId());
        if(r==null||r.authority()!=NpcWorkAuthority.NONE)throw new IllegalStateException("Departure not suspended");
        records.put(npc.getCharacterId(),r.relocatedInstitution(destination,min,bed,now));
        workZones.put(npc.getCharacterId(),new NpcWorkZoneRecord(npc.getCharacterId(),min,max,min,max));
        npc.setHomeSettlementId(destination);npc.setWorkZone(min,max);setDirty();
    }

    public void updateResidence(UUID npcId, String residenceName, @Nullable BlockPos bed,
                                @Nullable BlockPos privateStorage, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return;
        records.put(npcId, record.withResidence(residenceName, bed, privateStorage, gameTime));
        setDirty();
    }

    public boolean assignBed(UUID npcId, String dimension, BlockPos bed,
                             String residenceName, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || !record.dimension().equals(dimension)
                || records.values().stream().anyMatch(other -> !other.npcId().equals(npcId)
                && dimension.equals(other.dimension()) && bed.equals(other.bedPosition()))) return false;
        records.put(npcId, record.withResidence(residenceName, bed,
                record.privateStoragePosition(), gameTime));
        setDirty();
        return true;
    }

    public boolean assignPrivateStorage(MinecraftServer server, UUID npcId, String dimension,
                                        BlockPos storage, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || !record.dimension().equals(dimension)
                || HouseholdData.get(server).sharedStorageAssigned(dimension, storage)
                || records.values().stream().anyMatch(other -> !other.npcId().equals(npcId)
                && dimension.equals(other.dimension())
                && storage.equals(other.privateStoragePosition()))) return false;
        records.put(npcId, record.withResidence(record.residenceName(), record.bedPosition(),
                storage, gameTime));
        setDirty();
        return true;
    }

    public boolean assignEmployerWorkplace(UUID npcId, UUID playerCharacterId,
                                           @Nullable BlockPos workplace, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || (record.authority() != NpcWorkAuthority.SERVICE
                && record.authority() != NpcWorkAuthority.CONTRACT)
                || !playerCharacterId.equals(record.authorityId())) return false;
        records.put(npcId, record.withAssignment(record.authority(), playerCharacterId,
                record.activeWorkplaceSettlementId(), workplace, "接受玩家岗位", "工作地点="
                + (workplace == null ? "未分配" : workplace.toShortString()), gameTime));
        setDirty();
        return true;
    }

    public boolean assignEmployerWorkZone(NpcBase npc, UUID playerCharacterId,
                                          @Nullable BlockPos min, @Nullable BlockPos max) {
        NpcLifeRecord record = records.get(npc.getCharacterId());
        if (record == null || (record.authority() != NpcWorkAuthority.SERVICE
                && record.authority() != NpcWorkAuthority.CONTRACT)
                || !playerCharacterId.equals(record.authorityId())) return false;
        NpcWorkZoneRecord zone = workZones.computeIfAbsent(npc.getCharacterId(),
                id -> new NpcWorkZoneRecord(id, null, null, null, null));
        workZones.put(npc.getCharacterId(), zone.assigned(min, max));
        if (min == null || max == null) npc.clearWorkZone();
        else npc.setWorkZone(min, max);
        setDirty();
        return true;
    }

    public boolean beginContract(UUID npcId, UUID playerCharacterId, UUID settlementId,
                                 @Nullable BlockPos workplace, long gameTime) {
        return beginContract(npcId, playerCharacterId, settlementId, workplace,
                NpcEmploymentContract.DEFAULT_DURATION_DAYS,
                NpcEmploymentContract.DEFAULT_WAGE, gameTime);
    }

    public boolean beginContract(UUID npcId, UUID playerCharacterId, UUID settlementId,
                                 @Nullable BlockPos workplace, int durationDays,
                                 int wageEmeralds, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        NpcEmploymentContract existing = contracts.get(npcId);
        if (record == null || (record.authority() != NpcWorkAuthority.SERVICE
                && record.authority() != NpcWorkAuthority.CONTRACT)
                || !playerCharacterId.equals(record.authorityId())
                || existing != null && existing.status().ongoing()) return false;
        NpcEmploymentContract contract = NpcEmploymentContract.begin(npcId, playerCharacterId,
                settlementId, durationDays, wageEmeralds, gameTime);
        contracts.put(npcId, contract);
        records.put(npcId, record.withAssignment(NpcWorkAuthority.CONTRACT, playerCharacterId,
                settlementId, workplace, "签订外派合同",
                "玩家仍是指挥方；期限=" + durationDays + "日，日薪="
                        + wageEmeralds + "绿宝石；工资必须进入私人储物", gameTime));
        setDirty();
        return true;
    }

    public boolean renewContract(UUID npcId, UUID playerCharacterId, int durationDays, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        NpcLifeRecord record = records.get(npcId);
        if (contract == null || record == null || !contract.status().ongoing()
                || !playerCharacterId.equals(contract.employerCharacterId())
                || !playerCharacterId.equals(record.authorityId())) return false;
        contracts.put(npcId, contract.renewed(durationDays, gameTime));
        records.put(npcId, record.withAssignment(NpcWorkAuthority.CONTRACT, playerCharacterId,
                record.activeWorkplaceSettlementId(), record.activeWorkplace(), "合同续期",
                "增加 " + durationDays + " 个游戏日；欠薪状态不会因续期消失", gameTime));
        setDirty();
        return true;
    }

    public boolean endContract(UUID npcId, UUID playerCharacterId, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || record.authority() != NpcWorkAuthority.CONTRACT
                || !playerCharacterId.equals(record.authorityId())) return false;
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract != null && (contract.unpaidEmeralds() > 0
                || contract.paymentDue(gameTime))) return false;
        if (contract != null) contracts.put(npcId, contract.ended(NpcContractStatus.CANCELLED,
                "签约方主动解除合同", gameTime));
        records.put(npcId, record.dismissed(gameTime).withDecision("结束外派合同",
                "双方服务权限终止；恢复原机构岗位或自由身份", gameTime,
                gameTime + 200L, record.fatigue()));
        setDirty();
        return true;
    }

    public void recordContractPayment(UUID npcId, int amount, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract == null || !contract.status().ongoing()) return;
        contracts.put(npcId, contract.paid(amount, gameTime));
        adjustRelationship(npcId, contract.employerCharacterId(), NpcRelationKind.DEPENDENT,
                6, "按时支付工资形成的信任");
        remember(npcId, new NpcMemoryRecord("wage_paid", contract.employerCharacterId(),
                "按合同收到 " + amount + " 枚绿宝石工资", 55, 55,
                gameTime, gameTime + 72_000L), gameTime);
        setDirty();
    }

    public void recordContractPaymentBlocked(UUID npcId, String reason, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract == null || !contract.status().ongoing()) return;
        if (contract.statusReason().equals(reason)
                && gameTime - contract.statusChangedAt() < 1_200L) return;
        contracts.put(npcId, contract.paymentBlocked(reason, gameTime));
        setDirty();
    }

    public boolean recordMissedContractPayment(UUID npcId, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract == null || !contract.status().ongoing()) return false;
        NpcEmploymentContract updated = contract.missedPayment(gameTime);
        contracts.put(npcId, updated);
        adjustRelationship(npcId, contract.employerCharacterId(), NpcRelationKind.DEPENDENT,
                updated.status() == NpcContractStatus.BREACHED ? -28 : -18,
                updated.status() == NpcContractStatus.BREACHED ? "欠薪违约造成的不信任" : "欠薪削弱信任");
        remember(npcId, new NpcMemoryRecord("wage_missed", contract.employerCharacterId(),
                updated.statusReason(), -75, 75, gameTime, gameTime + 120_000L), gameTime);
        if (updated.status() == NpcContractStatus.BREACHED) {
            return finishContractAuthority(npcId, "拒绝继续外派",
                    "连续欠薪导致合同违约；终止服务并恢复原机构岗位", gameTime);
        }
        setDirty();
        return false;
    }

    public boolean completeContract(UUID npcId, long gameTime) {
        NpcEmploymentContract contract = contracts.get(npcId);
        if (contract == null || !contract.readyToComplete(gameTime)) return false;
        contracts.put(npcId, contract.ended(NpcContractStatus.COMPLETED,
                "期限届满且工资结清", gameTime));
        return finishContractAuthority(npcId, "合同期满",
                "工资结清，服务权限撤销；恢复原机构岗位", gameTime);
    }

    private boolean finishContractAuthority(UUID npcId, String decision, String reason, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || record.authority() != NpcWorkAuthority.CONTRACT) {
            setDirty();
            return false;
        }
        records.put(npcId, record.dismissed(gameTime).withDecision(decision, reason,
                gameTime, gameTime + 200L, record.fatigue()));
        setDirty();
        return true;
    }

    public boolean setShift(UUID npcId, NpcWorkShift shift, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return false;
        records.put(npcId, record.withShift(shift, gameTime));
        setDirty();
        return true;
    }

    public boolean setLongTermGoal(UUID npcId, NpcLongTermGoal goal, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return false;
        records.put(npcId, record.withGoal(goal, gameTime));
        setDirty();
        return true;
    }

    public void linkRelationship(UUID first, UUID second, NpcRelationKind kind,
                                 int strength, String note) {
        if (first.equals(second)) return;
        NpcLifeRecord firstRecord = records.get(first);
        NpcLifeRecord secondRecord = records.get(second);
        if (firstRecord == null || secondRecord == null) return;
        records.put(first, firstRecord.withRelation(new NpcRelationRecord(second, kind, strength, note)));
        records.put(second, secondRecord.withRelation(new NpcRelationRecord(first, kind, strength, note)));
        setDirty();
    }

    /** 单向主观信任变化使用边际递减，重复奖励不会线性堆到满值。 */
    public void adjustRelationship(UUID npcId, UUID otherCharacterId, NpcRelationKind defaultKind,
                                   int requestedChange, String note) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null || npcId.equals(otherCharacterId) || requestedChange == 0) return;
        NpcRelationRecord existing = record.relations().stream()
                .filter(relation -> relation.otherCharacterId().equals(otherCharacterId))
                .findFirst().orElse(null);
        int current = existing == null ? 0 : existing.strength();
        double remaining = requestedChange > 0 ? (100.0D - current) / 100.0D
                : (100.0D + current) / 100.0D;
        int effective = (int) Math.copySign(Math.max(1L,
                Math.round(Math.abs(requestedChange) * Math.max(0.15D, remaining))), requestedChange);
        int updated = Math.max(-100, Math.min(100, current + effective));
        NpcRelationKind kind = existing == null ? defaultKind : existing.kind();
        records.put(npcId, record.withRelation(new NpcRelationRecord(otherCharacterId,
                kind, updated, note)));
        setDirty();
    }

    public void remember(UUID npcId, NpcMemoryRecord memory, long gameTime) {
        NpcLifeRecord record = records.get(npcId);
        if (record == null) return;
        records.put(npcId, record.withMemory(memory, gameTime));
        setDirty();
    }

    private NpcLifeRecord ensureLegacy(NpcBase npc, long gameTime) {
        NpcLifeRecord existing = records.get(npc.getCharacterId());
        if (existing != null) return existing;
        UUID home = npc.getHomeSettlementId();
        String dimension = npc.level().dimension().location().toString();
        if (home != null) {
            NpcLifeRecord created = NpcLifeRecord.institution(npc.getCharacterId(), dimension, home,
                    npc.blockPosition(), "旧存档住宅待分配", null, null, NpcWorkShift.DAY, gameTime);
            records.put(npc.getCharacterId(), created);
            setDirty();
            return created;
        }
        return new NpcLifeRecord(npc.getCharacterId(), dimension, null, null, null,
                "未分配住宅", null, null, NpcWorkAuthority.NONE, null, null, null,
                NpcWorkShift.DAY, 50, 50, 50, 50, 50, 50, 100,
                NpcLongTermGoal.SURVIVE, java.util.List.of(), java.util.List.of(),
                "迁移旧存档", "没有可恢复的机构岗位", gameTime, gameTime + 100L);
    }

    private void ensureInstitutionWorkZone(NpcBase npc) {
        if (workZones.containsKey(npc.getCharacterId())) return;
        BlockPos min = npc.hasWorkZone() ? npc.getWorkZoneMin() : null;
        BlockPos max = npc.hasWorkZone() ? npc.getWorkZoneMax() : null;
        workZones.put(npc.getCharacterId(), new NpcWorkZoneRecord(npc.getCharacterId(), min, max, min, max));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("CombatImpacts",com.jgeted.sagadyssey.social.combat.CombatImpact.save(combatImpacts));
        ListTag list = new ListTag();
        records.values().forEach(record -> list.add(record.save()));
        tag.put("Records", list);
        ListTag zoneList = new ListTag();
        workZones.values().forEach(zone -> zoneList.add(zone.save()));
        tag.put("WorkZones", zoneList);
        ListTag contractList = new ListTag();
        contracts.values().forEach(contract -> contractList.add(contract.save()));
        tag.put("Contracts", contractList);
        ListTag needsList = new ListTag();
        needs.values().forEach(record -> needsList.add(record.save()));
        tag.put("Needs", needsList);
        ListTag scheduleList = new ListTag();
        schedules.values().forEach(record -> scheduleList.add(record.save()));
        tag.put("Schedules", scheduleList);
        ListTag decisionList = new ListTag();
        actionDecisions.values().forEach(byAction -> byAction.values()
                .forEach(record -> decisionList.add(record.save())));
        tag.put("ActionDecisions", decisionList);
        return tag;
    }

    private static NpcLifeData load(CompoundTag tag, HolderLookup.Provider registries) {
        NpcLifeData data = new NpcLifeData();
        com.jgeted.sagadyssey.social.combat.CombatImpact.load(tag,data.combatImpacts);
        ListTag list = tag.getList("Records", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            NpcLifeRecord record = NpcLifeRecord.load(list.getCompound(index));
            if (record != null) data.records.put(record.npcId(), record);
        }
        ListTag zoneList = tag.getList("WorkZones", Tag.TAG_COMPOUND);
        for (int index = 0; index < zoneList.size(); index++) {
            CompoundTag zoneTag = zoneList.getCompound(index);
            if (!zoneTag.hasUUID("Npc")) continue;
            NpcWorkZoneRecord zone = NpcWorkZoneRecord.load(zoneTag);
            data.workZones.put(zone.npcId(), zone);
        }
        ListTag contractList = tag.getList("Contracts", Tag.TAG_COMPOUND);
        for (int index = 0; index < contractList.size(); index++) {
            CompoundTag contractTag = contractList.getCompound(index);
            if (!contractTag.hasUUID("Npc") || !contractTag.hasUUID("Employer")
                    || !contractTag.hasUUID("Settlement")) continue;
            NpcEmploymentContract contract = NpcEmploymentContract.load(contractTag);
            data.contracts.put(contract.npcId(), contract);
        }
        ListTag needsList = tag.getList("Needs", Tag.TAG_COMPOUND);
        for (int index = 0; index < needsList.size(); index++) {
            CompoundTag needsTag = needsList.getCompound(index);
            if (!needsTag.hasUUID("Npc")) continue;
            NpcNeedsRecord record = NpcNeedsRecord.load(needsTag);
            data.needs.put(record.npcId(), record);
        }
        ListTag scheduleList = tag.getList("Schedules", Tag.TAG_COMPOUND);
        for (int index = 0; index < scheduleList.size(); index++) {
            CompoundTag scheduleTag = scheduleList.getCompound(index);
            if (!scheduleTag.hasUUID("Npc")) continue;
            NpcScheduleRecord record = NpcScheduleRecord.load(scheduleTag);
            data.schedules.put(record.npcId(), record);
        }
        ListTag decisionList = tag.getList("ActionDecisions", Tag.TAG_COMPOUND);
        for (int index = 0; index < decisionList.size(); index++) {
            CompoundTag decisionTag = decisionList.getCompound(index);
            if (!decisionTag.hasUUID("Npc") || decisionTag.getString("Action").isBlank()) continue;
            NpcActionDecisionRecord record = NpcActionDecisionRecord.load(decisionTag);
            data.actionDecisions.computeIfAbsent(record.npcId(), ignored -> new HashMap<>())
                    .put(record.action(), record);
        }
        // r8 的外派合同只有权限开关；迁移成有期限、有工资的真实合同。
        for (NpcLifeRecord record : data.records.values()) {
            if (record.authority() != NpcWorkAuthority.CONTRACT || data.contracts.containsKey(record.npcId())
                    || record.authorityId() == null || record.activeWorkplaceSettlementId() == null) continue;
            long started = Math.max(0L, record.lastDecisionAt());
            data.contracts.put(record.npcId(), NpcEmploymentContract.begin(record.npcId(),
                    record.authorityId(), record.activeWorkplaceSettlementId(),
                    NpcEmploymentContract.DEFAULT_DURATION_DAYS,
                    NpcEmploymentContract.DEFAULT_WAGE, started));
        }
        return data;
    }
}
