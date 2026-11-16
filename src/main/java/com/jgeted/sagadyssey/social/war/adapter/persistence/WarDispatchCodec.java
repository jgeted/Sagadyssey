package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import net.minecraft.nbt.*;
import java.util.*;

/** 军令传递独立版本；不修改已冻结的M1编组格式。 */
public final class WarDispatchCodec extends NbtSnapshotCodec<WarTransmission> {
    public WarDispatchCodec() { super("sagadyssey:war_dispatch/v1", 16 * 1024 * 1024, 4096); }
    protected UUID identity(WarTransmission value) { return value.command().operationId(); }
    protected CompoundTag encode(WarTransmission value) {
        var tag = new CompoundTag(); var c = value.command(); tag.store("Id", UUIDUtil.CODEC, c.operationId()); tag.putLong("Generation", c.generation());
        tag.store("Actor", UUIDUtil.CODEC, c.actorId()); tag.store("Army", UUIDUtil.CODEC, c.armyId()); tag.store("Unit", UUIDUtil.CODEC, c.unitId()); tag.putLong("ExpectedRevision", c.expectedRevision());
        tag.put("Intent", intent(c.intent())); tag.putLong("Sequence", value.sequence());
        var route = value.route(); var r = new CompoundTag(); r.put("Origin", position(route.origin())); r.put("Report", position(route.reportedPosition()));
        r.putLong("ReportedAt", route.reportedAt()); r.putLong("SentAt", route.sentAt()); r.putLong("Delay", route.delayTicks()); r.putString("Channel", route.channel().name()); tag.put("Route", r);
        var members = new ListTag(); for (var member : value.members()) { var row = new CompoundTag(); row.store("Contract", UUIDUtil.CODEC, member.contract()); row.store("Character", UUIDUtil.CODEC, member.character()); row.store("Body", UUIDUtil.CODEC, member.body()); members.add(row); } tag.put("Members", members);
        value.received().ifPresent(order -> { var row = new CompoundTag(); row.putLong("Issued", order.issuedAtTick()); row.putLong("Delivered", order.deliveredAtTick()); row.putLong("Revision", order.deliveryRevision()); tag.put("Received", row); });
        return tag;
    }
    protected WarTransmission decode(CompoundTag tag) {
        var expected = new HashSet<>(Set.of("Id", "Generation", "Actor", "Army", "Unit", "ExpectedRevision", "Intent", "Sequence", "Route", "Members")); if (tag.contains("Received")) expected.add("Received"); fields(tag, expected);
        var c = new WarCommand.IssueOrder(uuid(tag, "Id"), number(tag, "Generation"), uuid(tag, "Actor"), uuid(tag, "Army"), uuid(tag, "Unit"), number(tag, "ExpectedRevision"), intent(compound(tag, "Intent")));
        long sequence = number(tag, "Sequence"); var r = compound(tag, "Route"); fields(r, Set.of("Origin", "Report", "ReportedAt", "SentAt", "Delay", "Channel"));
        var route = new WarTransmission.Route(position(compound(r, "Origin")), position(compound(r, "Report")), number(r, "ReportedAt"), number(r, "SentAt"), number(r, "Delay"), WarTransmission.Channel.valueOf(string(r, "Channel")));
        if (!(tag.get("Members") instanceof ListTag rows) || rows.isEmpty() || rows.size() > 128 || MinecraftApi.elementType(rows) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid dispatch members");
        var members = new ArrayList<WarTransmission.Member>(); for (Tag raw : rows) { var row = (CompoundTag) raw; fields(row, Set.of("Contract", "Character", "Body")); members.add(new WarTransmission.Member(uuid(row, "Contract"), uuid(row, "Character"), uuid(row, "Body"))); }
        Optional<WarUnit.Order> received = Optional.empty(); if (tag.contains("Received")) {
            var row = compound(tag, "Received"); fields(row, Set.of("Issued", "Delivered", "Revision"));
            received = Optional.of(new WarUnit.Order(c.operationId(), c.generation(), c.actorId(), sequence, c.intent(), number(row, "Issued"), number(row, "Delivered"), number(row, "Revision")));
        }
        return new WarTransmission(c, sequence, route, members, received);
    }
    private static CompoundTag intent(WarIntent value) { var tag = new CompoundTag(); tag.putString("Kind", value.kind().toString()); tag.putInt("Version", value.definitionVersion()); tag.put("Target", position(value.target())); return tag; }
    private static WarIntent intent(CompoundTag tag) { fields(tag, Set.of("Kind", "Version", "Target")); return new WarIntent(WarKey.parse(string(tag, "Kind")), integer(tag, "Version"), position(compound(tag, "Target"))); }
    private static CompoundTag position(WarIntent.Position value) { var tag = new CompoundTag(); tag.putString("Dimension", value.dimension().toString()); tag.putInt("X", value.x()); tag.putInt("Y", value.y()); tag.putInt("Z", value.z()); return tag; }
    private static WarIntent.Position position(CompoundTag tag) { fields(tag, Set.of("Dimension", "X", "Y", "Z")); return new WarIntent.Position(WarKey.parse(string(tag, "Dimension")), integer(tag, "X"), integer(tag, "Y"), integer(tag, "Z")); }
    private static int integer(CompoundTag tag, String key) { if (!MinecraftApi.containsTag(tag, key, Tag.TAG_INT)) throw new IllegalArgumentException("missing dispatch integer"); return tag.getIntOr(key, 0); }
    private static CompoundTag compound(CompoundTag tag, String key) { if (!(tag.get(key) instanceof CompoundTag value)) throw new IllegalArgumentException("missing dispatch compound"); return value; }
    protected void validate(Collection<WarTransmission> values) {
        var sequences = new HashSet<String>(); for (var value : values) if (!sequences.add(value.command().unitId() + ":" + value.sequence())) throw new IllegalArgumentException("duplicate unit transmission sequence");
    }
}
