package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import java.util.*;
/** Original stock and transfer ledger, original body and a closed list of named tools. */
public final class IndustryToolTask implements NpcTaskExecutor {
 public static final Identifier ID=Identifier.parse("sagadyssey:industry_tool");
 public static boolean supported(String item){return Set.of("minecraft:iron_axe","minecraft:iron_hoe","minecraft:shears","minecraft:fishing_rod").contains(item);}
 static boolean ownedUnencumbered(java.util.List<AssetClaim> claims,SubjectRef owner){
  return claims.stream().allMatch(c->c.beneficialOwner().equals(owner)&&c.custodian().equals(owner)&&c.encumbrances().isEmpty()&&c.reservations().isEmpty());
 }
 static List<AssetClaim> claimsForSlot(RightsStateData rights,UUID bag,int slot){
  return rights.claims().stream().filter(c->c.containerId().equals(bag)&&c.slot()==slot).toList();
 }
 static boolean slotClaimsMatch(List<AssetClaim> claims,SubjectRef owner,ItemFingerprint actual,int quantity){
  if(actual==null)return quantity==0&&claims.isEmpty();
  return quantity==1&&claims.size()<=1&&ownedUnencumbered(claims,owner)
          &&claims.stream().allMatch(c->c.quantity()==quantity&&c.fingerprint().equals(actual));
 }
 static AssetClaim relocate(AssetClaim original,int slot,ItemFingerprint fingerprint,long revision){
  return new AssetClaim(original.claimId(),original.containerId(),slot,fingerprint,original.quantity(),
          original.beneficialOwner(),original.custodian(),original.encumbrances(),original.reservations(),
          original.provenance(),Math.max(original.revision()+1,revision));
 }
 private static boolean workTool(ItemStack stack){
  return stack.isEmpty()||stack.getCount()==1&&(MinecraftApi.isDigger(stack)
          ||stack.is(Items.SHEARS)||stack.is(Items.FISHING_ROD));
 }
 /** A same-container slot swap, not a transfer of title. Both physical items and exact claims move together. */
 private static TaskTickResult equipOwned(NpcBase n,int slot,long now){
  if(!MinecraftApi.server(n).isSameThread())throw new IllegalStateException("tool swap requires server thread");
  var inventory=n.getPersonalInventory();var handSlot=inventory.getContainerSize();
  var held=n.getMainHandItem().copy();var candidate=inventory.getItem(slot).copy();
  if(!workTool(held)||candidate.getCount()!=1)return waitFor("occupied hand is not a single work tool; retain original items",now);
  var rights=RightsStateData.get(MinecraftApi.server(n));var bag=ContainerIds.npcPersonal(n.getCharacterId());
  var actor=SubjectRef.character(n.getCharacterId());var context=rights.context(bag);
  var handClaims=claimsForSlot(rights,bag,handSlot);var bagClaims=claimsForSlot(rights,bag,slot);
  var heldFp=held.isEmpty()?null:ItemFingerprint.of(held);var nextFp=ItemFingerprint.of(candidate);
  if(context==null||!context.defaultBeneficialOwner().equals(actor)||!context.custodian().equals(actor)
          ||!slotClaimsMatch(handClaims,actor,heldFp,held.getCount())||!slotClaimsMatch(bagClaims,actor,nextFp,candidate.getCount()))
   return review("tool slot/title/quantity mismatch; original items and claims retained");
  if(rights.reservedQuantity(bag,nextFp,null,now)>0||heldFp!=null&&rights.reservedQuantity(bag,heldFp,null,now)>0)
   return waitFor("one of the original tools is committed; do not move its slot",now);
  var snapshot=rights.snapshotFor(Set.of(bag));long inventoryRevision=inventory.revision();
  try{
   inventory.setItem(slot,held.copy());n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,candidate.copy());
   handClaims.forEach(c->rights.removeClaim(c.claimId()));bagClaims.forEach(c->rights.removeClaim(c.claimId()));
   for(var claim:handClaims)rights.putClaim(relocate(claim,slot,heldFp,context.revision()+1));
   for(var claim:bagClaims)rights.putClaim(relocate(claim,handSlot,nextFp,context.revision()+1));
   rights.bumpContext(bag);
   return TaskTickResult.stepSucceeded("original owned tools exchanged slots; quantities and title preserved");
  }catch(RuntimeException failure){
   inventory.setItem(slot,candidate.copy());n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,held.copy());
   inventory.restoreRevision(inventoryRevision);rights.restore(snapshot);
   return review("tool swap rolled back: "+failure.getMessage());
  }
 }
 /** Actual native wear changes the component fingerprint, never the owner or provenance. */
 static void damageOwnedHand(NpcBase n,int amount){
  if(!MinecraftApi.server(n).isSameThread())throw new IllegalStateException("tool wear requires server thread");
  var rights=RightsStateData.get(MinecraftApi.server(n));var bag=ContainerIds.npcPersonal(n.getCharacterId());
  int slot=n.getPersonalInventory().getContainerSize();var tool=n.getMainHandItem();
  var actor=SubjectRef.character(n.getCharacterId());var context=rights.context(bag);var claims=claimsForSlot(rights,bag,slot);
  if(amount<1||tool.isEmpty()||context==null||!context.defaultBeneficialOwner().equals(actor)
          ||!context.custodian().equals(actor)||!slotClaimsMatch(claims,actor,ItemFingerprint.of(tool),tool.getCount())
          ||rights.reservedQuantity(bag,ItemFingerprint.of(tool),null,n.level().getGameTime())>0)
   throw new IllegalStateException("original hand tool changed before native wear; preserve uncertain action");
  var originalItem=tool.getItem();
  tool.hurtAndBreak(amount,n,net.minecraft.world.entity.EquipmentSlot.MAINHAND);
  if(!n.getMainHandItem().isEmpty()&&(n.getMainHandItem().getItem()!=originalItem||n.getMainHandItem().getCount()!=1))
   throw new IllegalStateException("native wear replaced original tool unexpectedly; preserve original claim for review");
  for(var claim:claims){
   rights.removeClaim(claim.claimId());
   if(!n.getMainHandItem().isEmpty())rights.putClaim(relocate(claim,slot,ItemFingerprint.of(n.getMainHandItem()),context.revision()+1));
  }
  rights.bumpContext(bag);
 }
 private static boolean usable(NpcBase n,ItemStack stack,int slot,String item,long now){
  if(!stack.isEmpty()&&ItemFingerprint.of(stack).itemId().equals(item)&&(!stack.isDamageableItem()||stack.getDamageValue()<stack.getMaxDamage())){
   var rights=RightsStateData.get(MinecraftApi.server(n));var bag=ContainerIds.npcPersonal(n.getCharacterId());var owner=SubjectRef.character(n.getCharacterId());var fp=ItemFingerprint.of(stack);
   return rights.context(bag)!=null&&rights.context(bag).defaultBeneficialOwner().equals(owner)&&rights.context(bag).custodian().equals(owner)&&slotClaimsMatch(claimsForSlot(rights,bag,slot),owner,fp,stack.getCount())&&rights.reservedQuantity(bag,fp,null,now)==0&&new RightsService(rights).can(owner,AssetRight.USE,bag,slot,fp,now).status()==RightsVerdict.Status.ALLOW;
  }return false;
 }
 private static boolean needsEquipping(String item){return item.equals("minecraft:shears")||item.equals("minecraft:fishing_rod");}
 private static String wanted(NpcBase n,long now){
  var data=EntityResourceActionData.existing(MinecraftApi.server(n));
  if(n.getProfession()==NpcProfession.WORKER&&SettlementIndustryAccess.resident(n)){
   data=EntityResourceActionData.get(MinecraftApi.server(n));
   NaturalIndustrySources.discover(n,data,now);
   for(var source:data.sources()){
    String kind=source.getStringOr("Kind", "");
    if(NaturalIndustrySources.natural(kind)&&NaturalIndustrySources.liveDemand(n,source,now)
            &&EntityResourceTask.validSource(n,data,source)){
     return kind.equals(NaturalIndustrySources.ANGLING)?"minecraft:fishing_rod":"minecraft:shears";
    }
   }
  }
  if(data!=null&&n.getProfession()==NpcProfession.WORKER){
   if(data.actions().stream().anyMatch(a->a.read("Person", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId())&&!EntityResourceActionData.terminal(a.getStringOr("State", ""))))return null;
   for(var src:data.sources())if(src.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId())&&src.getStringOr("Kind", "").equals("SHEAR")){
    var out=FieldworkStorage.load(src.getCompoundOrEmpty("Output"));
    for(var req:NeedRequestData.get(MinecraftApi.server(n)).requests())if(!req.status().terminal()&&req.status()!=NeedRequestStatus.DELIVERED&&req.remaining()>0&&req.key().item().equals(new ItemFingerprint("minecraft:white_wool",0))){
     var scope=SupplyFlowData.get(MinecraftApi.server(n)).demand(req.id());if(scope!=null&&SupplyFlowData.get(MinecraftApi.server(n)).known(scope.principal()).contains(out.containerId()))return "minecraft:shears";
    }
   }
  }
  if(!n.hasWorkZone())return null;var min=n.getProfession()==NpcProfession.FARMER?FieldworkDiscovery.area(n).min():n.getWorkZoneMin();var max=n.getWorkZoneMax();
  // Bounded known work plots only; never use a global or unloaded resource scan.
  if((long)(max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1)>2048)return null;
  for(var pos:net.minecraft.core.BlockPos.betweenClosed(min,max)){
   if(!n.level().hasChunkAt(pos))continue;var target=FieldworkDiscovery.inspect(n,pos);if(target==null)continue;
   if(target.operation()==FieldworkAction.Operation.CHOP&&target.before().is(net.minecraft.tags.BlockTags.LOGS))return "minecraft:iron_axe";
   if(target.operation()==FieldworkAction.Operation.TILL)return "minecraft:iron_hoe";
  }return null;
 }
 private static FieldworkStorage stock(NpcBase n){
  var s=MinecraftApi.server(n);var owner=SubjectRef.character(n.getCharacterId());var life=NpcLifeData.get(s).record(n.getCharacterId());var rights=RightsStateData.get(s);String dim=n.level().dimension().identifier().toString();
  if(life!=null&&life.privateStoragePosition()!=null&&life.dimension().equals(dim)&&n.level().hasChunkAt(life.privateStoragePosition())){
   var pos=life.privateStoragePosition();var id=ProductionContainerIds.resolve(s,dim,pos);var c=rights.context(id);
   if(c!=null&&c.defaultBeneficialOwner().equals(owner))return new FieldworkStorage(dim,pos,id,owner,c.accessPolicy(),TransferIntent.DELIVERY,"industry-tool-own");
  }
  var home=HouseholdData.get(s).forMember(n.getCharacterId());if(home==null||home.sharedStorage()==null||!home.dimension().equals(dim))return null;
  var id=ContainerIds.block("household_storage",dim,home.sharedStorage());var c=rights.context(id);if(c==null||!c.defaultBeneficialOwner().equals(SubjectRef.household(home.id())))return null;
  return new FieldworkStorage(dim,home.sharedStorage(),id,SubjectRef.household(home.id()),c.accessPolicy(),TransferIntent.GIFT,"industry-tool-household");
 }
 public static Optional<NpcPlan> choose(NpcBase n,long now){
  if(!EntityResourceTask.enabled(n)||n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null||!NpcSchedulePlanner.allowsWork(n))return Optional.empty();String item=wanted(n,now);if(item==null)return Optional.empty();
  var inventory=n.getPersonalInventory();if(usable(n,n.getMainHandItem(),inventory.getContainerSize(),item,now))return Optional.empty();
  for(int slot=0;slot<inventory.getContainerSize();slot++)if(usable(n,inventory.getItem(slot),slot,item,now))return needsEquipping(item)?Optional.of(plan(n,item,null,null,now)):Optional.empty();
  var point=stock(n);if(point==null)return Optional.empty();var flows=SupplyFlowData.get(MinecraftApi.server(n));flows.register(point);var owner=SubjectRef.character(n.getCharacterId());flows.reveal(owner,point.containerId());
  var fp=new ItemFingerprint(item,0);
  // The declared one-tool buffer is a real obligation; normal supply/manufacture fulfils it.
  flows.policy(new SupplyFlowData.Policy("industry-tool:"+n.getCharacterId()+":"+item,point.owner(),point.containerId(),fp,1,"workshop"));
  var need=NeedRequestData.get(MinecraftApi.server(n)).requests().stream().filter(r->r.key().destination().equals(point.containerId())&&r.key().item().equals(fp)&&r.key().purpose().contains("industry-tool:"+n.getCharacterId()+":"+item)).max(Comparator.comparingLong(NeedRequest::createdAt)).orElse(null);
  if(need==null || !SettlementIndustryAccess.fixture(n,"sagadyssey.industry.native")
          &&SupplyFlowService.available(MinecraftApi.server(n),point,owner,fp,now)<1)return Optional.empty();
  return Optional.of(plan(n,item,point,need.id(),now));
 }
 private static NpcPlan plan(NpcBase n,String item,FieldworkStorage source,UUID need,long now){
  var params=new HashMap<String,String>();params.put("item",item);if(source!=null){params.put("source",source.containerId().toString());params.put("need",need.toString());}
  return new NpcPlan(UUID.randomUUID(),n.getUUID(),"industry-tool:"+n.getCharacterId()+":"+item,ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,List.of(new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,params,NpcPlanStepStatus.PENDING,now,"original worker obtains named tool")),0,now,now,now+40,Long.MAX_VALUE,null,"existing title, original material obligation and transfer receipt");
 }
 public Identifier id(){return ID;}public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason why){if(c instanceof NpcEntityExecutionContext e)e.npc().getNavigation().stop();}
 public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
  if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("original tool user required");var n=c.npc();var s=MinecraftApi.server(n);long now=c.gameTime();String item=step.params().get("item");if(!supported(item))return TaskTickResult.failed("undeclared tool");
  if(n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null||!NpcSchedulePlanner.allowsWork(n))return waitFor("work condition unavailable",now);
  var inventory=n.getPersonalInventory();if(usable(n,n.getMainHandItem(),inventory.getContainerSize(),item,now))return TaskTickResult.stepSucceeded("original usable tool already equipped");
  for(int slot=0;slot<inventory.getContainerSize();slot++)if(usable(n,inventory.getItem(slot),slot,item,now)){
   // Fieldwork already selects an authorized axe/hoe from its actual inventory.
    if(!needsEquipping(item))return TaskTickResult.stepSucceeded("authorized fieldwork tool already usable in original inventory");
   return equipOwned(n,slot,now);

  }
  if(!step.params().containsKey("source"))return TaskTickResult.failed("original own item no longer available");
  var point=SupplyFlowData.get(s).pointOrNull(UUID.fromString(step.params().get("source")));var original=stock(n);if(point==null||original==null||!original.containerId().equals(point.containerId()))return waitFor("original facility/title no longer valid",now);
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
