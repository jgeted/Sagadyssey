package com.jgeted.sagadyssey.social.industry.common;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;
/** Controlled opt-in only. Original stock/transfer ledger, original body, closed named tools. */
public final class IndustryToolTask implements NpcTaskExecutor {
 public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:industry_tool");
 public static boolean supported(String item){return Set.of("minecraft:iron_axe","minecraft:iron_hoe","minecraft:shears").contains(item);}
 private static boolean usable(NpcBase n,ItemStack stack,int slot,String item,long now){
  if(!stack.isEmpty()&&ItemFingerprint.of(stack).itemId().equals(item)&&(!stack.isDamageableItem()||stack.getDamageValue()<stack.getMaxDamage())){
   var rights=RightsStateData.get(n.getServer());var bag=ContainerIds.npcPersonal(n.getCharacterId());var owner=SubjectRef.character(n.getCharacterId());var fp=ItemFingerprint.of(stack);
   return rights.context(bag)!=null&&rights.context(bag).defaultBeneficialOwner().equals(owner)&&rights.claimsAt(bag,slot,fp).isEmpty()&&rights.reservedQuantity(bag,fp,null,now)==0&&new RightsService(rights).can(owner,AssetRight.USE,bag,slot,fp,now).status()==RightsVerdict.Status.ALLOW;
  }return false;
 }
 private static String wanted(NpcBase n){
  var data=EntityResourceActionData.existing(n.getServer());
  if(data!=null&&n.getProfession()==NpcProfession.WORKER){
   if(data.actions().stream().anyMatch(a->a.getUUID("Person").equals(n.getCharacterId())&&!Set.of("DONE","REVIEW").contains(a.getString("State"))))return null;
   for(var src:data.sources())if(src.getUUID("Actor").equals(n.getCharacterId())&&src.getString("Kind").equals("SHEAR")){
    var out=FieldworkStorage.load(src.getCompound("Output"));
    for(var req:NeedRequestData.get(n.getServer()).requests())if(!req.status().terminal()&&req.status()!=NeedRequestStatus.DELIVERED&&req.remaining()>0&&req.key().item().equals(new ItemFingerprint("minecraft:white_wool",0))){
     var scope=SupplyFlowData.get(n.getServer()).demand(req.id());if(scope!=null&&SupplyFlowData.get(n.getServer()).known(scope.principal()).contains(out.containerId()))return "minecraft:shears";
    }
   }
  }
  if(!n.hasWorkZone())return null;var min=n.getWorkZoneMin();var max=n.getWorkZoneMax();
  // Bounded known work plots only; never use a global or unloaded resource scan.
  if((long)(max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1)>2048)return null;
  for(var pos:net.minecraft.core.BlockPos.betweenClosed(min,max)){
   if(!n.level().hasChunkAt(pos))continue;var target=FieldworkDiscovery.inspect(n,pos);if(target==null)continue;
   if(target.operation()==FieldworkAction.Operation.CHOP&&target.before().is(net.minecraft.tags.BlockTags.LOGS))return "minecraft:iron_axe";
   if(target.operation()==FieldworkAction.Operation.TILL)return "minecraft:iron_hoe";
  }return null;
 }
 private static FieldworkStorage stock(NpcBase n){
  var s=n.getServer();var owner=SubjectRef.character(n.getCharacterId());var life=NpcLifeData.get(s).record(n.getCharacterId());var rights=RightsStateData.get(s);String dim=n.level().dimension().location().toString();
  if(life!=null&&life.privateStoragePosition()!=null&&life.dimension().equals(dim)&&n.level().hasChunkAt(life.privateStoragePosition())){
   var pos=life.privateStoragePosition();var id=ProductionContainerIds.resolve(s,dim,pos);var c=rights.context(id);
   if(c!=null&&c.defaultBeneficialOwner().equals(owner))return new FieldworkStorage(dim,pos,id,owner,c.accessPolicy(),TransferIntent.DELIVERY,"industry-tool-own");
  }
  var home=HouseholdData.get(s).forMember(n.getCharacterId());if(home==null||home.sharedStorage()==null||!home.dimension().equals(dim))return null;
  var id=ContainerIds.block("household_storage",dim,home.sharedStorage());var c=rights.context(id);if(c==null||!c.defaultBeneficialOwner().equals(SubjectRef.household(home.id())))return null;
  return new FieldworkStorage(dim,home.sharedStorage(),id,SubjectRef.household(home.id()),c.accessPolicy(),TransferIntent.GIFT,"industry-tool-household");
 }
 public static Optional<NpcPlan> choose(NpcBase n,long now){
  if(!EntityResourceTask.enabled(n)||n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();String item=wanted(n);if(item==null)return Optional.empty();
  var inventory=n.getPersonalInventory();if(usable(n,n.getMainHandItem(),inventory.getContainerSize(),item,now))return Optional.empty();
  for(int slot=0;slot<inventory.getContainerSize();slot++)if(usable(n,inventory.getItem(slot),slot,item,now))return Optional.of(plan(n,item,null,null,now));
  var point=stock(n);if(point==null)return Optional.empty();var flows=SupplyFlowData.get(n.getServer());flows.register(point);var owner=SubjectRef.character(n.getCharacterId());flows.reveal(owner,point.containerId());
  var fp=new ItemFingerprint(item,0);
  // The declared one-tool buffer is a real obligation; normal supply/manufacture fulfils it.
  flows.policy(new SupplyFlowData.Policy("industry-tool:"+n.getCharacterId()+":"+item,point.owner(),point.containerId(),fp,1,"workshop"));
  var need=NeedRequestData.get(n.getServer()).requests().stream().filter(r->r.key().destination().equals(point.containerId())&&r.key().item().equals(fp)&&r.key().purpose().contains("industry-tool:"+n.getCharacterId()+":"+item)).max(Comparator.comparingLong(NeedRequest::createdAt)).orElse(null);
  if(need==null)return Optional.empty();return Optional.of(plan(n,item,point,need.id(),now));
 }
 private static NpcPlan plan(NpcBase n,String item,FieldworkStorage source,UUID need,long now){
  var params=new HashMap<String,String>();params.put("item",item);if(source!=null){params.put("source",source.containerId().toString());params.put("need",need.toString());}
  return new NpcPlan(UUID.randomUUID(),n.getUUID(),"industry-tool:"+n.getCharacterId()+":"+item,ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,params,NpcPlanStepStatus.PENDING,now,"original worker obtains named tool")),0,now,now,now+40,Long.MAX_VALUE,null,"existing title, original material obligation and transfer receipt");
 }
 public ResourceLocation id(){return ID;}public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason why){if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}
 public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
  if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("original tool user required");var n=c.npc();var s=n.getServer();long now=c.gameTime();String item=step.params().get("item");if(!supported(item))return TaskTickResult.failed("undeclared tool");
  if(n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null||!NpcSchedulePlanner.allowsWork(n))return waitFor("work condition unavailable",now);
  var inventory=n.getPersonalInventory();if(usable(n,n.getMainHandItem(),inventory.getContainerSize(),item,now))return TaskTickResult.stepSucceeded("original usable tool already equipped");
  for(int slot=0;slot<inventory.getContainerSize();slot++)if(usable(n,inventory.getItem(slot),slot,item,now)){
   // Do not overwrite another held item or move a contested/reserved item to a different slot.
   if(!n.getMainHandItem().isEmpty())return waitFor("hand occupied; retain both items",now);
   var tool=inventory.removeItem(slot,1);n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,tool);RightsStateData.get(s).bumpContext(ContainerIds.npcPersonal(n.getCharacterId()));
   return TaskTickResult.stepSucceeded("same owned tool actually equipped, no item created");
  }
  if(!step.params().containsKey("source"))return TaskTickResult.failed("original own item no longer available");
  var point=SupplyFlowData.get(s).point(UUID.fromString(step.params().get("source")));var original=stock(n);if(original==null||!original.containerId().equals(point.containerId()))return waitFor("original facility/title no longer valid",now);
  var box=SupplyFlowService.container(s,point);if(box==null)return waitFor("original source unavailable",now);var actor=SubjectRef.character(n.getCharacterId());var bag=ContainerIds.npcPersonal(n.getCharacterId());var fp=new ItemFingerprint(item,0);String token="industry-tool:"+step.params().get("need");var root=IndustryTransfers.id(token+":"+point.containerId()+":"+n.getCharacterId());var legs=List.of(new TransferLeg(point.containerId(),bag,fp,1));
  var old=IndustryTransfers.confirmed(s,root,actor,point.intent(),legs,token);if(old.state()==IndustryTransfers.State.REVIEW)return review(old.detail());if(old.state()==IndustryTransfers.State.SUCCESS)return review("original tool transfer succeeded but item absent/unusable; retain receipt");
  if(SupplyFlowService.available(s,point,actor,fp,now)<1)return waitFor("await original legal named-tool supply",now);
  var service=new RightsService(RightsStateData.get(s));boolean use=false;for(int i=0;i<box.getContainerSize();i++)if(fp.matches(box.getItem(i))&&service.can(actor,AssetRight.USE,point.containerId(),i,fp,now).status()==RightsVerdict.Status.ALLOW){use=true;break;}if(!use)return waitFor("no original tool-use permission",now);
  if(!FieldworkRoutes.closeEnough(n,point.position())){FieldworkRoutes.move(n,point.position());return TaskTickResult.running("walking to original legal tool stock");}
  var result=IndustryTransfers.once(s,root,actor,point.intent(),legs,Map.of(point.containerId(),new TransferEndpoint(point.containerId(),box),bag,new TransferEndpoint(bag,inventory)),token,now);
  return result.state()==IndustryTransfers.State.REVIEW?review(result.detail()):result.state()==IndustryTransfers.State.SUCCESS?TaskTickResult.running("real tool acquired; equip on next tick"):waitFor(result.detail(),now);
 }
 private static TaskTickResult waitFor(String reason,long now){return TaskTickResult.blocked(reason,new NpcPlanBlocker("INDUSTRY_TOOL_WAIT",reason,now+100,null));}
 private static TaskTickResult review(String reason){return TaskTickResult.blocked("tool REVIEW: "+reason,new NpcPlanBlocker("INDUSTRY_TOOL_REVIEW",reason,Long.MAX_VALUE,null));}
}
