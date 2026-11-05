package com.jgeted.sagadyssey.social.industry.metal;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class MetalIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "metal.ore-to-tool"; }
}
