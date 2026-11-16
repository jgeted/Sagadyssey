package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;

import java.math.BigInteger;
import java.util.*;
import java.util.function.*;

/** 纯预览用例。先验证批次与军队授权，再请求一次原生观察，不落盘或提交效果。 */
public final class WarPreparationService {
    private final WarDefinitions definitions;
    private final WarLoadoutCatalog loadouts;
    private final WarCommandService.Authority authority;
    private final BiFunction<List<WarMilitiaRequest>, Long, List<WarCandidateReport>> observer;
    private final Supplier<Set<WarKey>> missing;
    public WarPreparationService(WarDefinitions definitions, WarLoadoutCatalog loadouts, WarCommandService.Authority authority,
                                 BiFunction<List<WarMilitiaRequest>, Long, List<WarCandidateReport>> observer, Supplier<Set<WarKey>> missing) {
        this.definitions = Objects.requireNonNull(definitions); this.loadouts = Objects.requireNonNull(loadouts);
        this.authority = Objects.requireNonNull(authority); this.observer = Objects.requireNonNull(observer); this.missing = Objects.requireNonNull(missing);
    }
    public WarPreparation prepare(List<WarMilitiaRequest> requested, long now, long forecastTicks) {
        var requests = List.copyOf(requested);
        if (requests.isEmpty() || requests.size() > 128 || now < 0 || forecastTicks < 1 || forecastTicks > 2_400_000) {
            throw new IllegalArgumentException("invalid bounded preparation interval/batch");
        }
        Set<UUID> ids = new HashSet<>(), people = new HashSet<>(); var first = requests.getFirst();
        for (var request : requests) {
            if (!ids.add(request.id()) || !people.add(request.characterId()) || !first.actorId().equals(request.actorId()) || !first.armyId().equals(request.armyId())) {
                throw new IllegalArgumentException("preparation must have one actor/army and unique requests/people");
            }
        }
        List<WarCandidateReport> observed;
        if (authority.check(first.actorId(), first.armyId()) != WarCommandService.Permission.ALLOWED) {
            observed = requests.stream().map(request -> WarCandidateReport.unavailable(request, now, "ARMY_AUTHORITY_UNAVAILABLE_OR_DENIED")).toList();
        } else {
            var query = requests.stream().filter(request -> supported(request, now)).toList();
            Map<UUID, WarCandidateReport> byId = new HashMap<>();
            try {
                if (!query.isEmpty()) for (var report : observer.apply(query, now)) {
                    if (byId.putIfAbsent(report.requestId(), report) != null) throw new IllegalArgumentException("duplicate native candidate");
                }
                if (byId.size() != query.size()) throw new IllegalArgumentException("native candidate count mismatch");
                for (var request : query) {
                    var report = byId.get(request.id());
                    if (report == null || !request.characterId().equals(report.characterId()) || report.observedAtTick() != now) {
                        throw new IllegalArgumentException("native candidate identity/time mismatch");
                    }
                }
            } catch (RuntimeException exception) {
                byId.clear(); query.forEach(request -> byId.put(request.id(), WarCandidateReport.unavailable(request, now, "NATIVE_OBSERVATION_UNAVAILABLE")));
            }
            observed = requests.stream().map(request -> supported(request, now) ? byId.get(request.id())
                    : WarCandidateReport.unavailable(request, now, "UNSUPPORTED_OR_FUTURE_REQUEST")).toList();
        }
        var entries = new ArrayList<WarPreparation.Entry>(); Map<WarKey, BigInteger> costs = new TreeMap<>();
        for (int index = 0; index < requests.size(); index++) {
            var request = requests.get(index); entries.add(new WarPreparation.Entry(request, observed.get(index)));
            var wage = request.wage(); BigInteger numerator = BigInteger.valueOf(wage.amountPerPeriod()).multiply(BigInteger.valueOf(forecastTicks));
            BigInteger divisor = BigInteger.valueOf(wage.periodTicks());
            costs.merge(wage.currency(), numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor), BigInteger::add);
        }
        return new WarPreparation(now, forecastTicks, entries, missing.get(), costs);
    }
    private boolean supported(WarMilitiaRequest request, long now) {
        return request.requestedAtTick() <= now && com.jgeted.sagadyssey.social.war.domain.WarServiceRoles.supported(request)
                && definitions.supports(request.loadout(), WarDefinitions.Kind.TROOP, request.definitionVersion()) && loadouts.find(request.loadout()).isPresent();
    }
}
