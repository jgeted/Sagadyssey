package com.jgeted.sagadyssey.social.growth;

import java.util.Comparator;
import java.util.List;

/** 不读写世界的协调规则；模型和实际调度共用，未知条件不能当成满足。 */
public final class SettlementPolicy {
    public static final int VERSION = 1;
    public static final double LOW_DAYS = 4;
    public static final double HIGH_DAYS = 14;
    public static final double EMERGENCY_DAYS = 1;
    public static final double DAILY_MEALS_PER_PERSON = 1.5;

    private SettlementPolicy() { }

    public record Candidate(String id, int tier, long created, long deadline,
                            double travelAndWork, int blockersReleased, boolean feasible) { }

    public static boolean harvest(boolean previous, double usableFood, double dailyMeals,
                                  boolean emergency) {
        if (dailyMeals <= 0 || usableFood < 0 || !Double.isFinite(dailyMeals)) {
            throw new IllegalArgumentException("无效食物预算");
        }
        double days = usableFood / dailyMeals;
        if (emergency || days < LOW_DAYS) return true;
        if (days >= HIGH_DAYS) return false;
        return previous;
    }

    public static Candidate choose(List<Candidate> candidates) {
        return candidates.stream().filter(Candidate::feasible).min(Comparator
                .comparingInt(Candidate::tier)
                .thenComparingLong(Candidate::deadline)
                .thenComparingLong(Candidate::created)
                .thenComparingDouble(Candidate::travelAndWork)
                .thenComparing(Comparator.comparingInt(Candidate::blockersReleased).reversed())
                .thenComparing(Candidate::id)).orElse(null);
    }

    public static int materialDeficit(int required, int built, int available, int committed) {
        if (required < 0 || built < 0 || available < 0 || committed < 0) {
            throw new IllegalArgumentException("负材料数量");
        }
        return Math.max(0, required - built - available - committed);
    }
}
