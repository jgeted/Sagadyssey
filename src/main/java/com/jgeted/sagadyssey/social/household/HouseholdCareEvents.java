package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcMemoryRecord;
import com.jgeted.sagadyssey.social.life.NpcNeedsRecord;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/** 每日评估真实家庭存粮和已加载成员需求，并记录长期供养压力。 */
public final class HouseholdCareEvents {
    private HouseholdCareEvents() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long day = server.overworld().getDayTime() / 24000L;
        HouseholdData households = HouseholdData.get(server);
        NpcLifeData lives = NpcLifeData.get(server);
        for (HouseholdRecord household : households.households()) {
            HouseholdCareRecord previous = households.care(household.id());
            if (previous != null && previous.lastAssessedDay() >= day) continue;
            assess(server, households, lives, household, previous, day);
        }
    }

    private static void assess(MinecraftServer server, HouseholdData households,
                               NpcLifeData lives, HouseholdRecord household,
                               HouseholdCareRecord previous, long day) {
        ServerLevel level = level(server, household.dimension());
        if (level == null) return;
        // 未加载的真实容器不能被当作空箱，否则远处家庭会被错误记为缺粮。
        if (household.sharedStorage() != null
                && !level.hasChunkAt(household.sharedStorage())) return;
        List<NpcBase> loaded = new ArrayList<>();
        int hungry = 0;
        int critical = 0;
        for (HouseholdMemberRecord member : household.members()) {
            if (!(level.getEntity(member.characterId()) instanceof NpcBase npc) || !npc.isAlive()) continue;
            loaded.add(npc);
            NpcNeedsRecord needs = lives.ensureNeeds(npc.getCharacterId(), level.getGameTime());
            if (needs.hunger() >= 350) hungry++;
            if (needs.hunger() >= 800) critical++;
        }
        if (loaded.isEmpty()) return;
        int food = pantryFood(level, household);
        String summary = critical > 0 && food == 0 ? "有严重饥饿成员且共享储物无食物"
                : hungry > 0 ? "有成员需要进食，共享储物可用食物=" + food
                : "当前已加载成员供养稳定";
        HouseholdCareRecord updated = households.assessCare(household.id(), day,
                food, hungry, critical, summary);
        if (updated.shortageDays() <= 0) {
            if (previous != null && previous.shortageDays() > 0) {
                lives.remember(household.headCharacterId(), new NpcMemoryRecord(
                        "household_food_recovered", null, household.name() + "结束了连续缺粮",
                        35, 40, level.getGameTime(), level.getGameTime() + 72_000L), level.getGameTime());
            }
            return;
        }
        for (NpcBase npc : loaded) {
            NpcNeedsRecord needs = lives.needs(npc.getCharacterId());
            if (needs == null || needs.hunger() < 800) continue;
            lives.remember(npc.getCharacterId(), new NpcMemoryRecord("household_food_shortage",
                    household.headCharacterId(), household.name() + "缺少可取用的家庭食物",
                    -55, Math.min(90, 45 + updated.shortageDays() * 8), level.getGameTime(),
                    level.getGameTime() + 96_000L), level.getGameTime());
            HouseholdRole role = household.roleOf(npc.getCharacterId());
            if (updated.shortageDays() % 3 == 0
                    && (role == HouseholdRole.CHILD || role == HouseholdRole.DEPENDENT)) {
                lives.adjustRelationship(npc.getCharacterId(), household.headCharacterId(),
                        NpcRelationKind.DEPENDENT, -4, "连续缺粮削弱对户主供养能力的信心");
            }
        }
        if (updated.shortageDays() == 3 || updated.shortageDays() % 5 == 0) {
            WorldHistoryData.get(server).append(level.getGameTime(), "household_food_shortage",
                    household.id(), household.name(), updated.shortageDays() + " days", 12);
        }
    }

    private static int pantryFood(ServerLevel level, HouseholdRecord household) {
        if (household.sharedStorage() == null || !level.hasChunkAt(household.sharedStorage())) return 0;
        Container container = WarehouseContainers.asContainer(
                level.getBlockEntity(household.sharedStorage()));
        return container == null ? 0 : NpcFoodItems.edibleItems(container);
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        return null;
    }
}
