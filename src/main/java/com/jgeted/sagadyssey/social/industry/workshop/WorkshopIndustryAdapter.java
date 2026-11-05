package com.jgeted.sagadyssey.social.industry.workshop;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class WorkshopIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "workshop.wood"; }
}
