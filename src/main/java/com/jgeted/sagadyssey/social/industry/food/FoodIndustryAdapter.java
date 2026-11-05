package com.jgeted.sagadyssey.social.industry.food;

import com.jgeted.sagadyssey.social.industry.common.IndustryAdapter;

/** Bounded opportunity adapter; execution remains in existing authoritative NPC/asset services. */
public final class FoodIndustryAdapter implements IndustryAdapter {
    @Override public String capabilityId() { return "food.carrot"; }
}
