package com.jgeted.sagadyssey.registry;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.block.TestBlock;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks REGISTRY = DeferredRegister.createBlocks(Sagadyssey.MOD_ID);

    public static final DeferredBlock<TestBlock> TEST_BLOCK = REGISTRY.registerBlock("test_block",
            properties -> new TestBlock(properties.strength(1.0f).noOcclusion()));
}
