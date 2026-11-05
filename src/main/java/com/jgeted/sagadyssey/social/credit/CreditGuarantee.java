package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

/** A third party's capped promise; it is not cash and creates no items. */
public record CreditGuarantee(SubjectRef guarantor, int maximumEmeralds, String basis) {
    public CreditGuarantee {
        if (guarantor == null || maximumEmeralds <= 0) throw new IllegalArgumentException("invalid guarantee");
        basis = basis == null ? "" : basis;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Guarantor", guarantor.save());
        tag.putInt("Maximum", maximumEmeralds);
        tag.putString("Basis", basis);
        return tag;
    }

    static CreditGuarantee load(CompoundTag tag) {
        return new CreditGuarantee(SubjectRef.load(tag.getCompound("Guarantor")),
                tag.getInt("Maximum"), tag.getString("Basis"));
    }
}
