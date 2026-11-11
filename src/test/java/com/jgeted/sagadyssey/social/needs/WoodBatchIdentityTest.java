package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.industry.common.IndustryTransfers;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Component contracts only; these records do not simulate real carpentry or a full restart. */
class WoodBatchIdentityTest {
    private final SubjectRef owner=SubjectRef.character(UUID.randomUUID());
    private final FieldworkStorage stock=new FieldworkStorage("minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"wood");
    private final SupplyFlowData.Demand scope=new SupplyFlowData.Demand(owner,stock.containerId());
    private final ItemFingerprint planks=new ItemFingerprint("minecraft:birch_planks",0);
    private final NeedRequest request=NeedRequest.open(new NeedRequest.Key(owner.id(),"CHARACTER:workshop:birch",planks,stock.containerId(),0,24000),24,32,1);
    private ProductionMaterial m(String item,int q){return new ProductionMaterial(new ItemFingerprint("minecraft:"+item,0),q);}
    private ProductionOrder order(int index,int output,int logs,int work,SubjectRef principal){
        var id=SupplyFlowService.woodOrderId(request.id(),owner.id(),WorkshopTask.PLANKS,index);
        return ProductionOrder.open(id,principal,principal,WorkshopTask.PLANKS,List.of(m("birch_log",logs)),
                new ProductionMaterial(planks,output),work,SupplyFlowService.woodBatchId(id),stock.dimension(),BlockPos.ZERO,
                stock.containerId(),stock.dimension(),stock.position(),2,"original actual recipe");
    }
    @Test void firstIdPreservesExistingRequestAndLaterBatchHasIndependentStableIdentity(){
        var first=SupplyFlowService.woodOrderId(request.id(),owner.id(),WorkshopTask.PLANKS,0);
        assertEquals(IndustryTransfers.id("industry-planks:"+owner.id()+":need:"+request.id()),first);
        var second=SupplyFlowService.woodOrderId(request.id(),owner.id(),WorkshopTask.PLANKS,1);
        assertNotEquals(first,second);assertEquals(second,SupplyFlowService.woodOrderId(request.id(),owner.id(),WorkshopTask.PLANKS,1));
        assertNotEquals(SupplyFlowService.woodBatchId(first),SupplyFlowService.woodBatchId(second));
        assertThrows(IllegalArgumentException.class,()->SupplyFlowService.woodBusiness(request.id(),64));
    }
    @Test void fishingRodRemainsOriginalSingleOrderAndIsNotASequencedWoodBatch(){
        assertTrue(SupplyFlowService.woodBatchRecipe(WorkshopTask.PLANKS));
        assertTrue(SupplyFlowService.woodBatchRecipe(WorkshopTask.STICKS));
        assertTrue(SupplyFlowService.woodBatchRecipe(WorkshopTask.BED));
        assertFalse(SupplyFlowService.woodBatchRecipe(WorkshopTask.FISHING_ROD));
        var id=IndustryTransfers.id("industry-fishing-rod:"+owner.id()+":need:"+request.id());
        var rod=ProductionOrder.open(id,owner,owner,WorkshopTask.FISHING_ROD,List.of(m("stick",3),m("string",2)),m("fishing_rod",1),200,
                SupplyFlowService.woodBatchId(id),stock.dimension(),BlockPos.ZERO,stock.containerId(),stock.dimension(),stock.position(),2,"original rod path");
        assertTrue(WorkshopTask.recognized(rod));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,rod,0));
        var claimed=rod.claim(owner.id(),3,"original worker");
        var batch=new ProductionBatch(claimed.batchId(),claimed.recipeId(),owner.id(),owner,stock.containerId(),claimed.inputs(),claimed.output(),200,4,300,ProductionBatchStatus.COMPLETED,"original receipt");
        assertTrue(SupplyFlowService.completedUnitMatches(claimed,batch));
    }
    @Test void twentyFourIsTwoActualTwelveBatchesAndNeverAnExtraThird(){
        assertTrue(SupplyFlowService.woodProductionIntent(request,scope,stock,order(0,12,3,200,owner),0));
        assertTrue(SupplyFlowService.woodProductionIntent(request,scope,stock,order(1,12,3,200,owner),1));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(2,12,3,200,owner),2));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(1,12,3,200,owner),0));
    }
    @Test void changedQuantityWorkOwnerOutputOrDestinationCannotReplayOriginal(){
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(0,24,3,200,owner),0));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(0,12,2,200,owner),0));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(0,12,3,1,owner),0));
        assertFalse(SupplyFlowService.woodProductionIntent(request,scope,stock,order(0,12,3,200,SubjectRef.character(UUID.randomUUID())),0));
        var other=new FieldworkStorage(stock.dimension(),new BlockPos(1,0,0),UUID.randomUUID(),owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"other");
        assertFalse(SupplyFlowService.woodProductionIntent(request,new SupplyFlowData.Demand(owner,other.containerId()),other,order(0,12,3,200,owner),0));
        var changed=new NeedRequest(request.id(),new NeedRequest.Key(owner.id(),request.key().purpose(),new ItemFingerprint("minecraft:oak_planks",0),stock.containerId(),0,24000),24,24,0,0,0,NeedRequestStatus.OPEN,1,1,0,32,"");
        assertFalse(SupplyFlowService.woodProductionIntent(changed,scope,stock,order(0,12,3,200,owner),0));
    }
    @Test void continuationRequiresAckAndAccountsForReservedAndCarriedStock(){
        assertFalse(SupplyFlowService.woodContinuationReady(request,12,12,1,true));
        var after=request.reserve(12,2).dispatch(12,3).deliver(12,4);
        assertTrue(SupplyFlowService.woodContinuationReady(after,12,12,1,true));
        assertFalse(SupplyFlowService.woodContinuationReady(after,12,12,1,false));
        assertFalse(SupplyFlowService.woodContinuationReady(after.reserve(1,5),12,12,1,true));
        assertFalse(SupplyFlowService.woodContinuationReady(after.reserve(1,5).dispatch(1,6),12,12,1,true));
        assertFalse(SupplyFlowService.woodContinuationReady(after.resize(20,5),12,12,1,true));
    }
    @Test void missingWrongWorkerOrWrongNativeMaterialsAreNotCompletionProof(){
        var original=order(0,12,3,200,owner).claim(owner.id(),3,"original worker");
        assertFalse(SupplyFlowService.completedUnitMatches(original,null));
        var right=new ProductionBatch(original.batchId(),original.recipeId(),owner.id(),owner,stock.containerId(),original.inputs(),original.output(),200,4,300,ProductionBatchStatus.COMPLETED,"component receipt");
        assertTrue(SupplyFlowService.completedUnitMatches(original,right));
        var wrong=new ProductionBatch(original.batchId(),original.recipeId(),owner.id(),owner,stock.containerId(),List.of(m("oak_log",3)),original.output(),200,4,300,ProductionBatchStatus.COMPLETED,"different actual input");
        assertFalse(SupplyFlowService.completedUnitMatches(original,wrong));
        var orders=new ProductionOrderData();orders.put(order(0,12,3,200,owner));orders.put(original);
        var reloaded=ProductionOrderData.FACTORY.deserializer().apply(orders.save(new CompoundTag(),null),null);
        assertTrue(SupplyFlowService.woodProductionIntent(request,scope,stock,reloaded.byId(original.id()).orElseThrow(),0));
    }
    @Test void twentyFourReceiptsAccumulateExactlyOnceAcrossNbtAndRejectChangedReplay(){
        var data=new NeedRequestData();var r=data.openOrUpdate(request.key(),24,32,1);
        for(int index=0;index<2;index++){
            var id=SupplyFlowService.woodOrderId(r.id(),owner.id(),WorkshopTask.PLANKS,index);
            for(int replay=0;replay<2;replay++){
                long tick=100+index*10+replay;
                data.acknowledge(SupplyFlowService.receipt(id,"produced-reserve"),r.id(),NeedRequestData.Action.RESERVE,12,tick);
                data.acknowledge(SupplyFlowService.receipt(id,"produced-dispatch"),r.id(),NeedRequestData.Action.DISPATCH,12,tick);
                data.acknowledge(SupplyFlowService.receipt(id,"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,12,tick);
            }
            assertEquals((index+1)*12,data.request(r.id()).delivered());
        }
        var restored=NeedRequestData.load(data.save(new CompoundTag(),null),null);
        var last=SupplyFlowService.woodOrderId(r.id(),owner.id(),WorkshopTask.PLANKS,1);
        restored.acknowledge(SupplyFlowService.receipt(last,"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,12,120);
        assertThrows(IllegalStateException.class,()->restored.acknowledge(SupplyFlowService.receipt(last,"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,11,121));
        assertEquals(24,restored.request(r.id()).delivered());assertEquals(0,restored.request(r.id()).remaining());
        assertEquals(NeedRequestStatus.DELIVERED,restored.request(r.id()).status());
    }
}
