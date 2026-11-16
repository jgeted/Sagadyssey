package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarKey;
import java.util.*;
import java.util.function.Predicate;

/** A reference to an existing mount's use, not ownership, equipment or a second body. */
public record WarMountReference(UUID contract, UUID character, UUID body, UUID mount, UUID owner, UUID principal, WarKey dimension) {
    public WarMountReference {
        Objects.requireNonNull(contract); Objects.requireNonNull(character); Objects.requireNonNull(body);
        Objects.requireNonNull(mount); Objects.requireNonNull(owner); Objects.requireNonNull(principal); Objects.requireNonNull(dimension);
        if (body.equals(mount)) throw new IllegalArgumentException("rider and mount must differ");
    }
    /** Both copies must be present and equal. A released use may be replaced explicitly. */
    public static boolean mayPrepare(Optional<WarMountReference> rider, Optional<WarMountReference> horse,
                                     WarMountReference next, Predicate<UUID> released) {
        Objects.requireNonNull(released);
        if (rider.isEmpty() && horse.isEmpty()) return true;
        if (rider.filter(next::equals).isPresent() && horse.filter(next::equals).isPresent()) return true;
        // A partial current claim is uncertain; never repair it by guessing after restart.
        if (rider.filter(v -> v.contract().equals(next.contract())).isPresent()
                || horse.filter(v -> v.contract().equals(next.contract())).isPresent()) return false;
        return rider.stream().allMatch(v -> released.test(v.contract())) && horse.stream().allMatch(v -> released.test(v.contract()));
    }
}
