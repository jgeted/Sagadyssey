package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;

/** 低频维护生活状态；需求会积累和满足，不是每次行动都加固定分。 */
public final class NpcLifeBehavior {
    private NpcLifeBehavior() { }

    public static void tick(NpcBase npc) {
        if (!(npc.level() instanceof ServerLevel level) || npc.tickCount % 200 != 0) return;
        if(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.controls(level.getServer(),npc.getCharacterId()))return;
        NpcLifeData data = NpcLifeData.get(level.getServer());
        NpcLifeRecord life = data.record(npc.getCharacterId());
        if (life == null) return;
        int hungerGain = 2;
        if (npc.getTarget() != null) hungerGain += 2;
        if (npc.getNavigation().isInProgress()) hungerGain += 1;
        data.increaseHunger(npc.getCharacterId(), hungerGain, level.getGameTime());
        NpcNeedsRecord needs = data.needs(npc.getCharacterId());
        if (needs != null && needs.hunger() >= 900 && npc.tickCount % 1200 == 0) {
            npc.adjustMoral(-2);
        }
        NpcSchedulePlanner.refresh(npc, data, level);

        boolean brokenBed = life.bedPosition() == null || level.hasChunkAt(life.bedPosition())
                && !level.getBlockState(life.bedPosition()).is(BlockTags.BEDS);
        String storageIssue = storageIssue(npc, life, level);
        boolean brokenStorage = storageIssue != null;
        if ((brokenBed || brokenStorage) && level.getGameTime() >= life.decisionLockedUntil()) {
            data.updateDecision(npc.getCharacterId(), "报告住宅设施失效",
                    (brokenBed ? "私人床缺失" : "")
                            + (brokenBed && brokenStorage ? "；" : "")
                            + (brokenStorage ? storageIssue : "")
                            + "；等待原设施恢复或正式配置，不改动现有物品与义务",
                    level.getGameTime(), level.getGameTime() + 1200L, life.fatigue());
            life = data.record(npc.getCharacterId());
        }

        int fatigue = life.fatigue();
        if (npc.isSleeping()) {
            fatigue -= 90;
        } else {
            fatigue += isRestWindow(life.shift(), level.getDayTime()) ? 18 : 5;
            if (npc.getTarget() != null) fatigue += 12;
            if (npc.getNavigation().isInProgress()) fatigue += 3;
        }
        data.updateFatigue(npc.getCharacterId(), Math.max(0, Math.min(1000, fatigue)));
    }

    /** Missing unloaded blocks are unknown; a courier can use its existing legal household pantry. */
    private static String storageIssue(NpcBase npc, NpcLifeRecord life, ServerLevel level) {
        String dimension = level.dimension().location().toString();
        if (life.privateStoragePosition() != null) {
            if (!dimension.equals(life.dimension())) return "私人储物位于原维度，等待返回或正式迁居配置";
            if (!level.hasChunkAt(life.privateStoragePosition())) return null;
            return WarehouseContainers.asContainer(level.getBlockEntity(life.privateStoragePosition())) == null
                    ? "已登记私人储物不可用" : null;
        }
        if (npc.getProfession() != NpcProfession.COURIER) return "私人储物尚未配置";
        var home = HouseholdData.get(level.getServer()).forMember(npc.getCharacterId());
        if (home == null || !home.contains(npc.getCharacterId()) || home.sharedStorage() == null)
            return "运输员的家庭粮仓尚未关联";
        if (!dimension.equals(home.dimension())) return "家庭粮仓位于其他维度，等待返回或正式迁居配置";
        if (!level.hasChunkAt(home.sharedStorage())) return null;
        if (WarehouseContainers.asContainer(level.getBlockEntity(home.sharedStorage())) == null)
            return "已登记家庭粮仓不可用";
        var id = ContainerIds.block("household_storage", home.dimension(), home.sharedStorage());
        var context = RightsStateData.get(level.getServer()).context(id);
        var household = SubjectRef.household(home.id());
        // LifeTaskExecutor consumes as this household only after checking the same membership
        // and pantry association. An empty pantry remains a valid facility; no grants are added here.
        if (context == null || context.accessPolicy() != AccessPolicy.HOUSEHOLD
                || !household.equals(context.defaultBeneficialOwner())
                || !household.equals(context.custodian())) return "家庭粮仓权属关联不符，暂不能作为共享取食设施";
        return null;
    }

    public static boolean isRestWindow(NpcWorkShift shift, long dayTime) {
        long time = Math.floorMod(dayTime, 24000L);
        return switch (shift) {
            case DAY -> time >= 12500L && time <= 23000L;
            case NIGHT -> time >= 1000L && time <= 7000L;
            case FLEXIBLE -> time >= 14000L && time <= 22500L;
        };
    }
}
