package com.jgeted.sagadyssey.social.household;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** Concrete household buffer goals; observations are always counted from the real pantry. */
public record HouseholdProvisionPlan(UUID householdId, int foodItemsTarget,
                                     int fuelItemsTarget, int workingToolsTarget) {
    public HouseholdProvisionPlan {
        if (householdId == null) throw new IllegalArgumentException("household id required");
        foodItemsTarget = Math.max(0, foodItemsTarget);
        fuelItemsTarget = Math.max(0, fuelItemsTarget);
        workingToolsTarget = Math.max(0, workingToolsTarget);
    }

    public static HouseholdProvisionPlan forMembers(UUID householdId, int members) {
        return new HouseholdProvisionPlan(householdId, Math.max(6, members * 6),
                Math.max(8, members * 2), Math.max(1, (members + 2) / 3));
    }

    public Snapshot inspect(Container storage) {
        int food = 0;
        int fuel = 0;
        int tools = 0;
        if (storage != null) {
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                ItemStack stack = storage.getItem(slot);
                if (stack.isEmpty()) continue;
                if (NpcFoodItems.canConsumePlanned(stack, false)) food += stack.getCount();
                if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                        || stack.is(Items.COAL_BLOCK)) fuel += stack.getCount();
                if (com.jgeted.sagadyssey.core.MinecraftApi.isDigger(stack) && stack.getDamageValue() < stack.getMaxDamage()) {
                    tools += stack.getCount();
                }
            }
        }
        return new Snapshot(food, fuel, tools, Math.max(0, foodItemsTarget - food),
                Math.max(0, fuelItemsTarget - fuel), Math.max(0, workingToolsTarget - tools));
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Household", UUIDUtil.CODEC, householdId);
        tag.putInt("FoodTarget", foodItemsTarget);
        tag.putInt("FuelTarget", fuelItemsTarget);
        tag.putInt("ToolTarget", workingToolsTarget);
        return tag;
    }

    static HouseholdProvisionPlan load(CompoundTag tag) {
        return new HouseholdProvisionPlan(tag.read("Household", UUIDUtil.CODEC).orElseThrow(), tag.getIntOr("FoodTarget", 0),
                tag.getIntOr("FuelTarget", 0), tag.getIntOr("ToolTarget", 0));
    }

    public record Snapshot(int food, int fuel, int workingTools,
                           int foodDeficit, int fuelDeficit, int toolDeficit) {
        public boolean secure() { return foodDeficit == 0 && fuelDeficit == 0 && toolDeficit == 0; }
    }
}
