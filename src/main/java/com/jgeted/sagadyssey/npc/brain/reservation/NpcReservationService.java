package com.jgeted.sagadyssey.npc.brain.reservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理工作站、目标方块、交谈对象和座位等互斥资源。
 *
 * <p>物品数量仍由权利与生产账本预留，本服务不会复制物权逻辑。</p>
 */
public final class NpcReservationService {
    private static final Comparator<NpcReservation> AUDIT_ORDER = Comparator
            .comparing(NpcReservation::key)
            .thenComparing(value -> value.planId().toString());

    private final Map<NpcReservationKey, NpcReservation> reservations = new LinkedHashMap<>();

    public synchronized NpcReservationAcquireResult acquire(NpcReservationKey key,
                                                            UUID planId, UUID npcId,
                                                            long now, long expiresAt) {
        if (key == null || planId == null || npcId == null) {
            throw new IllegalArgumentException("reservation identity is required");
        }
        if (now < 0L || expiresAt <= now) {
            throw new IllegalArgumentException("reservation expiry must follow acquisition");
        }
        NpcReservation held = reservations.get(key);
        if (held != null && held.expiredAt(now)) {
            reservations.remove(key);
            held = null;
        }
        if (held != null) {
            if (held.planId().equals(planId)) {
                if (!held.npcId().equals(npcId)) {
                    throw new IllegalStateException("the same plan cannot belong to two NPCs");
                }
                return new NpcReservationAcquireResult(
                        NpcReservationAcquireResult.Status.ALREADY_HELD, held);
            }
            return new NpcReservationAcquireResult(
                    NpcReservationAcquireResult.Status.CONFLICT, held);
        }
        NpcReservation acquired = new NpcReservation(key, planId, npcId, now, expiresAt);
        reservations.put(key, acquired);
        return new NpcReservationAcquireResult(
                NpcReservationAcquireResult.Status.ACQUIRED, acquired);
    }

    public synchronized Optional<NpcReservation> find(NpcReservationKey key, long now) {
        if (key == null) throw new IllegalArgumentException("key is required");
        if (now < 0L) throw new IllegalArgumentException("now cannot be negative");
        return Optional.ofNullable(validReservation(key, now));
    }

    /**
     * 严格检查指定计划与 NPC 是否仍共同持有资源。
     *
     * <p>只匹配计划 ID 不够：同一计划 ID 不能被另一个 NPC 借用。检查时会先清除
     * 边界 tick 已过期的记录。</p>
     */
    public synchronized boolean holds(NpcReservationKey key, UUID planId, UUID npcId,
                                      long now) {
        requireIdentity(key, planId, npcId);
        if (now < 0L) throw new IllegalArgumentException("now cannot be negative");
        NpcReservation held = validReservation(key, now);
        return held != null && held.planId().equals(planId) && held.npcId().equals(npcId);
    }

    /**
     * 仅允许当前同一计划与 NPC 延长租期。
     *
     * <p>不存在、已过期或属于其他持有人的记录返回空；续租保持原始取得时间，
     * 且不能把有效期缩短。相同到期时间视为幂等成功。</p>
     */
    public synchronized Optional<NpcReservation> renew(NpcReservationKey key,
                                                       UUID planId, UUID npcId,
                                                       long now, long newExpiresAt) {
        requireIdentity(key, planId, npcId);
        if (now < 0L || newExpiresAt <= now) {
            throw new IllegalArgumentException("reservation expiry must follow renewal");
        }
        NpcReservation held = validReservation(key, now);
        if (held == null || !held.planId().equals(planId) || !held.npcId().equals(npcId)) {
            return Optional.empty();
        }
        if (newExpiresAt < held.expiresAt()) {
            throw new IllegalArgumentException("renewal cannot shorten reservation expiry");
        }
        if (newExpiresAt == held.expiresAt()) return Optional.of(held);

        NpcReservation renewed = new NpcReservation(key, planId, npcId,
                held.acquiredAt(), newExpiresAt);
        reservations.put(key, renewed);
        return Optional.of(renewed);
    }

    public synchronized List<NpcReservation> reservationsForPlan(UUID planId) {
        if (planId == null) throw new IllegalArgumentException("planId is required");
        return reservations.values().stream()
                .filter(value -> value.planId().equals(planId))
                .sorted(AUDIT_ORDER)
                .toList();
    }

    /** 释放结果包含完整原记录，供计划终结审计。 */
    public synchronized List<NpcReservation> releasePlan(UUID planId) {
        if (planId == null) throw new IllegalArgumentException("planId is required");
        List<NpcReservation> released = reservations.values().stream()
                .filter(value -> value.planId().equals(planId))
                .sorted(AUDIT_ORDER)
                .toList();
        released.forEach(value -> reservations.remove(value.key()));
        return released;
    }

    /** 清理结果包含过期持有人，而不是只报告清理数量。 */
    public synchronized List<NpcReservation> cleanupExpired(long now) {
        if (now < 0L) throw new IllegalArgumentException("now cannot be negative");
        List<NpcReservation> expired = reservations.values().stream()
                .filter(value -> value.expiredAt(now))
                .sorted(AUDIT_ORDER)
                .toList();
        expired.forEach(value -> reservations.remove(value.key()));
        return expired;
    }

    public synchronized List<NpcReservation> snapshot() {
        List<NpcReservation> result = new ArrayList<>(reservations.values());
        result.sort(AUDIT_ORDER);
        return List.copyOf(result);
    }

    private NpcReservation validReservation(NpcReservationKey key, long now) {
        NpcReservation held = reservations.get(key);
        if (held != null && held.expiredAt(now)) {
            reservations.remove(key);
            return null;
        }
        return held;
    }

    private static void requireIdentity(NpcReservationKey key, UUID planId, UUID npcId) {
        if (key == null || planId == null || npcId == null) {
            throw new IllegalArgumentException("reservation identity is required");
        }
    }
}
