package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.war.domain.WarBodyCheckpoint;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.*;
import net.minecraft.nbt.*;
import java.util.*;

/** 维持M3-a原身体字段与编码；原版NBT接口的版本差异仅在适配层处理。 */
public final class WarBodyCheckpointCodec {
    public static final String KEY = "SagadysseyFieldControlV1";
    private static final Set<String> FIELDS = Set.of("Contract", "Unit", "Order", "Sequence", "NextAttack");
    private static final CheckpointRead ABSENT = new CheckpointRead(ReadStatus.ABSENT, Optional.empty());
    private static final CheckpointRead INVALID = new CheckpointRead(ReadStatus.INVALID, Optional.empty());

    public CheckpointRead read(CompoundTag owner) {
        var entry = owner.get(KEY);
        if (entry == null) return ABSENT;
        if (!(entry instanceof CompoundTag saved) || !saved.keySet().equals(FIELDS)
                || !MinecraftApi.containsTag(saved, "Sequence", Tag.TAG_LONG)
                || !MinecraftApi.containsTag(saved, "NextAttack", Tag.TAG_LONG)) return INVALID;
        UUID contract = readUuid(saved, "Contract");
        UUID unit = readUuid(saved, "Unit");
        UUID order = readUuid(saved, "Order");
        if (contract == null || unit == null || order == null) return INVALID;
        try {
            return new CheckpointRead(ReadStatus.PRESENT, Optional.of(new WarBodyCheckpoint(contract, unit, order,
                    saved.getLongOr("Sequence", 0L), saved.getLongOr("NextAttack", 0L))));
        } catch (IllegalArgumentException exception) { return INVALID; }
    }

    private static UUID readUuid(CompoundTag tag, String field) {
        if (tag.get(field) instanceof IntArrayTag array) {
            var ids = array.getAsIntArray();
            return ids.length == 4 ? UUIDUtil.uuidFromIntArray(ids) : null;
        }
        return tag.read(field, UUIDUtil.CODEC).orElse(null);
    }

    public void store(CompoundTag owner, WarBodyCheckpoint value) {
        var tag = new CompoundTag(); tag.store("Contract", UUIDUtil.CODEC, value.contract()); tag.store("Unit", UUIDUtil.CODEC, value.unit()); tag.store("Order", UUIDUtil.CODEC, value.order());
        tag.putLong("Sequence", value.sequence()); tag.putLong("NextAttack", value.nextAttack()); owner.put(KEY, tag);
    }
}
