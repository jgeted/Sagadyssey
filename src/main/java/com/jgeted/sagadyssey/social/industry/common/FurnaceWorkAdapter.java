package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import java.util.*;
/** Existing order/material endpoints bind same-owner inputs and output. Native machine is the only converter. */
public final class FurnaceWorkAdapter implements NpcTaskExecutor {
 public static final Identifier ID=Identifier.parse("sagadyssey:industry_furnace");
 public static final String RECIPE="sagadyssey:industry_native_iron",BOUND_IRON="sagadyssey:industry_native_iron_bound",BOUND_POTATO="sagadyssey:industry_native_potato_bound";
 private record Spec(String raw,String output){}
 private static ItemFingerprint fp(String item){return new ItemFingerprint("minecraft:"+item,0);}
 private static Spec spec(String recipe){return switch(recipe){case RECIPE,BOUND_IRON->new Spec("raw_iron","iron_ingot");case BOUND_POTATO->new Spec("potato","baked_potato");default->null;};}
 public static boolean supports(ItemFingerprint item){return item.equals(fp("iron_ingot"))||item.equals(fp("baked_potato"));}
 public static boolean supportsStation(BlockEntity station, ItemFingerprint output) {
  if (!supports(output)) return false;
  if (station instanceof FurnaceBlockEntity) return true;
  return output.equals(fp("iron_ingot")) ? station instanceof BlastFurnaceBlockEntity
          : station instanceof SmokerBlockEntity;
 }
 public static boolean ownsStation(MinecraftServer server, String dimension, BlockPos position, SubjectRef actor) {
  return owned(RightsStateData.get(server), ProductionContainerIds.resolve(server, dimension, position), actor);
 }
 public static boolean recognized(ProductionOrder o){
  if(o==null)return false;var r=spec(o.recipeId());if(r==null||!o.output().fingerprint().equals(fp(r.output()))||o.output().quantity()<1||o.output().quantity()>8||o.requiredWorkTicks()!=40||!o.batchId().equals(IndustryTransfers.id(o.id()+":native-machine"))||o.outputOwner().type()!=SubjectRef.SubjectType.CHARACTER||!o.issuer().equals(o.outputOwner()))return false;
  var raw=new ProductionMaterial(fp(r.raw()),o.output().quantity());var fuel=new ProductionMaterial(fp("coal"),1);
  return o.inputs().equals(List.of(raw,fuel))||!o.recipeId().equals(RECIPE)&&o.inputs().equals(List.of(raw));
 }
 private static boolean bound(ProductionOrder o){return !o.recipeId().equals(RECIPE);}
 public static String rawItem(ItemFingerprint output){return output.equals(fp("iron_ingot"))?"minecraft:raw_iron":output.equals(fp("baked_potato"))?"minecraft:potato":null;}
 public static ProductionOrder request(NpcBase owner,String business,BlockPos furnace,FieldworkStorage stock,int quantity,long now){
  if(business==null||business.isBlank())throw new IllegalArgumentException("business identity required");
  return request(owner,IndustryTransfers.id("native-furnace:"+owner.getCharacterId()+":"+business),RECIPE,furnace,stock,stock,quantity,now);
 }
 public static ProductionOrder requestDemand(NpcBase owner,NeedRequest need,BlockPos furnace,FieldworkStorage input,FieldworkStorage output,long now){
  String recipe=need.key().item().equals(fp("iron_ingot"))?BOUND_IRON:need.key().item().equals(fp("baked_potato"))?BOUND_POTATO:null;
  if(recipe==null)throw new IllegalArgumentException("unsupported native product");
  return request(owner,SupplyFlowService.receipt(need.id(),"production-order"),recipe,furnace,input,output,need.remaining(),now);
 }
 private static ProductionOrder request(NpcBase owner,UUID id,String recipe,BlockPos furnace,FieldworkStorage input,FieldworkStorage output,int quantity,long now){
  if(quantity<1||quantity>8)throw new IllegalArgumentException("bounded native quantity required");
  var actor=SubjectRef.character(owner.getCharacterId());var r=spec(recipe);String dim=owner.level().dimension().identifier().toString();var s=MinecraftApi.server(owner);var orders=ProductionOrderData.get(s);var old=orders.byId(id).orElse(null);
  if(old!=null){
   if(!old.issuer().equals(actor)||!old.outputOwner().equals(actor)||!old.recipeId().equals(recipe)||!old.output().equals(new ProductionMaterial(fp(r.output()),quantity))||!Objects.equals(old.workstationDimension(),dim)||!Objects.equals(old.workstationPosition(),furnace)||!old.targetContainerId().equals(output.containerId())||!old.targetContainerDimension().equals(output.dimension())||!old.targetContainerPosition().equals(output.position()))throw new IllegalStateException("native request identity conflict");
   if(bound(old)&&!inputPoint(s,old).containerId().equals(input.containerId()))throw new IllegalStateException("native input identity conflict");return old;
  }
  if(!dim.equals(input.dimension())||!dim.equals(output.dimension())||!input.owner().equals(actor)||!output.owner().equals(actor)
          ||!owner.level().hasChunkAt(furnace)||!(owner.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f)
          ||!supportsStation(f, fp(r.output())))throw new IllegalArgumentException("known same-owner compatible native facility required");
  var rights=RightsStateData.get(s);UUID machine=ProductionContainerIds.resolve(s,dim,furnace);
  var materials=Set.of(fp(r.raw()),fp("coal"),fp(r.output()));
  if(!ownsMaterials(rights,input.containerId(),actor,materials)||!ownsMaterials(rights,output.containerId(),actor,materials)
          ||!owned(rights,machine,actor))throw new IllegalArgumentException("single uncontested owner required");
  if(orders.ordersForWorker(owner.getCharacterId()).stream().anyMatch(o->!o.terminal()&&recognized(o)&&furnace.equals(o.workstationPosition())))throw new IllegalStateException("native furnace already committed");
  var inputs=new ArrayList<ProductionMaterial>();inputs.add(new ProductionMaterial(fp(r.raw()),quantity));
  // Fixed existing coal may be reused. Current BurnTime alone is never a future fuel promise.
  if(recipe.equals(RECIPE)||!f.getItem(1).is(Items.COAL)||f.getItem(1).getCount()<1)inputs.add(new ProductionMaterial(fp("coal"),1));
  var proposed=ProductionOrder.open(id,actor,actor,recipe,inputs,new ProductionMaterial(fp(r.output()),quantity),40,IndustryTransfers.id(id+":native-machine"),dim,furnace,output.containerId(),output.dimension(),output.position(),now,"native conversion; actual coal and BurnTime authoritative");orders.put(proposed);
  if(bound(proposed))for(var m:inputs)ProductionMaterialRequestData.get(s).openOrGet(id,input.containerId(),m,now,"fixed native input endpoint; no inventory invented");
  var claimed=proposed.claim(owner.getCharacterId(),now,"original owner accepts native operation");orders.put(claimed);return claimed;
 }
 public static FieldworkStorage inputPoint(MinecraftServer s,ProductionOrder o){
  if(!bound(o))return outputPoint(s,o);
  var data=s.overworld().getDataStorage().get(ProductionMaterialRequestData.FACTORY.type(ProductionMaterialRequestData.DATA_NAME));
  if(data==null)throw new IllegalStateException("native input binding missing");var refs=data.forOrder(o.id());
  if(refs.size()!=o.inputs().size()||refs.stream().map(ProductionMaterialRequest::containerId).distinct().count()!=1||o.inputs().stream().anyMatch(m->refs.stream().noneMatch(x->x.material().equals(m))))throw new IllegalStateException("native input binding incomplete/conflicting");
  var point=SupplyFlowData.get(s).point(refs.getFirst().containerId());if(!point.owner().equals(o.outputOwner())||!point.dimension().equals(o.targetContainerDimension()))throw new IllegalStateException("native input title changed");return point;
 }
 private static FieldworkStorage outputPoint(MinecraftServer s,ProductionOrder o){var c=RightsStateData.get(s).context(o.targetContainerId());return new FieldworkStorage(o.targetContainerDimension(),o.targetContainerPosition(),o.targetContainerId(),o.outputOwner(),c==null?AccessPolicy.PRIVATE:c.accessPolicy(),TransferIntent.DELIVERY,o.token());}
 private static int coal(ProductionOrder o){return o.inputs().stream().filter(m->m.fingerprint().equals(fp("coal"))).mapToInt(ProductionMaterial::quantity).sum();}
 private static List<TransferLeg> legs(ProductionOrder o,UUID from,UUID to,boolean inputs){var r=spec(o.recipeId());var list=new ArrayList<TransferLeg>();list.add(new TransferLeg(from,to,fp(inputs?r.raw():r.output()),o.output().quantity()));if(inputs&&coal(o)>0)list.add(new TransferLeg(from,to,fp("coal"),coal(o)));return List.copyOf(list);}
 public static void reservePendingInputs(MinecraftServer s,long now){for(var l:s.getAllLevels())for(var e:l.getAllEntities())if(e instanceof NpcBase n&&EntityResourceTask.enabled(n))for(var o:ProductionOrderData.get(s).assignedTo(n.getCharacterId()))if(recognized(o)&&o.status()==ProductionOrderStatus.CLAIMED){try{reserve(s,o,now);}catch(IllegalStateException|NoSuchElementException bad){review(ProductionOrderData.get(s),o,now,bad.getMessage());}}}
 private static void reserve(MinecraftServer s,ProductionOrder o,long now){
  var point=inputPoint(s,o);var box=SupplyFlowService.container(s,point);if(box==null||!SupplyFlowService.validOwner(s,point))return;var rights=RightsStateData.get(s);
  for(var m:o.inputs()){
   int physical=SupplyFlowService.count(box,m.fingerprint());int own=rights.reservationsForToken(o.token(),now).stream().filter(x->x.containerId().equals(point.containerId())&&x.fingerprint().equals(m.fingerprint())).mapToInt(AssetReservation::quantity).sum();
   if(own>physical)throw new IllegalStateException("native promised input changed");int q=Math.min(m.quantity(),SupplyFlowService.available(s,point,o.outputOwner(),m.fingerprint(),now)+own);
   if(q>0)rights.reserve(new AssetReservation(IndustryTransfers.id(o.id()+":input:"+m.fingerprint()),point.containerId(),m.fingerprint(),q,o.outputOwner(),o.token(),"accepted native input",now,Long.MAX_VALUE),physical,now);
  }
 }
 private static boolean ready(MinecraftServer s,ProductionOrder o,FieldworkStorage stock,long now){var rights=RightsStateData.get(s);return o.inputs().stream().allMatch(m->SupplyFlowService.available(s,stock,o.outputOwner(),m.fingerprint(),now)+rights.reservationsForToken(o.token(),now).stream().filter(x->x.containerId().equals(stock.containerId())&&x.fingerprint().equals(m.fingerprint())).mapToInt(AssetReservation::quantity).sum()>=m.quantity());}
 public static Optional<NpcPlan> choose(NpcBase n,long now){
  if(!EntityResourceTask.enabled(n)||n.getProfession()!=NpcProfession.WORKER||n.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();var orders=ProductionOrderData.get(MinecraftApi.server(n));
  for(var o:orders.assignedTo(n.getCharacterId())){
   if(!recognized(o)||o.status()==ProductionOrderStatus.BLOCKED
           ||!n.level().dimension().identifier().toString().equals(o.workstationDimension()))continue;
   if(!n.level().hasChunkAt(o.workstationPosition())||!(n.level().getBlockEntity(o.workstationPosition()) instanceof AbstractFurnaceBlockEntity f)||!supportsStation(f,o.output().fingerprint()))continue;
   try{var in=inputPoint(MinecraftApi.server(n),o);if(SupplyFlowService.container(MinecraftApi.server(n),in)==null)continue;String action;
    if(o.status()==ProductionOrderStatus.CLAIMED){reserve(MinecraftApi.server(n),o,now);if(!ready(MinecraftApi.server(n),o,in,now)||!f.getItem(0).isEmpty()||!f.getItem(2).isEmpty()||(!f.getItem(1).isEmpty()&&!f.getItem(1).is(Items.COAL)))continue;action="LOAD";}
    else if(o.status()==ProductionOrderStatus.RESERVED)action="LOAD";
    else if(o.status()==ProductionOrderStatus.IN_PROGRESS||o.status()==ProductionOrderStatus.SETTLING){if(o.status()==ProductionOrderStatus.IN_PROGRESS&&(!fp(spec(o.recipeId()).output()).matches(f.getItem(2))||f.getItem(2).getCount()<o.output().quantity()))continue;action="UNLOAD";}else continue;
    return Optional.of(new NpcPlan(UUID.randomUUID(),n.getUUID(),"native-furnace:"+o.id()+":"+action,ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("order",o.id().toString(),"action",action),NpcPlanStepStatus.PENDING,now,"native furnace handoff")),0,now,now,now+40,now+12000,null,"actual machine processing; actual NPC load/unload"));
   }catch(IllegalStateException|NoSuchElementException bad){review(orders,o,now,bad.getMessage());}
  }return Optional.empty();
 }
 public Identifier id(){return ID;}
 public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep st){return tick(c,p,st);}
 public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep st){return tick(c,p,st);}
 public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep st,PlanCancellationReason why){if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}
 public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
  if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("original operator required");var n=c.npc();var s=MinecraftApi.server(n);var orders=ProductionOrderData.get(s);var o=orders.byId(UUID.fromString(step.params().get("order"))).orElse(null);
  if(o==null)return TaskTickResult.failed("original native order missing");if(o.status()==ProductionOrderStatus.COMPLETED)return TaskTickResult.stepSucceeded("native order already complete");
  if(!recognized(o)||!Objects.equals(o.assignedWorkerId(),n.getCharacterId())
          ||!o.outputOwner().equals(SubjectRef.character(n.getCharacterId())))return review(orders,o,c.gameTime(),"recipe/operator/title mismatch");
  if(n.getCommand()!=NpcCommand.WORK||!NpcSchedulePlanner.allowsWork(n))return waiting("original work conditions unavailable",c.gameTime());
  if(!c.level().dimension().identifier().toString().equals(o.workstationDimension())
          ||!c.level().hasChunkAt(o.workstationPosition())||!(c.level().getBlockEntity(o.workstationPosition()) instanceof AbstractFurnaceBlockEntity furnace)
          ||!supportsStation(furnace,o.output().fingerprint()))return waiting("original compatible machine unavailable",c.gameTime());
  FieldworkStorage input;try{input=inputPoint(s,o);}catch(IllegalStateException|NoSuchElementException bad){return review(orders,o,c.gameTime(),bad.getMessage());}
  var output=outputPoint(s,o);var in=SupplyFlowService.container(s,input);var out=SupplyFlowService.container(s,output);if(in==null||out==null)return waiting("original store unloaded",c.gameTime());
  var actor=o.outputOwner();var rights=RightsStateData.get(s);var bag=ContainerIds.npcPersonal(n.getCharacterId());var machine=ProductionContainerIds.resolve(s,output.dimension(),o.workstationPosition());
  var spec=spec(o.recipeId());
  var materials=Set.of(fp(spec.raw()),fp("coal"),fp(spec.output()));
  if(!owned(rights,machine,actor)||!ownsMaterials(rights,input.containerId(),actor,materials)
          ||!ownsMaterials(rights,output.containerId(),actor,materials)||!ownsMaterials(rights,bag,actor,materials))
      return review(orders,o,c.gameTime(),"mixed/unknown native title");
  var take=legs(o,input.containerId(),bag,true);var load=legs(o,bag,machine,true);var collect=legs(o,machine,bag,false);var deliver=legs(o,bag,output.containerId(),false);
  var ends=new HashMap<UUID,TransferEndpoint>();ends.put(input.containerId(),new TransferEndpoint(input.containerId(),in));ends.put(output.containerId(),new TransferEndpoint(output.containerId(),out));ends.put(bag,new TransferEndpoint(bag,n.getPersonalInventory()));ends.put(machine,new TransferEndpoint(machine,furnace));
  if(step.params().get("action").equals("LOAD")){
   var loaded=IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":load"),actor,TransferIntent.DELIVERY,load,o.token());if(loaded.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),loaded.detail());if(loaded.state()==IndustryTransfers.State.SUCCESS){finishLoad(orders,o,c.gameTime());return TaskTickResult.stepSucceeded("original load confirmed");}
   var taken=IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":take"),actor,TransferIntent.DELIVERY,take,o.token());if(taken.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),taken.detail());
   if(taken.state()!=IndustryTransfers.State.SUCCESS){
    if(o.status()==ProductionOrderStatus.RESERVED&&taken.receipt()==null)return review(orders,o,c.gameTime(),"take result uncertain after recovery");
    if(!near(n,input.position())){move(n,input.position());return TaskTickResult.running("walking to original inputs");}
    reserve(s,o,c.gameTime());if(!ready(s,o,input,c.gameTime()))return waiting("legal material competition",c.gameTime());
    if(ProductionLaborData.get(s).tick(o.id(),o.requiredWorkTicks(),c.gameTime())<o.requiredWorkTicks())return TaskTickResult.running("actual loading labour");
    if(o.status()==ProductionOrderStatus.CLAIMED){o=o.transition(ProductionOrderStatus.RESERVED,c.gameTime(),"native take fixed");orders.put(o);}
    taken=IndustryTransfers.once(s,IndustryTransfers.id(o.id()+":take"),actor,TransferIntent.DELIVERY,take,ends,o.token(),c.gameTime());if(taken.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),taken.detail());if(taken.state()!=IndustryTransfers.State.SUCCESS)return waiting(taken.detail(),c.gameTime());
   }
   for(var m:o.inputs())rights.reserve(new AssetReservation(IndustryTransfers.id(o.id()+":bag:"+m.fingerprint()),bag,m.fingerprint(),m.quantity(),actor,o.token(),"original carried native inputs",c.gameTime(),Long.MAX_VALUE),n.getPersonalInventory().count(m.fingerprint()::matches),c.gameTime());
   for(var r:ProductionMaterialRequestData.get(s).forOrder(o.id()))if(r.status()==ProductionMaterialRequest.Status.OPEN)ProductionMaterialRequestData.get(s).fulfill(r.idempotencyKey(),c.gameTime(),"original inputs actually held by native operator");
   if(!near(n,o.workstationPosition())){move(n,o.workstationPosition());return TaskTickResult.running("carrying inputs to original machine");}
   if(!furnace.getItem(0).isEmpty()||!furnace.getItem(2).isEmpty()||(!furnace.getItem(1).isEmpty()&&!furnace.getItem(1).is(Items.COAL)))return waiting("machine occupied; retain original cargo",c.gameTime());
   if(coal(o)==0&&(!furnace.getItem(1).is(Items.COAL)||furnace.getItem(1).getCount()<1))return review(orders,o,c.gameTime(),"fixed reused coal disappeared");
   loaded=IndustryTransfers.once(s,IndustryTransfers.id(o.id()+":load"),actor,TransferIntent.DELIVERY,load,ends,o.token(),c.gameTime());if(loaded.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),loaded.detail());if(loaded.state()!=IndustryTransfers.State.SUCCESS)return waiting(loaded.detail(),c.gameTime());finishLoad(orders,o,c.gameTime());return TaskTickResult.stepSucceeded("actual native conversion started");
  }
  var delivered=IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":deliver"),actor,TransferIntent.DELIVERY,deliver,o.token());if(delivered.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),delivered.detail());if(delivered.state()==IndustryTransfers.State.SUCCESS){finish(orders,o,c.gameTime());return TaskTickResult.stepSucceeded("original output delivered");}
  var picked=IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":collect"),actor,TransferIntent.DELIVERY,collect,o.token());if(picked.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),picked.detail());
  if(picked.state()!=IndustryTransfers.State.SUCCESS){
   if(o.status()==ProductionOrderStatus.SETTLING&&picked.receipt()==null)return review(orders,o,c.gameTime(),"collect uncertain after recovery");
   if(!near(n,o.workstationPosition())){move(n,o.workstationPosition());return TaskTickResult.running("walking to actual output");}
   if(!fp(spec(o.recipeId()).output()).matches(furnace.getItem(2))||furnace.getItem(2).getCount()<o.output().quantity())return waiting("native output not ready",c.gameTime());
   if(o.status()==ProductionOrderStatus.IN_PROGRESS){o=o.transition(ProductionOrderStatus.SETTLING,c.gameTime(),"native collect fixed");orders.put(o);}
   picked=IndustryTransfers.once(s,IndustryTransfers.id(o.id()+":collect"),actor,TransferIntent.DELIVERY,collect,ends,o.token(),c.gameTime());if(picked.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),picked.detail());if(picked.state()!=IndustryTransfers.State.SUCCESS)return waiting(picked.detail(),c.gameTime());
  }
  if(!near(n,output.position())){move(n,output.position());return TaskTickResult.running("carrying actual output to bound store");}
  delivered=IndustryTransfers.once(s,IndustryTransfers.id(o.id()+":deliver"),actor,TransferIntent.DELIVERY,deliver,ends,o.token(),c.gameTime());if(delivered.state()==IndustryTransfers.State.REVIEW)return review(orders,o,c.gameTime(),delivered.detail());if(delivered.state()!=IndustryTransfers.State.SUCCESS)return waiting(delivered.detail(),c.gameTime());finish(orders,o,c.gameTime());return TaskTickResult.stepSucceeded("real native output deposited");
 }
 public static boolean completedDelivery(MinecraftServer s,ProductionOrder o){
  if(o.status()!=ProductionOrderStatus.COMPLETED||!recognized(o)||o.assignedWorkerId()==null)return false;
  try{var input=inputPoint(s,o);var actor=o.outputOwner();var bag=ContainerIds.npcPersonal(o.assignedWorkerId());var machine=ProductionContainerIds.resolve(s,o.workstationDimension(),o.workstationPosition());
   return IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":take"),actor,TransferIntent.DELIVERY,legs(o,input.containerId(),bag,true),o.token()).state()==IndustryTransfers.State.SUCCESS&&IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":load"),actor,TransferIntent.DELIVERY,legs(o,bag,machine,true),o.token()).state()==IndustryTransfers.State.SUCCESS&&IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":collect"),actor,TransferIntent.DELIVERY,legs(o,machine,bag,false),o.token()).state()==IndustryTransfers.State.SUCCESS&&IndustryTransfers.confirmed(s,IndustryTransfers.id(o.id()+":deliver"),actor,TransferIntent.DELIVERY,legs(o,bag,o.targetContainerId(),false),o.token()).state()==IndustryTransfers.State.SUCCESS;
  }catch(IllegalStateException|NoSuchElementException missing){return false;}
 }
 private static boolean owned(RightsStateData r,UUID id,SubjectRef owner){return r.context(id)!=null&&r.context(id).defaultBeneficialOwner().equals(owner)&&r.claims().stream().noneMatch(c->c.containerId().equals(id));}
 private static boolean ownsMaterials(RightsStateData rights, UUID container, SubjectRef owner,
                                      Set<ItemFingerprint> materials) {
  var context=rights.context(container);
  return context!=null&&context.defaultBeneficialOwner().equals(owner)
          &&rights.claims().stream().filter(c->c.containerId().equals(container)&&materials.contains(c.fingerprint()))
          .allMatch(c->c.beneficialOwner().equals(owner)&&c.custodian().equals(owner)&&c.encumbrances().isEmpty());
 }
 private static boolean near(NpcBase n,BlockPos p){return FieldworkRoutes.closeEnough(n,p);}
 private static void move(NpcBase n,BlockPos p){var route=FieldworkRoutes.plan(n,p,false);if(route!=null)n.getNavigation().moveTo(route.stand().getX()+.5,route.stand().getY(),route.stand().getZ()+.5,1.1);}
 private static void finishLoad(ProductionOrderData orders,ProductionOrder o,long now){if(o.status()==ProductionOrderStatus.CLAIMED){o=o.transition(ProductionOrderStatus.RESERVED,now,"existing load receipt");orders.put(o);}if(o.status()==ProductionOrderStatus.RESERVED)orders.put(o.transition(ProductionOrderStatus.IN_PROGRESS,now,"actual native inputs loaded"));}
 private static void finish(ProductionOrderData orders,ProductionOrder o,long now){if(o.status()==ProductionOrderStatus.IN_PROGRESS){o=o.transition(ProductionOrderStatus.SETTLING,now,"existing delivery");orders.put(o);}orders.put(o.transition(ProductionOrderStatus.COMPLETED,now,"native output delivered;physical fuel stays in machine"));}
 private static TaskTickResult review(ProductionOrderData orders,ProductionOrder o,long now,String detail){if(o.status()!=ProductionOrderStatus.BLOCKED)orders.put(o.block(new ProductionOrderBlocker("NATIVE_REVIEW",detail,Long.MAX_VALUE),now,detail));return TaskTickResult.failed("native REVIEW: "+detail);}
 private static TaskTickResult waiting(String detail,long now){return TaskTickResult.blocked(detail,new NpcPlanBlocker("NATIVE_FURNACE_WAIT",detail,now+100,null));}
}
