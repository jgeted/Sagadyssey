package com.jgeted.sagadyssey.social.war.api;

import java.math.BigInteger;
import java.util.*;

/** 一次有界批量动员预览；预算是逐人向上取整的估算上界，不是实付或财务余额。 */
public record WarPreparation(long observedAtTick, long forecastTicks, List<Entry> entries,
                              Set<WarKey> missingCapabilities, Map<WarKey, BigInteger> wageBudgetCeiling) {
    public record Entry(WarMilitiaRequest request, WarCandidateReport candidate) {
        public Entry {
            Objects.requireNonNull(request); Objects.requireNonNull(candidate);
            if (!request.id().equals(candidate.requestId()) || !request.characterId().equals(candidate.characterId())) {
                throw new IllegalArgumentException("candidate does not match request");
            }
        }
    }
    public WarPreparation {
        entries = List.copyOf(entries); missingCapabilities = Set.copyOf(missingCapabilities); wageBudgetCeiling = Map.copyOf(wageBudgetCeiling);
        if (observedAtTick < 0 || forecastTicks < 1 || entries.isEmpty() || entries.size() > 128
                || wageBudgetCeiling.values().stream().anyMatch(value -> value.signum() < 0)) throw new IllegalArgumentException("invalid preparation");
    }
    public long observedClearCount() { return entries.stream().filter(entry -> entry.candidate().observedFactsClear()).count(); }
    /** 表示可以请求正式用例重新检查；不保证CLAIM成功或装备已经发到。 */
    public boolean readyForSubmission() { return missingCapabilities.isEmpty() && observedClearCount() == entries.size(); }
}
