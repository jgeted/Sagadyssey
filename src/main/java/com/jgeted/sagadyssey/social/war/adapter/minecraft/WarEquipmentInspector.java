package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.api.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.*;

/** 显式整备预检时读取现有实体；不查找/修复人物绑定，不改变职业、装备或AI。 */
public final class WarEquipmentInspector {
    private WarEquipmentInspector() {}

    public static WarEquipmentSnapshot inspect(NpcBase npc, WarLoadout.Weapon intendedPrimary) {
        return inspect(npc, intendedPrimary, WarNativeBindings.currentBuild());
    }
    public static WarEquipmentSnapshot inspect(NpcBase npc, WarLoadout.Weapon intendedPrimary, WarNativeBindings bindings) {
        if (!(npc.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("equipment inspection requires server thread");
        }
        Objects.requireNonNull(intendedPrimary);
        Set<WarLoadout.Weapon> available = EnumSet.noneOf(WarLoadout.Weapon.class);
        boolean shield = false, melee = false;
        List<ItemStack> carried = new ArrayList<>();
        carried.add(npc.getMainHandItem()); carried.add(npc.getOffhandItem());
        for (int slot = 0; slot < npc.getPersonalInventory().getContainerSize(); slot++) {
            carried.add(npc.getPersonalInventory().getItem(slot));
        }
        for (ItemStack stack : carried) {
            if (stack.isEmpty()) continue;
            var profile = bindings.items().inspect(stack); available.addAll(profile.weapons());
            shield |= profile.shield(); melee |= profile.meleeBackup();
        }
        boolean riderArmor = false;
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
            riderArmor |= bindings.items().inspect(npc.getItemBySlot(slot)).armor();
        var using = bindings.items().inspect(npc.getUseItem()).weapons();
        return new WarEquipmentSnapshot(new WarLoadout(bindings.actors().mount(npc), intendedPrimary, shield, riderArmor), available, melee,
                npc.isBlocking(), npc.isUsingItem() && (using.contains(WarLoadout.Weapon.BOW) || using.contains(WarLoadout.Weapon.CROSSBOW)));
    }
}
