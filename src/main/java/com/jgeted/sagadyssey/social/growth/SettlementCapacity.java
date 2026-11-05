package com.jgeted.sagadyssey.social.growth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only rules shared by real decisions, audits and the bounded model. No inventory or identity creation. */
public final class SettlementCapacity {
    public static final long RECHECK = 200;
    public static final long RESUME = 600;
    public static final long STABLE_MIN = 24000;
    /** Frozen against FOOD-CYCLE-CALIBRATION.json; no offline wall-clock catch-up. */
    public static final long REFERENCE_CYCLE = 24000;
    private SettlementCapacity() { }

    public record Home(UUID id, int living, int food, int usableBeds, int availableWorkers,
                       boolean known, int incoming, int seedReserve) {
        public Home {
            if (id == null || living < 0 || food < 0 || usableBeds < 0 || availableWorkers < 0
                    || incoming < 0 || seedReserve < 0) throw new IllegalArgumentException("Invalid capacity snapshot");
        }
        public double dailyMeals() { return living * SettlementPolicy.DAILY_MEALS_PER_PERSON; }
        public boolean stableFloor() { return known && usableBeds >= living && food >= Math.ceil(dailyMeals()); }
        public boolean canSupport(int arrivals, int transitionDays) {
            return known && availableWorkers >= 2 && arrivals >= 0 && transitionDays >= 1
                    && food >= Math.ceil((living + arrivals) * SettlementPolicy.DAILY_MEALS_PER_PERSON * transitionDays);
        }
    }

    public static boolean allStable(List<Home> homes) {
        return !homes.isEmpty() && homes.stream().allMatch(Home::stableFloor);
    }

    public static int replenishment(int target, int available, int inTransit) {
        if (target < 0 || available < 0 || inTransit < 0) throw new IllegalArgumentException("Negative quantity");
        return Math.max(0, target - available - inTransit);
    }

    public static long stableWindow(long calibratedCycle) {
        if (calibratedCycle <= 0 || calibratedCycle > Long.MAX_VALUE / 3) throw new IllegalArgumentException("Invalid cycle");
        return Math.max(STABLE_MIN, 3 * calibratedCycle);
    }

    public static boolean uniquePeople(List<UUID> source, List<UUID> traveling, List<UUID> destination) {
        Set<UUID> seen = new HashSet<>();
        for (var group : List.of(source, traveling, destination)) for (var person : group) if (!seen.add(person)) return false;
        return true;
    }
}
