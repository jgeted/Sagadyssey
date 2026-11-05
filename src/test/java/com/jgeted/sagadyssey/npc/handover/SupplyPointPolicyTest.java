package com.jgeted.sagadyssey.npc.handover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupplyPointPolicyTest {
    @Test
    void keepsReserveCapsEachWithdrawalAndEnforcesBeneficiaries() {
        UUID npc = UUID.randomUUID();
        SupplyPointPolicy policy = new SupplyPointPolicy(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", BlockPos.ZERO, SupplyCategory.FOOD,
                16, 8, 200L, Set.of(npc), UUID.randomUUID(), true);

        assertTrue(policy.allows(npc));
        assertFalse(policy.allows(UUID.randomUUID()));
        assertEquals(8, policy.claimable(64, 0L, 300L));
        assertEquals(4, policy.claimable(20, 0L, 300L));
        assertEquals(0, policy.claimable(64, 250L, 300L));
    }

    @Test
    void categoriesDescribeExistingItemsInsteadOfGeneratingThem() {
        assertTrue(SupplyCategory.FOOD.matches(new ItemStack(Items.BREAD)));
        assertTrue(SupplyCategory.AMMUNITION.matches(new ItemStack(Items.ARROW)));
        assertTrue(SupplyCategory.TOOL.matches(new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(SupplyCategory.FOOD.matches(new ItemStack(Items.COBBLESTONE)));
    }
}
