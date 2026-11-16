package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.*;
import java.util.*;

/** 与hunger同档保存的营养效果回执；实物消费凭据仍由共享物资域拥有。 */
public record NpcMealReceipt(UUID operationId, UUID person, int restored, String source, long consumedAt, long appliedAt) {
    public NpcMealReceipt {
        Objects.requireNonNull(operationId); Objects.requireNonNull(person);
        if (restored < 1 || restored > 1000 || source == null || source.isBlank() || source.length() > 256 || consumedAt < 0 || appliedAt < consumedAt) throw new IllegalArgumentException("invalid meal receipt");
    }
    public boolean sameEffect(UUID character, int nutrition, String origin, long consumed) { return person.equals(character) && restored == nutrition && source.equals(origin) && consumedAt == consumed; }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.store("Id", UUIDUtil.CODEC, operationId); tag.store("Person", UUIDUtil.CODEC, person); tag.putInt("Restored", restored); tag.putString("Source", source); tag.putLong("Consumed", consumedAt); tag.putLong("Applied", appliedAt); return tag;
    }
    public static NpcMealReceipt load(CompoundTag tag) {
        if (!tag.keySet().equals(Set.of("Id", "Person", "Restored", "Source", "Consumed", "Applied")) || !tag.read("Id", UUIDUtil.CODEC).isPresent() || !tag.read("Person", UUIDUtil.CODEC).isPresent()
                || !MinecraftApi.containsTag(tag, "Restored", Tag.TAG_INT) || !MinecraftApi.containsTag(tag, "Source", Tag.TAG_STRING) || !MinecraftApi.containsTag(tag, "Consumed", Tag.TAG_LONG) || !MinecraftApi.containsTag(tag, "Applied", Tag.TAG_LONG)) throw new IllegalArgumentException("invalid meal receipt fields");
        return new NpcMealReceipt(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Person", UUIDUtil.CODEC).orElseThrow(), tag.getIntOr("Restored", 0), tag.getStringOr("Source", ""), tag.getLongOr("Consumed", 0L), tag.getLongOr("Applied", 0L));
    }
}
