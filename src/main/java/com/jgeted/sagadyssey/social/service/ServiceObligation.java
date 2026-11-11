package com.jgeted.sagadyssey.social.service;

import net.minecraft.nbt.CompoundTag;

/** One explicit duty. Amount/unit may be zero/blank for behavioral duties. */
public record ServiceObligation(ObligationParty responsible, String kind, String description,
                                int amount, String unit, long dueAt, ObligationStatus status) {
    public ServiceObligation {
        if (responsible == null || kind == null || kind.isBlank() || status == null) {
            throw new IllegalArgumentException("invalid service obligation");
        }
        description = description == null ? "" : description;
        amount = Math.max(0, amount);
        unit = unit == null ? "" : unit;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Party", responsible.name());
        tag.putString("Kind", kind);
        tag.putString("Description", description);
        tag.putInt("Amount", amount);
        tag.putString("Unit", unit);
        tag.putLong("Due", dueAt);
        tag.putString("Status", status.name());
        return tag;
    }

    public static ServiceObligation load(CompoundTag tag) {
        return new ServiceObligation(ObligationParty.valueOf(tag.getStringOr("Party", "")),
                tag.getStringOr("Kind", ""), tag.getStringOr("Description", ""), tag.getIntOr("Amount", 0),
                tag.getStringOr("Unit", ""), tag.getLongOr("Due", 0L),
                ObligationStatus.valueOf(tag.getStringOr("Status", "")));
    }
}
