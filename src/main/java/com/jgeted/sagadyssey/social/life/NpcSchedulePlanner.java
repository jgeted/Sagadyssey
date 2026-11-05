package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.server.level.ServerLevel;

/** 将排班、明确命令和紧急需求归并为一个新任务准入状态。 */
public final class NpcSchedulePlanner {
    private static final long REVIEW_INTERVAL = 200L;

    private NpcSchedulePlanner() { }

    public static NpcScheduleRecord current(NpcBase npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return new NpcScheduleRecord(npc.getCharacterId(), NpcScheduleActivity.PERSONAL,
                    "客户端不决定日程", 0L, 0L);
        }
        NpcLifeData data = NpcLifeData.get(level.getServer());
        NpcScheduleRecord existing = data.schedule(npc.getCharacterId());
        if (existing == null || level.getGameTime() >= existing.reviewAt()) {
            refresh(npc, data, level);
            existing = data.schedule(npc.getCharacterId());
        }
        return existing == null ? new NpcScheduleRecord(npc.getCharacterId(),
                NpcScheduleActivity.PERSONAL, "缺少生活档案，保持兼容行为",
                level.getGameTime(), level.getGameTime() + REVIEW_INTERVAL) : existing;
    }

    public static void refresh(NpcBase npc, NpcLifeData data, ServerLevel level) {
        NpcLifeRecord life = data.record(npc.getCharacterId());
        if (life == null) return;
        NpcNeedsRecord needs = data.ensureNeeds(npc.getCharacterId(), level.getGameTime());
        NpcScheduleActivity activity;
        String reason;
        if (npc.getTarget() != null) {
            activity = NpcScheduleActivity.COMBAT;
            reason = "存在当前战斗目标，紧急状态覆盖日常排班";
        } else if (needs.hunger() >= 800) {
            activity = NpcScheduleActivity.URGENT_NEEDS;
            reason = "严重饥饿=" + needs.hunger() + "/1000";
        } else if (npc.getCommand() == NpcCommand.FOLLOW) {
            activity = NpcScheduleActivity.FOLLOW;
            reason = "玩家明确要求跟随";
        } else if (npc.getCommand() == NpcCommand.STAY) {
            activity = NpcScheduleActivity.STAY;
            reason = "玩家明确要求驻守";
        } else if (NpcLifeBehavior.isRestWindow(life.shift(), level.getDayTime())) {
            activity = NpcScheduleActivity.REST;
            reason = life.shift().name() + " 排班的休息窗口";
        } else if (isWorkWindow(life.shift(), level.getDayTime())
                && npc.getCommand() == NpcCommand.WORK) {
            activity = NpcScheduleActivity.WORK;
            reason = life.shift().name() + " 排班工作窗口";
        } else if (isWorkWindow(life.shift(), level.getDayTime())
                && npc.getCommand() == NpcCommand.PATROL) {
            activity = NpcScheduleActivity.DUTY;
            reason = life.shift().name() + " 排班执勤窗口";
        } else {
            activity = NpcScheduleActivity.PERSONAL;
            reason = "不在工作或睡眠窗口，允许进食、家庭照料和自由活动";
        }
        data.updateSchedule(npc.getCharacterId(), activity, reason, level.getGameTime(),
                level.getGameTime() + REVIEW_INTERVAL);
    }

    public static boolean allowsWork(NpcBase npc) {
        NpcLifeData data = NpcLifeData.from(npc);
        if (data == null || data.record(npc.getCharacterId()) == null) return true;
        NpcScheduleActivity activity = current(npc).activity();
        return activity == NpcScheduleActivity.WORK || activity == NpcScheduleActivity.COMBAT;
    }

    public static boolean allowsPatrol(NpcBase npc) {
        NpcLifeData data = NpcLifeData.from(npc);
        if (data == null || data.record(npc.getCharacterId()) == null) return true;
        NpcScheduleActivity activity = current(npc).activity();
        return activity == NpcScheduleActivity.DUTY || activity == NpcScheduleActivity.COMBAT;
    }

    public static boolean allowsRest(NpcBase npc) {
        return current(npc).activity() == NpcScheduleActivity.REST;
    }

    public static boolean allowsStay(NpcBase npc) {
        NpcLifeData data = NpcLifeData.from(npc);
        if (data == null || data.record(npc.getCharacterId()) == null) return true;
        return current(npc).activity() == NpcScheduleActivity.STAY;
    }

    public static boolean allowsCare(NpcBase npc, int recipientHunger) {
        NpcScheduleActivity activity = current(npc).activity();
        return recipientHunger >= 900 || activity == NpcScheduleActivity.PERSONAL;
    }

    public static boolean isWorkWindow(NpcWorkShift shift, long dayTime) {
        long time = Math.floorMod(dayTime, 24000L);
        return switch (shift) {
            case DAY -> time <= 12000L;
            case NIGHT -> time >= 11500L || time <= 500L;
            case FLEXIBLE -> time <= 13500L;
        };
    }
}
