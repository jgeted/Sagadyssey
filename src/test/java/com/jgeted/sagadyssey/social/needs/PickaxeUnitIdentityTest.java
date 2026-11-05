package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PickaxeUnitIdentityTest {
    private final SubjectRef owner=SubjectRef.household(UUID.randomUUID());
    private final FieldworkStorage target=new FieldworkStorage("minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),owner,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"test");
    private final SupplyFlowData.Demand scope=new SupplyFlowData.Demand(owner,target.containerId());
    private final ItemFingerprint pick=new ItemFingerprint("minecraft:iron_pickaxe",0);
    private final NeedRequest request=NeedRequest.open(new NeedRequest.Key(owner.id(),"HOUSEHOLD:household:tools",pick,target.containerId(),0,10000),2,32,1);
    private ProductionOrder order(int unit,int quantity,int iron,int work,SubjectRef principal){
        return ProductionOrder.open(SupplyFlowService.unitOrderId(request.id(),unit),principal,principal,"sagadyssey:iron_pickaxe",
                List.of(new ProductionMaterial(new ItemFingerprint("minecraft:iron_ingot",0),iron),new ProductionMaterial(new ItemFingerprint("minecraft:coal",0),1)),
                new ProductionMaterial(pick,quantity),work,SupplyFlowService.unitBatchId(request.id(),unit),target.dimension(),BlockPos.ZERO,target.containerId(),target.dimension(),target.position(),1,"one actual unit");
    }
    @Test void firstUnitRetainsExistingRootIdentityAndNextUnitIsStableAndDistinct(){
        assertEquals(SupplyFlowService.receipt(request.id(),"production-order"),SupplyFlowService.unitOrderId(request.id(),0));
        assertEquals(SupplyFlowService.receipt(request.id(),"production-batch"),SupplyFlowService.unitBatchId(request.id(),0));
        assertNotEquals(SupplyFlowService.unitOrderId(request.id(),0),SupplyFlowService.unitOrderId(request.id(),1));
        assertEquals(SupplyFlowService.unitOrderId(request.id(),1),SupplyFlowService.unitOrderId(request.id(),1));
        assertNotEquals(SupplyFlowService.unitOrderId(request.id(),1),SupplyFlowService.unitBatchId(request.id(),1));
    }
    @Test void quantityAndLaborCannotReuseTheSameUnitIdentity(){
        assertTrue(SupplyFlowService.unitProductionIntent(request,scope,target,order(0,1,3,80,owner),0));
        assertFalse(SupplyFlowService.unitProductionIntent(request,scope,target,order(0,2,3,80,owner),0));
        assertFalse(SupplyFlowService.unitProductionIntent(request,scope,target,order(0,1,6,80,owner),0));
        assertFalse(SupplyFlowService.unitProductionIntent(request,scope,target,order(0,1,3,40,owner),0));
    }
    @Test void anotherOwnerCannotTakeOverTheSameUnitIdentifier(){assertFalse(SupplyFlowService.unitProductionIntent(request,scope,target,order(0,1,3,80,SubjectRef.character(UUID.randomUUID())),0));}
    @Test void outOfSequenceUnitCannotMasqueradeAsFirst(){assertFalse(SupplyFlowService.unitProductionIntent(request,scope,target,order(1,1,3,80,owner),0));}
    @Test void unitIdentifierHasTheExistingBoundedRequestLimit(){assertThrows(IllegalArgumentException.class,()->SupplyFlowService.unitOrderId(request.id(),64));assertThrows(IllegalArgumentException.class,()->SupplyFlowService.unitBatchId(request.id(),-1));}
    @Test void absentOrUnfinishedBatchCannotAuthorizeNextManufacture(){
        var o=order(0,1,3,80,owner).claim(UUID.randomUUID(),2,"real smith");
        assertFalse(SupplyFlowService.completedUnitMatches(o,null));
        var b=new ProductionBatch(o.batchId(),o.recipeId(),o.assignedWorkerId(),owner,target.containerId(),o.inputs(),o.output(),80,2,0,ProductionBatchStatus.RESERVED,"unfinished");
        assertFalse(SupplyFlowService.completedUnitMatches(o,b));assertTrue(SupplyFlowService.completedUnitMatches(o,b.finish(ProductionBatchStatus.COMPLETED,100,"real receipt fixture")));
    }
    @Test void foreignWorkerOrOutputDoesNotProveOriginalUnitComplete(){
        var o=order(0,1,3,80,owner).claim(UUID.randomUUID(),2,"real smith");
        var b=new ProductionBatch(o.batchId(),o.recipeId(),UUID.randomUUID(),owner,target.containerId(),o.inputs(),o.output(),80,2,100,ProductionBatchStatus.COMPLETED,"wrong worker");
        assertFalse(SupplyFlowService.completedUnitMatches(o,b));
    }
    @Test void distinctRealUnitAcknowledgementsAccumulateOnceAcrossNbt(){
        var data=new NeedRequestData();var r=data.openOrUpdate(request.key(),2,32,1);
        for(int round=0;round<2;round++)for(int unit=0;unit<2;unit++){
            var id=SupplyFlowService.unitOrderId(r.id(),unit);
            data.acknowledge(SupplyFlowService.receipt(id,"produced-reserve"),r.id(),NeedRequestData.Action.RESERVE,1,100+round);
            data.acknowledge(SupplyFlowService.receipt(id,"produced-dispatch"),r.id(),NeedRequestData.Action.DISPATCH,1,100+round);
            data.acknowledge(SupplyFlowService.receipt(id,"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,1,100+round);
            if(round==0&&unit==0)assertEquals(1,data.request(r.id()).remaining());
        }
        var restored=NeedRequestData.load(data.save(new CompoundTag(),null),null);
        for(int unit=0;unit<2;unit++)restored.acknowledge(SupplyFlowService.receipt(SupplyFlowService.unitOrderId(r.id(),unit),"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,1,102);
        assertEquals(2,restored.request(r.id()).delivered());assertEquals(0,restored.request(r.id()).remaining());assertEquals(NeedRequestStatus.DELIVERED,restored.request(r.id()).status());
    }
    @Test void onlyRealCompletedUnitCanCloseUncommittedObsoleteRawRequest(){
        var o=order(0,1,3,80,owner).claim(UUID.randomUUID(),2,"real smith")
                .transition(ProductionOrderStatus.RESERVED,3,"reserved").transition(ProductionOrderStatus.IN_PROGRESS,4,"actual work")
                .transition(ProductionOrderStatus.SETTLING,100,"worked").transition(ProductionOrderStatus.COMPLETED,101,"settled");
        var batch=new ProductionBatch(o.batchId(),o.recipeId(),o.assignedWorkerId(),owner,target.containerId(),o.inputs(),o.output(),80,2,100,ProductionBatchStatus.COMPLETED,"actual inputs consumed");
        var raw=NeedRequest.open(new NeedRequest.Key(owner.id(),"HOUSEHOLD:production:"+o.id(),new ItemFingerprint("minecraft:coal",0),target.containerId(),0,10000),1,32,1);
        assertTrue(SupplyFlowService.uncommittedUnitMaterialIsRedundant(raw,o,batch));
        assertFalse(SupplyFlowService.uncommittedUnitMaterialIsRedundant(raw,o,null));
        assertFalse(SupplyFlowService.uncommittedUnitMaterialIsRedundant(raw.reserve(1,2),o,batch));
        assertFalse(SupplyFlowService.uncommittedUnitMaterialIsRedundant(raw.reserve(1,2).dispatch(1,3),o,batch));
        var cancelled=raw.transition(NeedRequestStatus.CANCELLED,102,"original unit completed; keep counters");
        assertEquals(0,cancelled.delivered());assertEquals(1,cancelled.originalRequested());assertEquals(1,cancelled.requested());
        assertFalse(SupplyFlowService.uncommittedUnitMaterialIsRedundant(cancelled,o,batch));
    }

}
