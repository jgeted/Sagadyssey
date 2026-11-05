package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;

/** Server-side market-scale result; clients never supply quantity or durability. */
public record MarketScaleMeasurement(ItemFingerprint fingerprint, int count,
                                     int damage, int maximumDamage,
                                     QualityGrade grade) { }
