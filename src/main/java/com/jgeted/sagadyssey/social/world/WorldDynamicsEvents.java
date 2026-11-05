package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.politics.PoliticalRelation;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.List;

/** 每日推进小型世界状态：政治事件改变关系，灾害改变真实供给或 NPC 状态。 */
public final class WorldDynamicsEvents {
    private WorldDynamicsEvents() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        WorldDynamicsData dynamics = WorldDynamicsData.get(server);
        long day = dynamics.simulationDay(server.overworld().getGameTime(),server.overworld().getDayTime());
        if (!dynamics.beginDay(day)) return;
        long started=System.nanoTime();
        dynamics.expire(server, day);
        applyActiveEffects(server, dynamics.activeEvents());
        if (dynamics.canRoll(day)) rollEvent(server, dynamics, day);
        com.jgeted.sagadyssey.worldsim.lod.LodMetrics.record(server,com.jgeted.sagadyssey.worldsim.lod.LodMetrics.Kind.WORLD,System.nanoTime()-started);
    }

    private static void rollEvent(MinecraftServer server, WorldDynamicsData dynamics, long day) {
        dynamics.markRolled(day);
        RandomSource random = RandomSource.create(server.overworld().getSeed() ^ (day * 341873128712L));
        if (random.nextFloat() > 0.42F) return;
        List<SettlementRecord> settlements = SettlementRegistryData.get(server).allSettlements();
        SettlementRecord target = settlements.isEmpty() ? null : settlements.get(random.nextInt(settlements.size()));
        PoliticalStateData politics = PoliticalStateData.get(server);
        DynamicWorldEventType type;
        if (politics.isOfficeVacant("sagadyssey:kingdom") && random.nextBoolean()) {
            type = DynamicWorldEventType.SUCCESSION_CRISIS;
        } else {
            DynamicWorldEventType[] pool = {DynamicWorldEventType.BORDER_RAID, DynamicWorldEventType.TRUCE,
                    DynamicWorldEventType.FAMINE, DynamicWorldEventType.PLAGUE, DynamicWorldEventType.MARKET_FAIR};
            type = pool[random.nextInt(pool.length)];
        }
        startEvent(server, dynamics, type, target, day, 1 + random.nextInt(3));
    }

    @Nullable
    public static DynamicWorldEventRecord startEvent(MinecraftServer server, WorldDynamicsData dynamics,
                                                     DynamicWorldEventType type,
                                                     @Nullable SettlementRecord target,
                                                     long day, int severity) {
        int duration = switch (type) {
            case BORDER_RAID, TRUCE -> 2;
            case FAMINE, PLAGUE, SUCCESSION_CRISIS -> 3;
            case MARKET_FAIR -> 1;
        };
        String faction = target == null ? "sagadyssey:kingdom" : target.factionId();
        DynamicWorldEventRecord record = dynamics.start(server, type,
                target == null ? null : target.id(), faction, day, duration, severity);
        if (record == null) return null;
        PoliticalStateData politics = PoliticalStateData.get(server);
        if (type == DynamicWorldEventType.BORDER_RAID) {
            politics.setRelation(server, "sagadyssey:kingdom", "sagadyssey:bandit",
                    PoliticalRelation.WAR, server.overworld().getGameTime());
        } else if (type == DynamicWorldEventType.TRUCE) {
            politics.setRelation(server, "sagadyssey:kingdom", "sagadyssey:bandit",
                    PoliticalRelation.TENSE, server.overworld().getGameTime());
        } else if (type == DynamicWorldEventType.SUCCESSION_CRISIS) {
            politics.setRelation(server, "sagadyssey:kingdom", "sagadyssey:church",
                    PoliticalRelation.TENSE, server.overworld().getGameTime());
        }
        String place = target == null ? "整个世界" : target.name();
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6[世界事件] §f"
                + type.displayName() + " §7发生于 " + place + "，预计持续 " + duration + " 天"), false);
        applyActiveEffects(server, List.of(record));
        return record;
    }

    private static void applyActiveEffects(MinecraftServer server, List<DynamicWorldEventRecord> events) {
        for (DynamicWorldEventRecord event : events) {
            if (event.settlementId() == null) continue;
            SettlementRecord settlement = SettlementRegistryData.get(server).byId(event.settlementId());
            if (settlement == null) continue;
            ServerLevel level = level(server, settlement.dimension());
            if (level == null || !level.hasChunkAt(settlement.center())) continue;
            int radius = settlement.radius();
            for (NpcBase npc : level.getEntitiesOfClass(NpcBase.class,
                    new AABB(settlement.center()).inflate(radius), NpcBase::isAlive)) {
                switch (event.type()) {
                    case PLAGUE -> {
                        npc.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6000, 0));
                        npc.adjustMoral(-4 * event.severity());
                    }
                    case FAMINE, SUCCESSION_CRISIS, BORDER_RAID -> npc.adjustMoral(-2 * event.severity());
                    case MARKET_FAIR -> npc.adjustMoral(4 + event.severity());
                    case TRUCE -> npc.adjustMoral(2);
                }
            }
        }
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
