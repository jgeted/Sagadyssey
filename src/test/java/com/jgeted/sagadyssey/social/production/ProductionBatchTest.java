package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductionBatchTest {
    @Test
    void roundTripPreservesInputsOwnerAndStableReservationToken() {
        UUID id = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID container = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        ProductionBatch original = new ProductionBatch(id, "iron_tool", worker, owner, container,
                List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)), 2),
                        new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)), 1)),
                new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)), 1),
                34, 80L, 0L, ProductionBatchStatus.RESERVED, "reserved");

        ProductionBatch loaded = ProductionBatch.load(original.save());

        assertEquals(original, loaded);
        assertEquals("production:" + id, loaded.token());
        assertFalse(loaded.status().terminal());
    }

    @Test
    void completionIsTerminalAndKeepsBatchIdentity() {
        UUID id = UUID.randomUUID();
        ProductionBatch batch = new ProductionBatch(id, "bread", UUID.randomUUID(),
                SubjectRef.character(UUID.randomUUID()), UUID.randomUUID(),
                List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.WHEAT)), 3)),
                new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.BREAD)), 1),
                20, 5L, 0L, ProductionBatchStatus.RESERVED, "");

        ProductionBatch completed = batch.finish(ProductionBatchStatus.COMPLETED, 30L, "done");

        assertEquals(id, completed.id());
        assertEquals(30L, completed.finishedAt());
        assertTrue(completed.status().terminal());
        assertThrows(IllegalArgumentException.class,
                () -> batch.finish(ProductionBatchStatus.RESERVED, 30L, "bad"));
    }

    @Test
    void replayIntentIgnoresLifecycleFieldsButRejectsDifferentRecipe() {
        UUID id = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID container = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        List<ProductionMaterial> inputs = List.of(
                new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)), 2));
        ProductionMaterial output = new ProductionMaterial(
                ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)), 1);
        ProductionBatch reserved = new ProductionBatch(id, "iron_sword", worker, owner,
                container, inputs, output, 34, 10L, 0L,
                ProductionBatchStatus.RESERVED, "reserved");
        ProductionBatch completed = new ProductionBatch(id, "iron_sword", worker, owner,
                container, inputs, output, 34, 10L, 60L,
                ProductionBatchStatus.COMPLETED, "done");
        ProductionBatch different = new ProductionBatch(id, "iron_pickaxe", worker, owner,
                container, inputs, output, 34, 10L, 0L,
                ProductionBatchStatus.RESERVED, "reserved");

        assertTrue(reserved.sameIntentAs(completed));
        assertFalse(reserved.sameIntentAs(different));
    }
}
