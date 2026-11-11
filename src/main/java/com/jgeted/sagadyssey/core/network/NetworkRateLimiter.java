package com.jgeted.sagadyssey.core.network;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** 主线程上的轻量逐玩家限流，防止修改客户端高频刷 GUI/状态数据包。 */
public final class NetworkRateLimiter {
    private static final Map<ServerPlayer, Map<String, Long>> LAST_TICK = new WeakHashMap<>();

    private NetworkRateLimiter() { }

    public static synchronized boolean allow(ServerPlayer player, String channel, int intervalTicks) {
        long now = player.level().getGameTime();
        Map<String, Long> channels = LAST_TICK.computeIfAbsent(player, ignored -> new HashMap<>());
        long last = channels.getOrDefault(channel, Long.MIN_VALUE / 2L);
        if (now - last < Math.max(1, intervalTicks)) return false;
        channels.put(channel, now);
        return true;
    }
}
