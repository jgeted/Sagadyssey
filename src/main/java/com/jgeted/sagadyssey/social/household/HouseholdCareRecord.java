package com.jgeted.sagadyssey.social.household;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 家庭供养的每日快照与长期照料记录。 */
public record HouseholdCareRecord(
        UUID householdId,
        long lastAssessedDay,
        int pantryFoodItems,
        int hungryMembers,
        int criticalMembers,
        int shortageDays,
        int totalContributions,
        @Nullable UUID lastContributor,
        long lastContributionAt,
        String summary) {

    public HouseholdCareRecord {
        pantryFoodItems = Math.max(0, pantryFoodItems);
        hungryMembers = Math.max(0, hungryMembers);
        criticalMembers = Math.max(0, criticalMembers);
        shortageDays = Math.max(0, shortageDays);
        totalContributions = Math.max(0, totalContributions);
        summary = summary == null ? "尚未评估" : summary;
    }

    public static HouseholdCareRecord create(UUID householdId) {
        return new HouseholdCareRecord(householdId, -1L, 0, 0, 0,
                0, 0, null, -1L, "尚未评估");
    }

    public HouseholdCareRecord assessed(long day, int food, int hungry, int critical,
                                         int consecutiveShortage, String result) {
        return new HouseholdCareRecord(householdId, day, food, hungry, critical,
                consecutiveShortage, totalContributions, lastContributor,
                lastContributionAt, result);
    }

    public HouseholdCareRecord contributed(UUID contributor, long gameTime) {
        return new HouseholdCareRecord(householdId, lastAssessedDay, pantryFoodItems + 1,
                hungryMembers, criticalMembers, shortageDays, totalContributions + 1,
                contributor, gameTime, "家庭成员主动补充了一份食物");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Household", UUIDUtil.CODEC, householdId);
        tag.putLong("LastAssessedDay", lastAssessedDay);
        tag.putInt("PantryFoodItems", pantryFoodItems);
        tag.putInt("HungryMembers", hungryMembers);
        tag.putInt("CriticalMembers", criticalMembers);
        tag.putInt("ShortageDays", shortageDays);
        tag.putInt("TotalContributions", totalContributions);
        if (lastContributor != null) tag.store("LastContributor", UUIDUtil.CODEC, lastContributor);
        tag.putLong("LastContributionAt", lastContributionAt);
        tag.putString("Summary", summary);
        return tag;
    }

    public static HouseholdCareRecord load(CompoundTag tag) {
        return new HouseholdCareRecord(tag.read("Household", UUIDUtil.CODEC).orElseThrow(),
                tag.getLongOr("LastAssessedDay", 0L), tag.getIntOr("PantryFoodItems", 0),
                tag.getIntOr("HungryMembers", 0), tag.getIntOr("CriticalMembers", 0),
                tag.getIntOr("ShortageDays", 0), tag.getIntOr("TotalContributions", 0),
                tag.read("LastContributor", UUIDUtil.CODEC).isPresent() ? tag.read("LastContributor", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("LastContributionAt", 0L), tag.getStringOr("Summary", ""));
    }
}
