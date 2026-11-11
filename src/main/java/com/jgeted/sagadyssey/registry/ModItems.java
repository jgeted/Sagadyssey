package com.jgeted.sagadyssey.registry;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.item.NpcCommandWandItem;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items REGISTRY = DeferredRegister.createItems(Sagadyssey.MOD_ID);

    public static final DeferredItem<BlockItem> TEST_BLOCK_ITEM = REGISTRY.registerSimpleBlockItem(ModBlocks.TEST_BLOCK);

    public static final DeferredItem<NpcCommandWandItem> NPC_COMMAND_WAND = REGISTRY.registerItem(
            "npc_command_wand", properties -> new NpcCommandWandItem(properties.stacksTo(1)));
}
