package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AppraisalServiceTest {
    @Test
    void serverMeasurementCreatesATamperEvidentQualityMark() {
        RightsStateData rights = new RightsStateData();
        AppraisalData appraisals = new AppraisalData();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        UUID containerId = UUID.randomUUID();
        SimpleContainer inventory = new SimpleContainer(2);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(pickaxe.getMaxDamage() / 2);
        inventory.setItem(0, pickaxe);
        rights.ensureContext(containerId, "market-scale", owner, owner, AccessPolicy.PRIVATE);

        AppraisalRecord mark = AppraisalService.appraise(appraisals, rights, owner, owner,
                containerId, inventory, 0, 100L, 1_000L);

        assertEquals(QualityGrade.WORN, mark.grade());
        assertTrue(mark.verifies(containerId, 0, inventory, 101L));
        assertEquals(mark, AppraisalRecord.load(mark.save()));
        inventory.getItem(0).setDamageValue(inventory.getItem(0).getDamageValue() + 1);
        assertFalse(mark.verifies(containerId, 0, inventory, 102L));
    }

    @Test
    void unlicensedAppraiserCannotInspectPrivateGoods() {
        RightsStateData rights = new RightsStateData();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        SubjectRef stranger = SubjectRef.character(UUID.randomUUID());
        UUID containerId = UUID.randomUUID();
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.DIAMOND, 3));
        rights.ensureContext(containerId, "private", owner, owner, AccessPolicy.PRIVATE);

        assertThrows(IllegalStateException.class, () -> AppraisalService.appraise(
                new AppraisalData(), rights, stranger, owner, containerId, inventory,
                0, 10L, 100L));
    }

    @Test
    void expiredMarkNoLongerVerifiesEvenWhenStackIsUnchanged() {
        RightsStateData rights = new RightsStateData();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        UUID containerId = UUID.randomUUID();
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.COAL, 8));
        rights.ensureContext(containerId, "market", owner, owner, AccessPolicy.PRIVATE);
        AppraisalRecord mark = AppraisalService.appraise(new AppraisalData(), rights,
                owner, owner, containerId, inventory, 0, 20L, 5L);

        assertFalse(mark.verifies(containerId, 0, inventory, 25L));
    }
}
