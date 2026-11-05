package com.jgeted.sagadyssey.social.governance;

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

/** Auditable assessments and physical payment evidence for taxes, rent, dues, tithes, and fines. */
public final class GovernanceLedgerData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_governance_ledger";
    private static final Factory<GovernanceLedgerData> FACTORY =
            new Factory<>(GovernanceLedgerData::new, GovernanceLedgerData::load);
    private final Map<UUID, LevyAssessment> assessments = new HashMap<>();
    private final Map<UUID, LevyPaymentRecord> payments = new HashMap<>();

    public static GovernanceLedgerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    boolean add(LevyAssessment assessment) {
        if (assessments.putIfAbsent(assessment.id(), assessment) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public LevyAssessment assessment(UUID id) { return assessments.get(id); }
    @Nullable public LevyPaymentRecord payment(UUID id) { return payments.get(id); }

    public List<LevyAssessment> obligationsOf(SubjectRef debtor, long now) {
        reviewDue(now);
        return assessments.values().stream().filter(value -> value.debtor().equals(debtor)).toList();
    }

    public void reviewDue(long now) {
        boolean changed = false;
        for (LevyAssessment assessment : List.copyOf(assessments.values())) {
            LevyAssessment reviewed = assessment.review(now);
            if (!reviewed.equals(assessment)) {
                assessments.put(reviewed.id(), reviewed);
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    boolean recordPayment(UUID paymentId, UUID assessmentId, int quantity, long now) {
        if (payments.containsKey(paymentId)) return false;
        LevyAssessment assessment = assessments.get(assessmentId);
        if (assessment == null) throw new IllegalStateException("征收项目不存在");
        assessments.put(assessmentId, assessment.paid(quantity, now));
        payments.put(paymentId, new LevyPaymentRecord(paymentId, assessmentId,
                quantity, now, paymentId));
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag assessmentTags = new ListTag();
        assessments.values().forEach(value -> assessmentTags.add(value.save()));
        tag.put("Assessments", assessmentTags);
        ListTag paymentTags = new ListTag();
        payments.values().forEach(value -> paymentTags.add(value.save()));
        tag.put("Payments", paymentTags);
        return tag;
    }

    private static GovernanceLedgerData load(CompoundTag tag, HolderLookup.Provider registries) {
        GovernanceLedgerData data = new GovernanceLedgerData();
        ListTag assessments = tag.getList("Assessments", Tag.TAG_COMPOUND);
        for (int index = 0; index < assessments.size(); index++) {
            LevyAssessment value = LevyAssessment.load(assessments.getCompound(index));
            data.assessments.put(value.id(), value);
        }
        ListTag payments = tag.getList("Payments", Tag.TAG_COMPOUND);
        for (int index = 0; index < payments.size(); index++) {
            LevyPaymentRecord value = LevyPaymentRecord.load(payments.getCompound(index));
            data.payments.put(value.paymentId(), value);
        }
        return data;
    }
}
