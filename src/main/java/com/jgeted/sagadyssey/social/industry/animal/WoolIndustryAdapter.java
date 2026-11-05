package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class WoolIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "wool.shear"; }
}
