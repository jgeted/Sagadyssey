package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.social.market.QualityGrade;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record GuildQualityStandard(UUID id, UUID institutionId, String craftId,
                                   ItemFingerprint product, Set<QualityGrade> acceptedGrades,
                                   long adoptedAt, boolean active, String description) {
    public GuildQualityStandard {
        if (id == null || institutionId == null || craftId == null || craftId.isBlank()
                || product == null || acceptedGrades == null || acceptedGrades.isEmpty()) {
            throw new IllegalArgumentException("invalid guild quality standard");
        }
        craftId = craftId.trim().toLowerCase(java.util.Locale.ROOT);
        acceptedGrades = Set.copyOf(acceptedGrades);
        description = description == null ? "" : description;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.putString("Craft", craftId);
        tag.put("Product", product.save());
        ListTag grades = new ListTag();
        acceptedGrades.forEach(grade -> {
            CompoundTag value = new CompoundTag();
            value.putString("Value", grade.name());
            grades.add(value);
        });
        tag.put("AcceptedGrades", grades);
        tag.putLong("AdoptedAt", adoptedAt);
        tag.putBoolean("Active", active);
        tag.putString("Description", description);
        return tag;
    }

    static GuildQualityStandard load(CompoundTag tag) {
        Set<QualityGrade> grades = new HashSet<>();
        ListTag values = tag.getList("AcceptedGrades", Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) {
            grades.add(QualityGrade.valueOf(values.getCompound(index).getString("Value")));
        }
        return new GuildQualityStandard(tag.getUUID("Id"), tag.getUUID("Institution"),
                tag.getString("Craft"), ItemFingerprint.load(tag.getCompound("Product")),
                grades, tag.getLong("AdoptedAt"), tag.getBoolean("Active"),
                tag.getString("Description"));
    }
}
