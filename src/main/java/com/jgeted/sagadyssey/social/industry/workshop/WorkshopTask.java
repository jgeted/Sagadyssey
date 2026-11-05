package com.jgeted.sagadyssey.social.industry.workshop;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Exact existing growth planks/sticks/bed recipes; original qualified worker, original batch/labor/rights. */
public final class WorkshopTask implements NpcTaskExecutor {
    public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:industry_workshop");
    public static final String BED="sagadyssey:industry_growth_bed";
    public static final String PLANKS="sagadyssey:industry_growth_planks";
    public static final String STICKS="sagadyssey:industry_growth_sticks";
    private record Recipe(String id,String identityPrefix,List<ProductionMaterial> inputs,ProductionMaterial output,int work){}
    private static ProductionMaterial material(String id,int quantity){return new ProductionMaterial(new ItemFingerprint("minecraft:"+id,0),quantity);}
    private static Recipe recipe(String id){
        return switch(id){
            case BED -> new Recipe(BED,"industry-bed",List.of(material("oak_planks",3),material("white_wool",3)),material("white_bed",1),400);
            case PLANKS -> new Recipe(PLANKS,"industry-planks",List.of(material("oak_log",3)),material("oak_planks",12),200);
            case STICKS -> new Recipe(STICKS,"industry-sticks",List.of(material("oak_planks",2)),material("stick",4),200);
            default -> null;
        };
    }
    public static ProductionOrder requestBed(NpcBase owner,String business,FieldworkStorage stock,BlockPos table,long now){return request(owner,business,stock,table,recipe(BED),now);}
    public static ProductionOrder requestPlanks(NpcBase owner,String business,FieldworkStorage stock,BlockPos table,long now){return request(owner,business,stock,table,recipe(PLANKS),now);}
    public static ProductionOrder requestSticks(NpcBase owner,String business,FieldworkStorage stock,BlockPos table,long now){return request(owner,business,stock,table,recipe(STICKS),now);}
    private static ProductionOrder request(NpcBase owner,String business,FieldworkStorage stock,BlockPos table,Recipe spec,long now){
        if(business==null||business.isBlank())throw new IllegalArgumentException("business identity required");
        var principal=SubjectRef.character(owner.getCharacterId());var id=IndustryTransfers.id(spec.identityPrefix()+":"+owner.getCharacterId()+":"+business);
        var orders=ProductionOrderData.get(owner.getServer());var old=orders.byId(id).orElse(null);
        if(old!=null){
            if(!old.issuer().equals(principal)||!old.outputOwner().equals(principal)||!old.recipeId().equals(spec.id())
                    ||!old.inputs().equals(spec.inputs())||!old.output().equals(spec.output())||old.requiredWorkTicks()!=spec.work()
                    ||!old.batchId().equals(IndustryTransfers.id(id+":batch"))||!old.targetContainerId().equals(stock.containerId())
                    ||!old.targetContainerDimension().equals(stock.dimension())||!old.targetContainerPosition().equals(stock.position())
                    ||!Objects.equals(old.workstationDimension(),stock.dimension())||!Objects.equals(old.workstationPosition(),table))
                throw new IllegalStateException("workshop request identity conflict");
            return old;
        }
        if(!stock.owner().equals(principal)||!owner.level().dimension().location().toString().equals(stock.dimension())
                ||!owner.level().hasChunkAt(table)||!owner.level().getBlockState(table).is(Blocks.CRAFTING_TABLE))
            throw new IllegalArgumentException("owned stock and known actual crafting table required");
        var o=ProductionOrder.open(id,principal,principal,spec.id(),spec.inputs(),spec.output(),spec.work(),IndustryTransfers.id(id+":batch"),stock.dimension(),table,stock.containerId(),stock.dimension(),stock.position(),now,"reuse exact existing growth recipe, no new costs");orders.put(o);
        for(var material:spec.inputs())ProductionMaterialRequestData.get(owner.getServer()).openOrGet(o.id(),stock.containerId(),material,now,"existing wood recipe input requirement");
        return o;
    }
    public static boolean recognized(ProductionOrder o){var spec=recipe(o.recipeId());return spec!=null&&o.inputs().equals(spec.inputs())&&o.output().equals(spec.output())&&o.requiredWorkTicks()==spec.work();}
    public static Optional<NpcPlan> choose(NpcBase n,long now){
        if(!EntityResourceTask.enabled(n)||n.getProfession()!=NpcProfession.WORKER||n.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();
        var nativeFacts=com.jgeted.sagadyssey.social.industry.common.EntityResourceActionData.existing(n.getServer());
        if(nativeFacts!=null && nativeFacts.sources().stream().anyMatch(src->{var need=com.jgeted.sagadyssey.social.needs.NeedRequestData.get(n.getServer()).request(src.getUUID("Need"));return src.getUUID("Actor").equals(n.getCharacterId()) && need!=null && need.remaining()>0;}))return Optional.empty();
        var orders=ProductionOrderData.get(n.getServer());var list=new ArrayList<>(orders.assignedTo(n.getCharacterId()));list.addAll(orders.openOrders());
        for(var order:list){
            if(!recognized(order)||order.status()==ProductionOrderStatus.BLOCKED||!mayWork(n,order))continue;
            if(!n.level().hasChunkAt(order.workstationPosition())||!n.level().getBlockState(order.workstationPosition()).is(Blocks.CRAFTING_TABLE))continue;
            var store=storage(order);var box=SupplyFlowService.container(n.getServer(),store);if(box==null)continue;
            if(order.status()==ProductionOrderStatus.OPEN){
                var owner=order.outputOwner();
                if(order.inputs().stream().anyMatch(m->SupplyFlowService.available(n.getServer(),store,owner,m.fingerprint(),now)<m.quantity()))continue;
                order=order.claim(n.getCharacterId(),now,"worker accepts existing carpentry recipe");orders.put(order);
            }
            var step=new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("order",order.id().toString()),NpcPlanStepStatus.PENDING,now,"actual workshop work");
            return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"industry-workshop:"+order.id(),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(step),0,now,now,now+40,now+12000,null,"same production batch, original material reservations and labour ledger"));
        }
        return Optional.empty();
    }
    public ResourceLocation id(){return ID;}
    public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason why){if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}
    public TaskTickResult tick(NpcExecutionContext raw,NpcPlan p,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("original workshop body required");
        var n=c.npc();var orders=ProductionOrderData.get(n.getServer());var o=orders.byId(UUID.fromString(step.params().get("order"))).orElse(null);
        if(o==null)return TaskTickResult.failed("missing workshop order, no recreation");
        if(o.status()==ProductionOrderStatus.COMPLETED)return TaskTickResult.stepSucceeded("original batch already completed");
        if(!mayWork(n,o)||n.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(n))return waitFor("original authority or schedule unavailable",c.gameTime());
        if(!c.level().getBlockState(o.workstationPosition()).is(Blocks.CRAFTING_TABLE))return waitFor("actual workbench unavailable",c.gameTime());
        if(!FieldworkRoutes.closeEnough(n,o.workstationPosition())){var route=FieldworkRoutes.plan(n,o.workstationPosition(),false);if(route!=null)n.getNavigation().moveTo(route.stand().getX()+.5,route.stand().getY(),route.stand().getZ()+.5,1.1);return TaskTickResult.running("walking to actual crafting table");}
        var stock=storage(o);var box=SupplyFlowService.container(n.getServer(),stock);if(box==null)return waitFor("owned workshop stock unloaded",c.gameTime());
        try{
            var batch=ProductionService.begin(n.getServer(),o.batchId(),n.getCharacterId(),o.outputOwner(),o.targetContainerId(),box,o.recipeId(),o.inputs(),o.output(),o.requiredWorkTicks(),c.gameTime());
            if(o.status()==ProductionOrderStatus.CLAIMED){o=o.transition(ProductionOrderStatus.RESERVED,c.gameTime(),"original materials really reserved");orders.put(o);}
            if(o.status()==ProductionOrderStatus.RESERVED){o=o.transition(ProductionOrderStatus.IN_PROGRESS,c.gameTime(),"original worker begins real nearby labour");orders.put(o);}
            if(batch.status()!=ProductionBatchStatus.COMPLETED){
                int actual=ProductionLaborData.get(n.getServer()).tick(o.id(),o.requiredWorkTicks(),c.gameTime());n.playAction(NpcAction.HARVEST,10);
                if(actual<o.requiredWorkTicks())return TaskTickResult.running("actual nearby carpentry labour "+actual);
                batch=ProductionService.complete(n.getServer(),o.batchId(),box,actual,c.gameTime());
            }
            if(batch.status()!=ProductionBatchStatus.COMPLETED)return waitFor("original batch not complete",c.gameTime());
            if(o.status()==ProductionOrderStatus.IN_PROGRESS){o=o.transition(ProductionOrderStatus.SETTLING,c.gameTime(),"real product and original batch receipt");orders.put(o);}
            orders.put(o.transition(ProductionOrderStatus.COMPLETED,c.gameTime(),"existing wood recipe physically manufactured"));
            return TaskTickResult.stepSucceeded("real product in original owned stock");
        }catch(IllegalStateException failure){return waitFor("materials/capacity conflict: "+failure.getMessage(),c.gameTime());}
    }
    private static boolean mayWork(NpcBase n,ProductionOrder o){return o.outputOwner().equals(SubjectRef.character(n.getCharacterId()))
            || o.outputOwner().type()==SubjectRef.SubjectType.CHARACTER && o.outputOwner().id().equals(NpcWorkContext.from(n).employerCharacterId());}
    private static FieldworkStorage storage(ProductionOrder o){return new FieldworkStorage(o.targetContainerDimension(),o.targetContainerPosition(),o.targetContainerId(),o.outputOwner(),AccessPolicy.PRIVATE,com.jgeted.sagadyssey.worldsim.transfer.TransferIntent.DELIVERY,o.token());}
    private static TaskTickResult waitFor(String detail,long now){return TaskTickResult.blocked(detail,new NpcPlanBlocker("WORKSHOP_WAIT",detail,now+100,null));}
}
