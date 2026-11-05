package com.jgeted.sagadyssey.npc.handover;

import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NpcHandoverPolicyTest {
    @Test
    void ordinaryGoodsDefaultToGiftWithoutOpeningAChooser() {
        assertEquals(HandoverIntent.GIFT,
                NpcHandoverService.inferIntent(new ItemStack(Items.COBBLESTONE), false));
    }

    @Test
    void equipmentUsesIssueOnlyWhenTheRelationshipGrantsThatScope() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);

        assertNull(NpcHandoverService.inferIntent(sword, false));
        assertEquals(HandoverIntent.ISSUE, NpcHandoverService.inferIntent(sword, true));
        assertNotNull(NpcHandoverService.refusalFor(HandoverIntent.ISSUE, sword, false));
        assertNull(NpcHandoverService.refusalFor(HandoverIntent.ISSUE, sword, true));
    }

    @Test
    void visiblyDangerousItemsAreRefusedForEveryDeclaredIntent() {
        for (HandoverIntent intent : HandoverIntent.values()) {
            assertNotNull(NpcHandoverService.refusalFor(intent,
                    new ItemStack(Items.TNT), true), intent.name());
        }
    }

    @Test
    void paymentMustBePhysicalEmeraldAndConsumablesCannotBeLoans() {
        assertNotNull(NpcHandoverService.refusalFor(HandoverIntent.PAYMENT,
                new ItemStack(Items.DIAMOND), false));
        assertNull(NpcHandoverService.refusalFor(HandoverIntent.PAYMENT,
                new ItemStack(Items.EMERALD), false));
        assertNotNull(NpcHandoverService.refusalFor(HandoverIntent.LOAN,
                new ItemStack(Items.BREAD), false));
    }

    @Test
    void everyMovingIntentMapsToTheUnifiedTransferLedger() {
        assertEquals(TransferIntent.GIFT, NpcHandoverService.map(HandoverIntent.GIFT));
        assertEquals(TransferIntent.PAYMENT, NpcHandoverService.map(HandoverIntent.PAYMENT));
        assertEquals(TransferIntent.DELIVERY, NpcHandoverService.map(HandoverIntent.DELIVERY));
        assertEquals(TransferIntent.ISSUE, NpcHandoverService.map(HandoverIntent.ISSUE));
        assertEquals(TransferIntent.LOAN, NpcHandoverService.map(HandoverIntent.LOAN));
        assertThrows(IllegalArgumentException.class,
                () -> NpcHandoverService.map(HandoverIntent.SHOW));
    }
}
