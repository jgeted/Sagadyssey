package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.ProductionLaborData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.IShearable;
import java.util.*;

/** Real NPC/native effects and original asset transfers. Unknown commits enter durable review, never reroll. */
public final class EntityResourceTask implements NpcTaskExecutor {
    public static final ResourceLocation ID=ResourceLocation.parse("sagadyssey:entity_resource");
    public static boolean enabled(NpcBase n){return n.getServer() instanceof GameTestServer && Boolean.getBoolean("sagadyssey.industry.native");}
    public static Optional<NpcPlan> choose(NpcBase n,long now){
        if(!enabled(n) || n.getProfession()!=NpcProfession.WORKER || n.getCommand()!=NpcCommand.WORK || n.getTarget()!=null || !NpcSchedulePlanner.allowsWork(n))return Optional.empty();
        var d=EntityResourceActionData.existing(n.getServer());if(d==null)return Optional.empty();
        var active=d.actions().stream().filter(a->a.getUUID("Person").equals(n.getCharacterId()) && !Set.of("DONE","REVIEW").contains(a.getString("State"))).findFirst().orElse(null);
        if(active!=null){if(!active.getUUID("Body").equals(n.getUUID()))return Optional.empty();active.putUUID("Plan",UUID.randomUUID());d.putAction(active);return Optional.of(plan(n,active,now));}
        for(var source:d.sources()){
            if(!source.getUUID("Actor").equals(n.getCharacterId()))continue;
            var need=NeedRequestData.get(n.getServer()).request(source.getUUID("Need"));
            if(need==null)continue;
            if(Set.of(NeedRequestStatus.DELIVERED,NeedRequestStatus.CANCELLED,NeedRequestStatus.CONSUMED,NeedRequestStatus.CLOSED).contains(need.status()) || need.remaining()<=0){
                var out=FieldworkStorage.load(source.getCompound("Output"));var item=new ItemFingerprint(source.getString("Kind").equals("SHEAR")?"minecraft:white_wool":"minecraft:cod",0);
                for(var candidate:NeedRequestData.get(n.getServer()).requests()){
                    if(candidate.id().equals(need.id())||candidate.status().terminal()||candidate.status()==NeedRequestStatus.DELIVERED||candidate.remaining()<=0||!candidate.key().item().equals(item))continue;
                    var scope=SupplyFlowData.get(n.getServer()).demand(candidate.id());if(scope==null||!SupplyFlowData.get(n.getServer()).known(scope.principal()).contains(out.containerId()))continue;
                    try{var id=EntityResourceAuthority.bindKnownOwnedNeed(n,source.getUUID("Id"),candidate.id());source=d.source(id);need=candidate;break;}catch(IllegalArgumentException|IllegalStateException denied){/* no new effect or permission; unrelated sources may proceed */}
                }
                if(need.status().terminal()||need.status()==NeedRequestStatus.DELIVERED||need.remaining()<=0)continue;
            }
            var output=FieldworkStorage.load(source.getCompound("Output"));var scope=SupplyFlowData.get(n.getServer()).demand(need.id());
            if(SupplyFlowService.available(n.getServer(),output,scope.principal(),need.key().item(),now)>=need.remaining())continue;
            var found=((net.minecraft.server.level.ServerLevel)n.level()).getEntity(source.getUUID("Target"));
            if(!(found instanceof LivingEntity target) || !target.isAlive() || !EntityResourceAuthority.allows(n,source,target))continue;
            if(source.getString("Kind").equals("SHEAR") && (!(target instanceof Sheep sheep) || !sheep.readyForShearing()))continue;
            if(!toolAllowed(n,source.getString("Kind"),now))continue;
            UUID selectedSource=source.getUUID("Id");long cycle=d.actions().stream().filter(a->a.getUUID("Source").equals(selectedSource)).count();
            var a=new CompoundTag();a.putUUID("Id",IndustryTransfers.id("native:"+source.getUUID("Id")+":"+cycle));a.putUUID("Source",source.getUUID("Id"));
            a.putUUID("Need",need.id());a.putUUID("Person",n.getCharacterId());a.putUUID("Body",n.getUUID());a.putUUID("Plan",UUID.randomUUID());
            a.putString("State","READY");a.putLong("StartedAt",now);a.putLong("UpdatedAt",now);a.put("Drops",new ListTag());d.putAction(a);
            return Optional.of(plan(n,a,now));
        }
        return Optional.empty();
    }
    private static NpcPlan plan(NpcBase n,CompoundTag a,long now){
        return new NpcPlan(a.getUUID("Plan"),n.getUUID(),"native:"+a.getUUID("Id"),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,
                List.of(new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("action",a.getUUID("Id").toString()),NpcPlanStepStatus.PENDING,now,"native resource obligation")),0,now,now,now+40,now+12000,null,"explicit entity title and original transfer ledger");
    }
    @Override public ResourceLocation id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){if(c instanceof NpcEntityExecutionContext e){e.npc().getNavigation().stop();NpcBrainRuntime.reservations(e.npc().getServer()).releasePlan(p.id());}}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("native resource needs original body");
        var n=c.npc();var data=EntityResourceActionData.existing(n.getServer());var a=data==null?null:data.action(UUID.fromString(step.params().get("action")));
        if(a==null)return TaskTickResult.failed("missing native facts; do not recreate");
        if(a.getString("State").equals("DONE"))return TaskTickResult.stepSucceeded("original native obligation settled");
        if(a.getString("State").equals("REVIEW"))return TaskTickResult.failed("native obligation preserved for review");
        if(!a.getUUID("Person").equals(n.getCharacterId()) || !a.getUUID("Body").equals(n.getUUID()))return review(data,a,"body/person conflict");
        if(n.getCommand()!=NpcCommand.WORK || n.getProfession()!=NpcProfession.WORKER || !NpcSchedulePlanner.allowsWork(n))return waitFor("original work conditions unavailable",c.gameTime());
        var source=data.source(a.getUUID("Source"));if(source==null)return review(data,a,"source title missing");
        if(a.getString("State").equals("ACTING"))return review(data,a,"native result uncertain; do not shear, attack or reroll again");
        if(a.getString("State").equals("READY")){
            var originalNeed=NeedRequestData.get(n.getServer()).request(a.getUUID("Need"));
            if(originalNeed.status().terminal()||originalNeed.status()==NeedRequestStatus.DELIVERED||originalNeed.remaining()<=0)
                return review(data,a,"original need ended before native action; no effect executed");
            var lease=NpcBrainRuntime.reservations(n.getServer()).acquire(new NpcReservationKey("native_entity",source.getUUID("Target").toString()),plan.id(),n.getUUID(),c.gameTime(),c.gameTime()+100);
            if(lease.status()==com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationAcquireResult.Status.CONFLICT)return waitFor("original entity leased to another plan",c.gameTime());
            var found=c.level().getEntity(source.getUUID("Target"));
            if(!(found instanceof LivingEntity target) || !target.isAlive() || !EntityResourceAuthority.allows(n,source,target))return waitFor("registered target unavailable or outside consent area",c.gameTime());
            if(!toolAllowed(n,source.getString("Kind"),c.gameTime()))return waitFor("missing owned usable tool",c.gameTime());
            if(source.getString("Kind").equals("SHEAR") && target instanceof Sheep sh && !sh.readyForShearing())return waitFor("original sheep must regrow wool",c.gameTime());
            if(n.distanceToSqr(target)>6.25 || !n.hasLineOfSight(target)){
                approach(n,target.blockPosition());return TaskTickResult.running("walking to original registered target");
            }
            n.getNavigation().stop();
            if(!room(n,source.getString("Kind")))return waitFor("no space for bounded real native drops",c.gameTime());
            if(ProductionLaborData.get(n.getServer()).tick(a.getUUID("Id"),40,c.gameTime())<40){n.playAction(NpcAction.HARVEST,10);return TaskTickResult.running("original NPC performs actual nearby work");}
            a.putString("TargetBefore",source.getString("Kind").equals("SHEAR")?"adult-white-unshorn":"living-cod:"+target.getHealth());
            a.put("ToolBefore",ItemFingerprint.of(n.getMainHandItem()).save());a.putInt("ToolDamageBefore",n.getMainHandItem().getDamageValue());
            a.putString("State","ACTING");data.putAction(a);
            var before=c.level().getEntitiesOfClass(ItemEntity.class,target.getBoundingBox().inflate(4)).stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            boolean effected;
            if(source.getString("Kind").equals("SHEAR")){
                IShearable shearable=(IShearable)target;
                if(!shearable.isShearable(null,n.getMainHandItem(),c.level(),target.blockPosition())){a.putString("State","READY");data.putAction(a);return waitFor("native shearing refused",c.gameTime());}
                var result=shearable.onSheared(null,n.getMainHandItem(),c.level(),target.blockPosition());
                var merged=new ArrayList<ItemStack>();
                for(var stack:result){var same=merged.stream().filter(x->ItemStack.isSameItemSameComponents(x,stack)).findFirst().orElse(null);if(same==null)merged.add(stack.copy());else same.grow(stack.getCount());}
                for(var stack:merged)shearable.spawnShearedDrop(c.level(),target.blockPosition(),stack);
                effected=target instanceof Sheep sh && sh.isSheared();
            }else{effected=n.doHurtTarget(target) && !target.isAlive();}
            if(!effected)return review(data,a,"native action not conclusively completed; retain actual target/tool state");
            n.getMainHandItem().hurtAndBreak(1,n,EquipmentSlot.MAINHAND);
            var rights=RightsStateData.get(n.getServer());var actor=SubjectRef.character(n.getCharacterId());var bag=ContainerIds.npcPersonal(n.getCharacterId());
            rights.bumpContext(bag);
            var drops=new ListTag();
            for(var item:c.level().getEntitiesOfClass(ItemEntity.class,target.getBoundingBox().inflate(4))){
                if(before.contains(item.getUUID()) || item.getItem().isEmpty())continue;
                boolean allowed=source.getString("Kind").equals("SHEAR")?item.getItem().is(Items.WHITE_WOOL):item.getItem().is(Items.COD)||item.getItem().is(Items.BONE_MEAL);
                if(!allowed || item.getOwner()!=null && item.getOwner()!=n && item.getOwner()!=target)return review(data,a,"unexpected or foreign native drop");
                var dropId=IndustryTransfers.id("native-drop:"+item.getUUID());
                var old=rights.context(dropId);if(old!=null && !old.defaultBeneficialOwner().equals(actor))return review(data,a,"conflicting drop title");
                rights.ensureContext(dropId,"native-output:"+a.getUUID("Id"),actor,actor,AccessPolicy.PRIVATE);
                var row=new CompoundTag();row.putUUID("Id",item.getUUID());row.putUUID("Container",dropId);row.put("Item",ItemFingerprint.of(item.getItem()).save());row.putInt("Quantity",item.getItem().getCount());drops.add(row);
            }
            a.put("Drops",drops);a.putString("State","ACTED");a.putLong("UpdatedAt",c.gameTime());a.putInt("ToolDamageAfter",n.getMainHandItem().getDamageValue());
            if(drops.isEmpty())return review(data,a,"native effect occurred without attributable drops");
            data.putAction(a);return TaskTickResult.running("native result recorded; original dropped entities await lawful pickup");
        }
        var actor=SubjectRef.character(n.getCharacterId());var bag=ContainerIds.npcPersonal(n.getCharacterId());var rights=RightsStateData.get(n.getServer());
        String token="native-output:"+a.getUUID("Id");var list=a.getList("Drops",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){
            var drop=list.getCompound(i);var fp=ItemFingerprint.load(drop.getCompound("Item"));int q=drop.getInt("Quantity");
            var pickupId=IndustryTransfers.id(a.getUUID("Id")+":pickup:"+drop.getUUID("Id"));
            var legs=List.of(new TransferLeg(drop.getUUID("Container"),bag,fp,q));
            if(!drop.getBoolean("Picked")){
                var outcome=IndustryTransfers.confirmed(n.getServer(),pickupId,actor,TransferIntent.DELIVERY,legs,token);
                if(outcome.state()!=IndustryTransfers.State.SUCCESS){
                    var item=c.level().getEntity(drop.getUUID("Id"));
                    if(!(item instanceof ItemEntity live) || !fp.matches(live.getItem()) || live.getItem().getCount()!=q)return review(data,a,"original drop missing/merged/changed without confirmed transfer");
                    if(n.distanceToSqr(live)>6.25){approach(n,live.blockPosition());return TaskTickResult.running("approaching actual drop");}
                    if(live.hasPickUpDelay())return TaskTickResult.running("respecting native pickup delay");
                    outcome=IndustryTransfers.once(n.getServer(),pickupId,actor,TransferIntent.DELIVERY,legs,Map.of(drop.getUUID("Container"),new TransferEndpoint(drop.getUUID("Container"),IndustryContainers.dropped(live)),bag,new TransferEndpoint(bag,n.getPersonalInventory())),token,c.gameTime());
                }
                if(outcome.state()==IndustryTransfers.State.REVIEW)return review(data,a,outcome.detail());
                if(outcome.state()!=IndustryTransfers.State.SUCCESS)return waitFor(outcome.detail(),c.gameTime());
                drop.putBoolean("Picked",true);drop.putUUID("PickupReceipt",outcome.actualId());
                if(!rights.reserve(new AssetReservation(IndustryTransfers.id(a.getUUID("Id")+":cargo:"+i),bag,fp,q,actor,token,"native output already committed to original need",c.gameTime(),Long.MAX_VALUE),n.getPersonalInventory().count(fp::matches),c.gameTime()))return review(data,a,"cannot protect actual carried output");
                data.putAction(a);
            }
        }
        var output=FieldworkStorage.load(source.getCompound("Output"));var target=SupplyFlowService.container(n.getServer(),output);
        if(target==null)return waitFor("owned output store unloaded; original cargo retained",c.gameTime());
        if(!rights.context(output.containerId()).defaultBeneficialOwner().equals(actor))return review(data,a,"output title changed");
        if(!FieldworkRoutes.closeEnough(n,output.position())){
            var route=FieldworkRoutes.plan(n,output.position(),false);if(route==null)return waitFor("output path blocked; original cargo retained",c.gameTime());
            n.getNavigation().moveTo(route.stand().getX()+.5,route.stand().getY(),route.stand().getZ()+.5,1.1);return TaskTickResult.running("carrying actual native output to owned store");
        }
        var legs=new ArrayList<TransferLeg>();for(var x:list){var d=(CompoundTag)x;legs.add(new TransferLeg(bag,output.containerId(),ItemFingerprint.load(d.getCompound("Item")),d.getInt("Quantity")));}
        var outcome=IndustryTransfers.once(n.getServer(),IndustryTransfers.id(a.getUUID("Id")+":deposit"),actor,TransferIntent.DELIVERY,legs,
                Map.of(bag,new TransferEndpoint(bag,n.getPersonalInventory()),output.containerId(),new TransferEndpoint(output.containerId(),target)),token,c.gameTime());
        if(outcome.state()==IndustryTransfers.State.REVIEW)return review(data,a,outcome.detail());
        if(outcome.state()!=IndustryTransfers.State.SUCCESS)return waitFor(outcome.detail(),c.gameTime());
        a.putUUID("DeliveryReceipt",outcome.actualId());a.putString("State","DONE");a.putLong("UpdatedAt",c.gameTime());data.putAction(a);rights.releaseReservations(token);n.getNavigation().stop();
        return TaskTickResult.stepSucceeded("native output physically stored; ordinary supply continues original need");
    }
    private static boolean room(NpcBase n,String kind){return kind.equals("SHEAR")?FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.WHITE_WOOL))>=3
            :FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.COD))>=1 && FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.BONE_MEAL))>=1;}
    private static boolean toolAllowed(NpcBase n,String kind,long now){
        var tool=n.getMainHandItem();if(kind.equals("SHEAR")?!tool.is(Items.SHEARS):!(tool.getItem() instanceof SwordItem))return false;
        if(tool.isDamageableItem()&&tool.getDamageValue()>=tool.getMaxDamage())return false;
        var r=RightsStateData.get(n.getServer());var id=ContainerIds.npcPersonal(n.getCharacterId());var owner=SubjectRef.character(n.getCharacterId());
        return r.context(id)!=null && r.context(id).defaultBeneficialOwner().equals(owner)
                && r.claimsAt(id,n.getPersonalInventory().getContainerSize(),ItemFingerprint.of(tool)).isEmpty()
                && r.reservedQuantity(id,ItemFingerprint.of(tool),null,now)==0
                && new RightsService(r).can(owner,AssetRight.USE,id,n.getPersonalInventory().getContainerSize(),ItemFingerprint.of(tool),now).status()==RightsVerdict.Status.ALLOW;
    }
    private static TaskTickResult review(EntityResourceActionData d,CompoundTag a,String reason){a.putString("State","REVIEW");a.putString("Detail",reason);d.putAction(a);return TaskTickResult.failed("native REVIEW: "+reason);}
    private static TaskTickResult waitFor(String reason,long now){return TaskTickResult.blocked(reason,new NpcPlanBlocker("NATIVE_WAIT",reason,now+100,null));}
    private static void approach(NpcBase n,BlockPos p){
        var stands=new ArrayList<BlockPos>();for(var q:BlockPos.betweenClosed(p.offset(-1,-1,-1),p.offset(1,1,1)))
            if(FieldworkRoutes.walkable(n,q))stands.add(q.immutable());
        stands.sort(Comparator.comparingDouble(n.blockPosition()::distSqr));
        for(var q:stands){var path=n.getNavigation().createPath(q,0);if(path!=null&&path.canReach()){n.getNavigation().moveTo(path,1.1);return;}}
    }
}
