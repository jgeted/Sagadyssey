package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarLeadershipRecord;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class WarLeadershipCodec extends NbtSnapshotCodec<WarLeadershipRecord> {
    public WarLeadershipCodec() { super("sagadyssey:war_leadership/v1", 8 * 1024 * 1024, 8_192); }

    @Override protected UUID identity(WarLeadershipRecord value) { return value.order(); }

    @Override protected CompoundTag encode(WarLeadershipRecord value) {
        var tag = new CompoundTag();
        tag.store("Order", UUIDUtil.CODEC, value.order());
        tag.store("Army", UUIDUtil.CODEC, value.army());
        tag.store("Unit", UUIDUtil.CODEC, value.unit());
        tag.store("Appointment", UUIDUtil.CODEC, value.appointment());
        tag.store("Officer", UUIDUtil.CODEC, value.officer());
        tag.putString("Specialty", value.specialty().name());
        tag.putLong("ReceivedAt", value.receivedAt());
        tag.putLong("ObservedAt", value.observedAt());
        tag.putInt("Strength", value.strength());
        tag.putInt("Arrived", value.arrived());
        tag.putString("Contact", value.contact().map(UUID::toString).orElse(""));
        return tag;
    }

    @Override protected WarLeadershipRecord decode(CompoundTag tag) {
        fields(tag, Set.of("Order", "Army", "Unit", "Appointment", "Officer", "Specialty", "ReceivedAt", "ObservedAt", "Strength", "Arrived", "Contact"));
        String contact = string(tag, "Contact");
        return new WarLeadershipRecord(uuid(tag, "Order"), uuid(tag, "Army"), uuid(tag, "Unit"), uuid(tag, "Appointment"), uuid(tag, "Officer"),
                WarLeadershipRecord.Specialty.valueOf(string(tag, "Specialty")), number(tag, "ReceivedAt"), number(tag, "ObservedAt"),
                tag.getInt("Strength").orElseThrow(), tag.getInt("Arrived").orElseThrow(),
                contact.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(contact)));
    }
}
