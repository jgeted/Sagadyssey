package com.jgeted.sagadyssey.social.needs;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CancelledSupplySafetyTest {
    private ProductionOrder open(int qty){var p=SubjectRef.character(UUID.randomUUID());return ProductionOrder.open(UUID.randomUUID(),p,p,"sagadyssey:iron_pickaxe",List.of(new ProductionMaterial(new ItemFingerprint("minecraft:iron_ingot",0),qty*3),new ProductionMaterial(new ItemFingerprint("minecraft:coal",0),qty)),new ProductionMaterial(new ItemFingerprint("minecraft:iron_pickaxe",0),qty),80,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,1,"pending");}
    private ProductionOrder assigned(String why,int qty){return open(qty).claim(UUID.randomUUID(),2,"real worker").transition(ProductionOrderStatus.CANCELLED,3,why);}
    private ProductionBatch batch(ProductionOrder o,ProductionBatchStatus status){return new ProductionBatch(o.batchId(),o.recipeId(),o.assignedWorkerId(),o.outputOwner(),o.targetContainerId(),o.inputs(),o.output(),o.requiredWorkTicks(),2,3,status,"component historical fact; not a simulated world effect");}
    @Test void effectiveOrderCannotUseCancellationRecovery(){assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(open(1),null));}
    @Test void unclaimedCancellationHasNoWorkerEffect(){assertTrue(SupplyFlowService.cancelledBatchHasNoEffect(open(1).transition(ProductionOrderStatus.CANCELLED,2,"cancelled before claim"),null));}
    @Test void missingAssignedBatchWithoutExactEvidenceIsNotSafe(){assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(assigned("计划已过期",1),null));}
    @Test void knownOverstackPreflightIsNarrowAndDoesNotRewriteQuantity(){var o=assigned("执行器异常: 单批成品超过堆叠上限",2);assertTrue(SupplyFlowService.cancelledBatchHasNoEffect(o,null));assertEquals(2,o.output().quantity());assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(assigned("执行器异常: 单批成品超过堆叠上限",1),null));assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(assigned("other exception",2),null));}
    @Test void outstandingAndSuccessfulBatchAreNeverAssumedCancelled(){var o=assigned("cancelled",1);assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(o,batch(o,ProductionBatchStatus.RESERVED)));assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(o,batch(o,ProductionBatchStatus.COMPLETED)));}
    @Test void matchingCancelledBatchIsEvidenceButDoesNotReleaseIt(){var o=assigned("cancelled",1);var b=batch(o,ProductionBatchStatus.CANCELLED);assertTrue(SupplyFlowService.cancelledBatchHasNoEffect(o,b));assertEquals(ProductionBatchStatus.CANCELLED,b.status());}
    @Test void matchingRolledBackFailureIsDistinctFromMissingEvidence(){var o=assigned("cancelled",1);assertTrue(SupplyFlowService.cancelledBatchHasNoEffect(o,batch(o,ProductionBatchStatus.FAILED)));}
    @Test void foreignBatchIdentityNeverConfirmsOriginalCancellation(){var o=assigned("cancelled",1);var b=new ProductionBatch(o.batchId(),o.recipeId(),UUID.randomUUID(),o.outputOwner(),o.targetContainerId(),o.inputs(),o.output(),80,2,3,ProductionBatchStatus.CANCELLED,"different worker");assertFalse(SupplyFlowService.cancelledBatchHasNoEffect(o,b));}
}
