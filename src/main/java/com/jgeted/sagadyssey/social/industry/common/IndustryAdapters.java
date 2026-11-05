package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.social.industry.food.FoodIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.forestry.ForestryIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.stone.StoneIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.metal.MetalIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.animal.WoolIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.fish.FishIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.logistics.IndustrySupplyAdapter;
import java.util.List;

/** Stable domain list. Listing or assessing an industry cannot create a task. */
public final class IndustryAdapters {
    private IndustryAdapters() {}
    public static List<IndustryAdapter> all() {
        return List.of(new FoodIndustryAdapter(),new ForestryIndustryAdapter(),new StoneIndustryAdapter(),
                new MetalIndustryAdapter(),new WoolIndustryAdapter(),new FishIndustryAdapter(),
                new WorkshopIndustryAdapter(),new IndustrySupplyAdapter());
    }
}
