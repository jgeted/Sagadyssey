package com.jgeted.sagadyssey.core.registry;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.container.NpcEquipMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModMenuTypes {
    private ModMenuTypes() {}

    public static final DeferredRegister<MenuType<?>> REGISTRY =
            DeferredRegister.create(Registries.MENU, Sagadyssey.MOD_ID);

    public static final Supplier<MenuType<NpcEquipMenu>> NPC_EQUIP =
            REGISTRY.register("npc_equip",
                    () -> IMenuTypeExtension.create(NpcEquipMenu::new));
}
