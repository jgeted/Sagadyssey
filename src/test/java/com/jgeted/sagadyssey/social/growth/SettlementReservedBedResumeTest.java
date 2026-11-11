package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReservedBedResumeTest {
    final UUID id = UUID.randomUUID(), worker = UUID.randomUUID(), container = UUID.randomUUID();
    final SubjectRef owner = SubjectRef.institution(UUID.randomUUID());
    ProductionBatch bed() {
        return new ProductionBatch(id, "sagadyssey:growth_bed", worker, owner, container,
                List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.OAK_PLANKS)), 3),
                        new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.WHITE_WOOL)), 3)),
                new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.WHITE_BED)), 1),
                400, 100, 0, ProductionBatchStatus.RESERVED, "original unfinished work");
    }
    boolean resume(ProductionBatch b) {
        return SettlementGrowthData.matchesReservedBedBatch(b,id,worker,owner,container);
    }
    @Test void originalReservedBatchCanResumeWithoutTreatingOwnReservationAsFreeStock() {
        assertTrue(resume(bed()));
        assertFalse(resume(null));
    }
    @Test void otherActorBatchOwnerOrContainerCannotBorrowReservedMaterials() {
        var b=bed();
        assertFalse(SettlementGrowthData.matchesReservedBedBatch(b,UUID.randomUUID(),worker,owner,container));
        assertFalse(SettlementGrowthData.matchesReservedBedBatch(b,id,UUID.randomUUID(),owner,container));
        assertFalse(SettlementGrowthData.matchesReservedBedBatch(b,id,worker,SubjectRef.institution(UUID.randomUUID()),container));
        assertFalse(SettlementGrowthData.matchesReservedBedBatch(b,id,worker,owner,UUID.randomUUID()));
    }
    @Test void terminalOrDifferentProductionIntentCannotResume() {
        var b=bed();
        for(var state:List.of(ProductionBatchStatus.COMPLETED,ProductionBatchStatus.CANCELLED,ProductionBatchStatus.FAILED))
            assertFalse(resume(b.finish(state,200,"terminal")));
        assertFalse(resume(new ProductionBatch(id,b.recipeId(),worker,owner,container,
                List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.BIRCH_PLANKS)),3),b.inputs().get(1)),
                b.output(),400,100,0,b.status(),"different material")));
        assertFalse(resume(new ProductionBatch(id,b.recipeId(),worker,owner,container,
                List.of(b.inputs().get(0),new ProductionMaterial(b.inputs().get(1).fingerprint(),2)),
                b.output(),400,100,0,b.status(),"different quantity")));
        assertFalse(resume(new ProductionBatch(id,"other:bed",worker,owner,container,b.inputs(),b.output(),400,100,0,b.status(),"recipe")));
        assertFalse(resume(new ProductionBatch(id,b.recipeId(),worker,owner,container,b.inputs(),b.output(),399,100,0,b.status(),"labor")));
        assertFalse(resume(new ProductionBatch(id,b.recipeId(),worker,owner,container,b.inputs(),
                new ProductionMaterial(b.output().fingerprint(),2),400,100,0,b.status(),"output")));
    }
}
