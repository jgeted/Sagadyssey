package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 投射物的原军事来源引用；只引用既有服役/军令/遭遇，不授予新敌对权限。 */
public record WarShotPermit(UUID contract, UUID character, UUID body, UUID army, UUID unit, UUID order,
                            UUID issuer, UUID encounter, UUID intendedTarget, boolean immediateThreat, long firedAt) {
    public WarShotPermit {
        for (var id : List.of(contract, character, body, army, unit, order, issuer, encounter, intendedTarget)) Objects.requireNonNull(id);
        if (firedAt < 0) throw new IllegalArgumentException("invalid shot time");
    }
    public boolean currentAt(long now) { return now >= firedAt && now - firedAt <= 200; }
}
