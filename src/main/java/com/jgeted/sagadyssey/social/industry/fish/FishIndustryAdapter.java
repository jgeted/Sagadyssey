package com.jgeted.sagadyssey.social.industry.fish;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class FishIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "fish.capture"; }
}
