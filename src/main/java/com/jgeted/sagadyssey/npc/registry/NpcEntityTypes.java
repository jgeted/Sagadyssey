package com.jgeted.sagadyssey.npc.registry;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
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
                    .build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE,
                            net.minecraft.resources.Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_base")))
    );

    /** 生成蛋 */
    public static final DeferredRegister.Items SPAWN_EGGS =
            DeferredRegister.createItems(Sagadyssey.MOD_ID);

    public static final DeferredItem<SpawnEggItem> NPC_BASE_SPAWN_EGG =
            SPAWN_EGGS.registerItem("npc_base_spawn_egg",
                    properties -> new SpawnEggItem(properties.spawnEgg(NPC_BASE.get())));

    /** 注册属性 */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(NPC_BASE.get(), NpcBase.createAttributes().build());
    }
}
