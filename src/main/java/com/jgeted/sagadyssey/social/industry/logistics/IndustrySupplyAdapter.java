package com.jgeted.sagadyssey.social.industry.logistics;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class IndustrySupplyAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "logistics.delivery"; }
}
