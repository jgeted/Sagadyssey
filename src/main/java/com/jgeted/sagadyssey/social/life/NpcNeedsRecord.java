package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** NPC 的持续生存需求；数值越高表示需求越迫切。 */
public record NpcNeedsRecord(
        UUID npcId,
        int hunger,
        long lastAteAt,
        String lastFoodSource,
        long lastUpdatedAt,
        long lastAccruedAt) {

    public NpcNeedsRecord(UUID npcId, int hunger, long lastAteAt, String lastFoodSource, long lastUpdatedAt) {
        this(npcId, hunger, lastAteAt, lastFoodSource, lastUpdatedAt, Math.max(0, lastUpdatedAt));
    }

    public static final int MAX_HUNGER = 1000;
    public static final int DEFAULT_HUNGER = 120;

    public NpcNeedsRecord {
        hunger = clamp(hunger);
        lastFoodSource = lastFoodSource == null ? "尚未进食" : lastFoodSource;
        if (lastAccruedAt < 0) throw new IllegalArgumentException("negative hunger accrual time");
    }

    public static NpcNeedsRecord create(UUID npcId, long gameTime) {
        return new NpcNeedsRecord(npcId, DEFAULT_HUNGER, -1L,
                "尚未进食", gameTime);
    }

    public NpcNeedsRecord withHunger(int value, long gameTime) {
        return new NpcNeedsRecord(npcId, value, lastAteAt, lastFoodSource, gameTime, lastAccruedAt);
    }

    public NpcNeedsRecord accrued(int value, long accruedThrough, long observedAt) {
        if (accruedThrough < lastAccruedAt || observedAt < accruedThrough) throw new IllegalArgumentException("hunger accrual reversed");
        return new NpcNeedsRecord(npcId, value, lastAteAt, lastFoodSource, observedAt, accruedThrough);
    }

    public NpcNeedsRecord fed(int restored, String source, long gameTime) {
        return new NpcNeedsRecord(npcId, hunger - Math.max(0, restored), gameTime,
                source == null || source.isBlank() ? "未知食物来源" : source, gameTime, lastAccruedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Npc", UUIDUtil.CODEC, npcId);
        tag.putInt("Hunger", hunger);
        tag.putLong("LastAte", lastAteAt);
        tag.putString("LastFoodSource", lastFoodSource);
        tag.putLong("LastUpdated", lastUpdatedAt);
        tag.putLong("LastAccrued", lastAccruedAt);
        return tag;
    }

    public static NpcNeedsRecord load(CompoundTag tag) {
        UUID npcId = tag.read("Npc", UUIDUtil.CODEC).orElseThrow();
        return new NpcNeedsRecord(npcId, tag.getIntOr("Hunger", 0), tag.getLongOr("LastAte", 0L),
                tag.getStringOr("LastFoodSource", ""), tag.getLongOr("LastUpdated", 0L), MinecraftApi.containsTag(tag, "LastAccrued", net.minecraft.nbt.Tag.TAG_LONG)
                        ? tag.getLongOr("LastAccrued", 0L) : Math.max(0, tag.getLongOr("LastUpdated", 0L)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_HUNGER, value));
    }
}
