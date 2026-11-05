package com.jgeted.sagadyssey.social.industry.forestry;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class ForestryIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "forestry.fell"; }
}
