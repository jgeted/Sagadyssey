package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import java.util.*;
import java.io.IOException;

public final class ServiceProvisionCodec extends NbtSnapshotCodec<ServiceProvision> {
    private static final PhysicalItemOperationCodec ITEMS = new PhysicalItemOperationCodec();
    public ServiceProvisionCodec() { super("sagadyssey:service_provision/v1", 16 * 1024 * 1024, 4_096); }
    protected UUID identity(ServiceProvision value) { return value.terms().id(); }
    protected CompoundTag encode(ServiceProvision value) {
        var tag = new CompoundTag(); var t = value.terms(); var terms = new CompoundTag();
        terms.store("Id", UUIDUtil.CODEC, t.id()); terms.putString("OwnerKind", t.ownerKind()); terms.store("Owner", UUIDUtil.CODEC, t.owner()); terms.store("Person", UUIDUtil.CODEC, t.person()); terms.store("Body", UUIDUtil.CODEC, t.body());
        terms.put("Payer", t.payer().save()); terms.put("Treasury", t.treasury().save()); terms.put("Wage", t.wageItem().save()); terms.putLong("Rate", t.rate()); terms.putLong("Period", t.period()); tag.put("Terms", terms);
        tag.putLong("Revision", value.revision()); tag.putLong("At", value.changedAt()); tag.putLong("Sequence", value.nextSequence());
        var marks = new ListTag(); value.marks().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var row = new CompoundTag(); row.putString("Step", entry.getKey().name()); row.store("Operation", UUIDUtil.CODEC, entry.getValue().operation());
            row.putLong("At", entry.getValue().at()); row.putLong("Ack", entry.getValue().acknowledgedAt()); row.putBoolean("Outstanding", entry.getValue().outstanding()); marks.add(row);
        }); tag.put("Marks", marks); value.pending().ifPresent(attempt -> tag.put("Pending", attempt(attempt)));
        var history = new ListTag(); for (var entry : value.settlements()) {
            var row = new CompoundTag(); row.put("Attempt", attempt(entry.attempt())); row.putBoolean("Applied", entry.applied()); row.putLong("At", entry.occurredAt()); history.add(row);
        } tag.put("Settlements", history); return tag;
    }
    private static CompoundTag attempt(ServiceProvision.Attempt value) {
        var tag = new CompoundTag(); tag.putString("Purpose", value.purpose().name()); tag.putLong("Sequence", value.sequence()); tag.putInt("Value", value.value());
        try { tag.putByteArray("Intent", ITEMS.write(List.of(new PhysicalItemOperation(value.intent(), PhysicalItemOperation.Phase.INTENT, Optional.empty())))); }
        catch (IOException exception) { throw new IllegalArgumentException("invalid service physical intent", exception); } return tag;
    }
    protected ServiceProvision decode(CompoundTag tag) {
        fields(tag, tag.contains("Pending") ? Set.of("Terms", "Revision", "At", "Sequence", "Marks", "Pending", "Settlements") : Set.of("Terms", "Revision", "At", "Sequence", "Marks", "Settlements"));
        var t = compound(tag, "Terms"); fields(t, Set.of("Id", "OwnerKind", "Owner", "Person", "Body", "Payer", "Treasury", "Wage", "Rate", "Period"));
        var payer = compound(t, "Payer"); fields(payer, Set.of("Type", "Id")); uuid(payer, "Id"); string(payer, "Type");
        var wage = compound(t, "Wage"); fields(wage, Set.of("Item", "Components")); string(wage, "Item"); if (!MinecraftApi.containsTag(wage, "Components", Tag.TAG_INT)) throw new IllegalArgumentException("missing wage identity");
        var terms = new ServiceProvision.Terms(uuid(t, "Id"), string(t, "OwnerKind"), uuid(t, "Owner"), uuid(t, "Person"), uuid(t, "Body"), SubjectRef.load(payer),
                ItemLocation.load(compound(t, "Treasury")), ItemFingerprint.load(wage), number(t, "Rate"), number(t, "Period"));
        Map<ServiceProvision.Step, ServiceProvision.Mark> marks = new EnumMap<>(ServiceProvision.Step.class);
        for (Tag raw : rows(tag, "Marks", 4)) { var row = (CompoundTag) raw; fields(row, Set.of("Step", "Operation", "At", "Ack", "Outstanding"));
            if (!MinecraftApi.containsTag(row, "Outstanding", Tag.TAG_BYTE) || row.getByteOr("Outstanding", (byte)0) < 0 || row.getByteOr("Outstanding", (byte)0) > 1) throw new IllegalArgumentException("invalid outstanding flag");
            if (marks.putIfAbsent(ServiceProvision.Step.valueOf(string(row, "Step")), new ServiceProvision.Mark(uuid(row, "Operation"), number(row, "At"), number(row, "Ack"), row.getBooleanOr("Outstanding", false))) != null) throw new IllegalArgumentException("duplicate service mark"); }
        var history = new ArrayList<ServiceProvision.Settlement>(); for (Tag raw : rows(tag, "Settlements", 1024)) {
            var row = (CompoundTag) raw; fields(row, Set.of("Attempt", "Applied", "At"));
            if (!MinecraftApi.containsTag(row, "Applied", Tag.TAG_BYTE) || row.getByteOr("Applied", (byte)0) < 0 || row.getByteOr("Applied", (byte)0) > 1) throw new IllegalArgumentException("invalid settlement flag");
            history.add(new ServiceProvision.Settlement(attempt(compound(row, "Attempt")), row.getBooleanOr("Applied", false), number(row, "At")));
        }
        return new ServiceProvision(terms, number(tag, "Revision"), number(tag, "At"), marks, tag.contains("Pending") ? Optional.of(attempt(compound(tag, "Pending"))) : Optional.empty(), history, number(tag, "Sequence"));
    }
    private static ServiceProvision.Attempt attempt(CompoundTag tag) {
        fields(tag, Set.of("Purpose", "Sequence", "Value", "Intent"));
        if (!MinecraftApi.containsTag(tag, "Value", Tag.TAG_INT) || !MinecraftApi.containsTag(tag, "Intent", Tag.TAG_BYTE_ARRAY) || tag.getByteArray("Intent").orElse(new byte[0]).length > 32_768) throw new IllegalArgumentException("invalid service attempt data");
        try { var values = ITEMS.read(tag.getByteArray("Intent").orElse(new byte[0])); if (values.size() != 1) throw new IllegalArgumentException("expected one physical intent");
            var value = values.iterator().next(); if (value.phase() != PhysicalItemOperation.Phase.INTENT) throw new IllegalArgumentException("expected physical intent");
            return new ServiceProvision.Attempt(ServiceProvision.Purpose.valueOf(string(tag, "Purpose")), number(tag, "Sequence"), value.intent(), tag.getIntOr("Value", 0));
        } catch (IOException exception) { throw new IllegalArgumentException("invalid service intent envelope", exception); }
    }
    private static CompoundTag compound(CompoundTag tag, String key) { if (!(tag.get(key) instanceof CompoundTag value)) throw new IllegalArgumentException("missing service compound"); return value; }
    private static ListTag rows(CompoundTag tag, String key, int max) { if (!(tag.get(key) instanceof ListTag list) || list.size() > max || !list.isEmpty() && MinecraftApi.elementType(list) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid service rows"); return list; }
    protected void validate(Collection<ServiceProvision> values) {
        var people = new HashSet<UUID>(); var bodies = new HashSet<UUID>();
        for (var value : values) if (!value.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)) {
            if (!people.add(value.terms().person()) || !bodies.add(value.terms().body())) throw new IllegalArgumentException("duplicate ongoing service provision");
        }
    }
}
