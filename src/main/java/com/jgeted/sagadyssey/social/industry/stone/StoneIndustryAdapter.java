package com.jgeted.sagadyssey.social.industry.stone;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class StoneIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "stone.mine"; }
}
