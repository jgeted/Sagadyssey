package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.*;

public final class WarJointOrderCodec extends NbtSnapshotCodec<WarJointOrder> {
    public WarJointOrderCodec() { super("sagadyssey:war_joint_order/v1", 4 * 1024 * 1024, 256); }

    @Override protected UUID identity(WarJointOrder plan) { return plan.id(); }

    @Override protected CompoundTag encode(WarJointOrder plan) {
        var tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, plan.id());
        tag.store("Organizer", UUIDUtil.CODEC, plan.organizer());
        tag.store("Partner", UUIDUtil.CODEC, plan.partner());
        tag.putLong("ExecuteAt", plan.executeAt());
        tag.putLong("ReportsUntil", plan.reportsUntil());
        tag.putLong("Revision", plan.revision());
        tag.putString("Phase", plan.phase().name());
        var legs = new ListTag();
        plan.instructions().forEach(leg -> legs.add(writeInstruction(leg)));
        tag.put("Instructions", legs);
        var approvals = new ListTag();
        for (var signer : plan.signatures().stream().sorted().toList()) {
            var row = new CompoundTag();
            row.store("Person", UUIDUtil.CODEC, signer);
            approvals.add(row);
        }
        tag.put("Signatures", approvals);
        var results = new ListTag();
        for (var result : plan.receipts()) {
            var row = new CompoundTag();
            row.store("Order", UUIDUtil.CODEC, result.order());
            row.putString("Outcome", result.outcome().name());
            row.putLong("DueAt", result.dueAt());
            row.putString("Reason", result.reason());
            results.add(row);
        }
        tag.put("Receipts", results);
        return tag;
    }

    @Override protected WarJointOrder decode(CompoundTag tag) {
        fields(tag, Set.of("Id", "Organizer", "Partner", "ExecuteAt", "ReportsUntil", "Revision",
                "Phase", "Instructions", "Signatures", "Receipts"));
        var legs = new ArrayList<WarJointOrder.Instruction>();
        for (var row : rows(tag, "Instructions", WarJointOrder.MAX_UNITS)) legs.add(readInstruction(row));
        var signatures = new HashSet<UUID>();
        for (var row : rows(tag, "Signatures", 2)) {
            fields(row, Set.of("Person"));
            if (!signatures.add(uuid(row, "Person"))) throw new IllegalArgumentException("repeated joint signature");
        }
        var receipts = new ArrayList<WarJointOrder.Receipt>();
        for (var row : rows(tag, "Receipts", WarJointOrder.MAX_UNITS)) {
            fields(row, Set.of("Order", "Outcome", "DueAt", "Reason"));
            receipts.add(new WarJointOrder.Receipt(uuid(row, "Order"), WarJointOrder.Outcome.valueOf(string(row, "Outcome")),
                    number(row, "DueAt"), string(row, "Reason")));
        }
        return new WarJointOrder(uuid(tag, "Id"), uuid(tag, "Organizer"), uuid(tag, "Partner"),
                number(tag, "ExecuteAt"), number(tag, "ReportsUntil"), legs, signatures,
                WarJointOrder.Phase.valueOf(string(tag, "Phase")), receipts, number(tag, "Revision"));
    }

    private static CompoundTag writeInstruction(WarJointOrder.Instruction leg) {
        var tag = new CompoundTag();
        var command = leg.command();
        var map = leg.map();
        tag.store("Order", UUIDUtil.CODEC, command.operationId());
        tag.store("Owner", UUIDUtil.CODEC, command.actorId());
        tag.store("Army", UUIDUtil.CODEC, command.armyId());
        tag.store("Unit", UUIDUtil.CODEC, command.unitId());
        tag.putLong("Generation", command.generation());
        tag.putLong("Revision", command.expectedRevision());
        tag.putString("Mode", command.intent().kind().toString());
        tag.putLong("Definition", command.intent().definitionVersion());
        tag.putLong("Height", command.intent().target().y());
        tag.putString("Dimension", map.dimension().toString());
        tag.putLong("Map", map.id());
        tag.putLong("CenterX", map.centerX());
        tag.putLong("CenterZ", map.centerZ());
        tag.putLong("Scale", map.scale());
        tag.putLong("PixelX", leg.x());
        tag.putLong("PixelZ", leg.z());
        tag.putLong("ShareReport", leg.shareReport() ? 1 : 0);
        return tag;
    }

    private static WarJointOrder.Instruction readInstruction(CompoundTag tag) {
        fields(tag, Set.of("Order", "Owner", "Army", "Unit", "Generation", "Revision", "Mode", "Definition",
                "Height", "Dimension", "Map", "CenterX", "CenterZ", "Scale", "PixelX", "PixelZ", "ShareReport"));
        var dim = WarKey.parse(string(tag, "Dimension"));
        var map = new WarMapSheet(integer(tag, "Map"), dim, integer(tag, "CenterX"), integer(tag, "CenterZ"), integer(tag, "Scale"));
        int x = integer(tag, "PixelX"), z = integer(tag, "PixelZ");
        var target = new WarIntent.Position(dim, map.x(x), integer(tag, "Height"), map.z(z));
        var intent = new WarIntent(WarKey.parse(string(tag, "Mode")), integer(tag, "Definition"), target);
        var command = new WarCommand.IssueOrder(uuid(tag, "Order"), number(tag, "Generation"), uuid(tag, "Owner"),
                uuid(tag, "Army"), uuid(tag, "Unit"), number(tag, "Revision"), intent);
        long share = number(tag, "ShareReport");
        if (share != 0 && share != 1) throw new IllegalArgumentException("invalid report consent");
        return new WarJointOrder.Instruction(command, map, x, z, share == 1);
    }

    private static int integer(CompoundTag tag, String name) {
        long value = number(tag, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid integer");
        return (int) value;
    }

    private static List<CompoundTag> rows(CompoundTag tag, String key, int maximum) {
        if (!(tag.get(key) instanceof ListTag list) || list.size() > maximum) throw new IllegalArgumentException("invalid plan rows");
        var rows = new ArrayList<CompoundTag>();
        for (var row : list) {
            if (!(row instanceof CompoundTag entry)) throw new IllegalArgumentException("invalid plan row");
            rows.add(entry);
        }
        return rows;
    }
}
