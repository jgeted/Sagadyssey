package com.jgeted.sagadyssey.npc.registry;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class NpcEntityTypes {
    public static final DeferredRegister<EntityType<?>> REGISTRY =
            DeferredRegister.create(Registries.ENTITY_TYPE, Sagadyssey.MOD_ID);

    /** NPC 基础实体 */
    public static final Supplier<EntityType<NpcBase>> NPC_BASE = REGISTRY.register("npc_base",
            () -> EntityType.Builder.of(NpcBase::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(8)
                    .build("npc_base")
    );

    /** 生成蛋 */
    public static final DeferredRegister.Items SPAWN_EGGS =
            DeferredRegister.createItems(Sagadyssey.MOD_ID);

    public static final DeferredItem<DeferredSpawnEggItem> NPC_BASE_SPAWN_EGG =
            SPAWN_EGGS.register("npc_base_spawn_egg",
                    () -> new DeferredSpawnEggItem(NPC_BASE, 0x8B4513, 0xD2B48C,
                            new Item.Properties()));

    /** 注册属性 */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(NPC_BASE.get(), NpcBase.createAttributes().build());
    }
}
