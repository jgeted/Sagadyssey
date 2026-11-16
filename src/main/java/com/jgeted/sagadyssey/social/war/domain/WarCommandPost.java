package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** A fixed banner reference, not a land claim or an order. */
public record WarCommandPost(UUID unit, UUID army, UUID binder, WarIntent.Position position, WarKey block, long revision) {
    public WarCommandPost {
        Objects.requireNonNull(unit);Objects.requireNonNull(army);Objects.requireNonNull(binder);Objects.requireNonNull(position);Objects.requireNonNull(block);
        if(revision<1 || Math.abs((long)position.x())>29_999_900 || Math.abs((long)position.z())>29_999_900)throw new IllegalArgumentException("invalid command post");
    }
    public static boolean follows(WarCommandPost before,WarCommandPost next){return before.equals(next) || before.unit.equals(next.unit) && before.army.equals(next.army) && before.revision<Long.MAX_VALUE && next.revision==before.revision+1;}
}
