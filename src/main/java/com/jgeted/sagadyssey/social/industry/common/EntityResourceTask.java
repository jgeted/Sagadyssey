package com.jgeted.sagadyssey.social.industry.common;

import net.minecraft.world.entity.animal.sheep.Sheep;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.IShearable;
import java.util.*;

/** Real NPC/native effects and original asset transfers. Unknown commits enter durable review, never reroll. */
public final class EntityResourceTask implements NpcTaskExecutor {
    public static final Identifier ID=Identifier.parse("sagadyssey:entity_resource");
    public static boolean enabled(NpcBase n){
        return SettlementIndustryAccess.fixture(n,"sagadyssey.industry.native") || SettlementIndustryAccess.resident(n);
    }
    public static Optional<NpcPlan> choose(NpcBase n,long now){
        if(!enabled(n) || n.getProfession()!=NpcProfession.WORKER || n.getCommand()!=NpcCommand.WORK || n.getTarget()!=null || !NpcSchedulePlanner.allowsWork(n))return Optional.empty();
        boolean fixture=SettlementIndustryAccess.fixture(n,"sagadyssey.industry.native");
        if(!fixture)com.jgeted.sagadyssey.social.industry.animal.WoolSupplyNeeds.maintain(n,now);
        var d=fixture?EntityResourceActionData.existing(MinecraftApi.server(n)):EntityResourceActionData.get(MinecraftApi.server(n));
        if(d==null)return Optional.empty();
        var active=d.actions().stream().filter(a->a.read("Person", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()) && !EntityResourceActionData.terminal(a.getStringOr("State", ""))).findFirst().orElse(null);
        if(active!=null){if(!active.read("Body", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID()))return Optional.empty();active.store("Plan", UUIDUtil.CODEC,UUID.randomUUID());d.putAction(active);return Optional.of(plan(n,active,now));}
        if(!d.hasActionCapacity())return Optional.empty();
        if(!fixture)NaturalIndustrySources.discover(n,d,now);
        for(var source:d.sources()){
            if(!source.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()))continue;
            if(!fixture&&source.getStringOr("Kind", "").equals("FISH_COD"))continue;
            var need=NeedRequestData.get(MinecraftApi.server(n)).request(source.read("Need", UUIDUtil.CODEC).orElseThrow());
            if(need==null)continue;
            if(Set.of(NeedRequestStatus.DELIVERED,NeedRequestStatus.CANCELLED,NeedRequestStatus.CONSUMED,NeedRequestStatus.CLOSED).contains(need.status()) || need.remaining()<=0){
                if(NaturalIndustrySources.natural(source.getStringOr("Kind", "")))continue;
                var out=FieldworkStorage.load(source.getCompoundOrEmpty("Output"));var item=new ItemFingerprint(source.getStringOr("Kind", "").equals("SHEAR")?"minecraft:white_wool":"minecraft:cod",0);
                for(var candidate:NeedRequestData.get(MinecraftApi.server(n)).requests()){
                    if(candidate.id().equals(need.id())||candidate.status().terminal()||candidate.status()==NeedRequestStatus.DELIVERED||candidate.remaining()<=0||!candidate.key().item().equals(item))continue;
                    var scope=SupplyFlowData.get(MinecraftApi.server(n)).demand(candidate.id());if(scope==null||!SupplyFlowData.get(MinecraftApi.server(n)).known(scope.principal()).contains(out.containerId())
                            ||!fixture&&!SettlementIndustryAccess.servesLocalPrincipal(n,scope.principal()))continue;
                    try{var id=EntityResourceAuthority.bindKnownOwnedNeed(n,source.read("Id", UUIDUtil.CODEC).orElseThrow(),candidate.id());source=d.source(id);need=candidate;break;}catch(IllegalArgumentException|IllegalStateException denied){/* no new effect or permission; unrelated sources may proceed */}
                }
                if(need.status().terminal()||need.status()==NeedRequestStatus.DELIVERED||need.remaining()<=0)continue;
            }
            var output=FieldworkStorage.load(source.getCompoundOrEmpty("Output"));var scope=SupplyFlowData.get(MinecraftApi.server(n)).demand(need.id());
            if(scope==null||!fixture&&(!SettlementIndustryAccess.servesLocalPrincipal(n,scope.principal())
                    ||!SettlementIndustryAccess.local(n,output.position())))continue;
            if(SupplyFlowService.available(MinecraftApi.server(n),output,scope.principal(),need.key().item(),now)>=need.remaining())continue;
            String kind=source.getStringOr("Kind", "");
            UUID needId=need.id();
            if(NaturalIndustrySources.natural(kind)&&(now>=need.key().deadline()
                    ||d.actions().stream().anyMatch(a->a.read("Need", UUIDUtil.CODEC).orElseThrow().equals(needId)
                        &&!EntityResourceActionData.settled(a.getStringOr("State", "")))))continue;
            if(!validSource(n,d,source))continue;
            if(!toolAllowed(n,kind,now))continue;
            UUID selectedSource=source.read("Id", UUIDUtil.CODEC).orElseThrow();long cycle=d.actions().stream().filter(a->a.read("Source", UUIDUtil.CODEC).orElseThrow().equals(selectedSource)).count();
            var a=new CompoundTag();a.store("Id", UUIDUtil.CODEC,IndustryTransfers.id("native:"+source.read("Id", UUIDUtil.CODEC).orElseThrow()+":"+cycle));a.store("Source", UUIDUtil.CODEC,source.read("Id", UUIDUtil.CODEC).orElseThrow());
            a.store("Need", UUIDUtil.CODEC,need.id());a.store("Person", UUIDUtil.CODEC,n.getCharacterId());a.store("Body", UUIDUtil.CODEC,n.getUUID());a.store("Plan", UUIDUtil.CODEC,UUID.randomUUID());
            a.putString("State","READY");a.putLong("StartedAt",now);a.putLong("UpdatedAt",now);a.put("Drops",new ListTag());d.createAction(a);
            return Optional.of(plan(n,a,now));
        }
        return Optional.empty();
    }
    private static NpcPlan plan(NpcBase n,CompoundTag a,long now){
        return new NpcPlan(a.read("Plan", UUIDUtil.CODEC).orElseThrow(),n.getUUID(),"native:"+a.read("Id", UUIDUtil.CODEC).orElseThrow(),ID,NpcPlanKind.COMMITMENT,NpcPlanPriority.NORMAL,NpcPlanStatus.SELECTED,
                List.of(new NpcPlanStep(0,NpcPlanPhase.ACTING,ID,Map.of("action",a.read("Id", UUIDUtil.CODEC).orElseThrow().toString()),NpcPlanStepStatus.PENDING,now,"native resource obligation")),0,now,now,now+40,now+12000,null,"explicit entity title and original transfer ledger");
    }
    @Override public Identifier id(){return ID;}
    @Override public TaskTickResult start(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public TaskTickResult recover(NpcExecutionContext c,NpcPlan p,NpcPlanStep s){return tick(c,p,s);}
    @Override public void cancel(NpcExecutionContext c,NpcPlan p,NpcPlanStep s,PlanCancellationReason reason){if(c instanceof NpcEntityExecutionContext e){e.npc().getNavigation().stop();NpcBrainRuntime.reservations(MinecraftApi.server(e.npc())).releasePlan(p.id());}}
    @Override public TaskTickResult tick(NpcExecutionContext raw,NpcPlan plan,NpcPlanStep step){
        if(!(raw instanceof NpcEntityExecutionContext c))return TaskTickResult.failed("native resource needs original body");
        var n=c.npc();var data=EntityResourceActionData.existing(MinecraftApi.server(n));var a=data==null?null:data.action(UUID.fromString(step.params().get("action")));
        if(a==null)return TaskTickResult.failed("missing native facts; do not recreate");
        if(a.getStringOr("State", "").equals("DONE"))return TaskTickResult.stepSucceeded("original native obligation settled");
        if(a.getStringOr("State", "").equals("CANCELLED"))return TaskTickResult.stepSucceeded("unexecuted original plan cancelled; no native output or cargo created");
        if(a.getStringOr("State", "").equals("REVIEW"))return TaskTickResult.failed("native obligation preserved for review");
        if(!a.read("Person", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()) || !a.read("Body", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID()))return review(data,a,"body/person conflict");
        if(n.getCommand()!=NpcCommand.WORK || n.getProfession()!=NpcProfession.WORKER || !NpcSchedulePlanner.allowsWork(n))return waitFor("original work conditions unavailable",c.gameTime());
        var source=data.source(a.read("Source", UUIDUtil.CODEC).orElseThrow());if(source==null)return review(data,a,"source title missing");
        if(a.getStringOr("State", "").equals("ACTING"))return review(data,a,"native result uncertain; do not shear, attack or reroll again");
        if(a.getStringOr("State", "").equals("READY")){
            var originalNeed=NeedRequestData.get(MinecraftApi.server(n)).request(a.read("Need", UUIDUtil.CODEC).orElseThrow());
            if(originalNeed==null)return review(data,a,"original native need missing; retain unknown facts");
            if(originalNeed.status().terminal()||originalNeed.status()==NeedRequestStatus.DELIVERED){
                if(data.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),originalNeed,c.gameTime(),"original need ended before native action")){
                    NpcBrainRuntime.reservations(MinecraftApi.server(n)).releasePlan(plan.id());n.getNavigation().stop();
                    return TaskTickResult.stepSucceeded("verified unexecuted cancellation; original need and all material obligations retained");
                }
                return review(data,a,"ended need but no current-process no-effect proof; do not retry native result");
            }
            if(originalNeed.remaining()<=0)return review(data,a,"original need covered by unsettled commitments; retain original action");
            if(!SettlementIndustryAccess.fixture(n,"sagadyssey.industry.native")){
                if(source.getStringOr("Kind", "").equals("FISH_COD"))
                    return waitFor("normal fishing requires a rod and a river or ocean shore",c.gameTime());
                var scope=SupplyFlowData.get(MinecraftApi.server(n)).demand(originalNeed.id());
                var output=FieldworkStorage.load(source.getCompoundOrEmpty("Output"));
                if(!SettlementIndustryAccess.resident(n)||scope==null
                        ||!SettlementIndustryAccess.servesLocalPrincipal(n,scope.principal())
                        ||!SettlementIndustryAccess.local(n,output.position())
                        ||NaturalIndustrySources.natural(source.getStringOr("Kind", ""))
                            &&!NaturalIndustrySources.maySupply(n,output,scope.principal(),originalNeed.key().item(),c.gameTime()))
                    return waitFor("original local industry eligibility unavailable",c.gameTime());
                if(NaturalIndustrySources.natural(source.getStringOr("Kind", ""))&&c.gameTime()>=originalNeed.key().deadline())
                    return waitFor("original natural harvest demand window expired",c.gameTime());
            }
            var lease=NpcBrainRuntime.reservations(MinecraftApi.server(n)).acquire(new NpcReservationKey("native_entity",source.read("Target", UUIDUtil.CODEC).orElseThrow().toString()),plan.id(),n.getUUID(),c.gameTime(),c.gameTime()+100);
            if(lease.status()==com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationAcquireResult.Status.CONFLICT)return waitFor("original entity leased to another plan",c.gameTime());
            String kind=source.getStringOr("Kind", "");
            if(!validSource(n,data,source))return waitFor("original source unavailable or outside the permitted range",c.gameTime());
            if(!toolAllowed(n,kind,c.gameTime()))return waitFor("missing owned usable tool",c.gameTime());
            if(kind.equals(NaturalIndustrySources.ANGLING))return fish(c,n,data,a,source);
            var found=c.level().getEntity(source.read("Target", UUIDUtil.CODEC).orElseThrow());
            if(!(found instanceof LivingEntity target))return waitFor("original animal unavailable",c.gameTime());
            if(n.distanceToSqr(target)>6.25 || !n.hasLineOfSight(target)){
                approach(n,target.blockPosition());return TaskTickResult.running("walking to original registered target");
            }
            n.getNavigation().stop();
            if(!room(n,source.getStringOr("Kind", "")))return waitFor("no space for bounded real native drops",c.gameTime());
            if(ProductionLaborData.get(MinecraftApi.server(n)).tick(a.read("Id", UUIDUtil.CODEC).orElseThrow(),40,c.gameTime())<40){n.playAction(NpcAction.HARVEST,10);return TaskTickResult.running("original NPC performs actual nearby work");}
            a.putString("TargetBefore",NaturalIndustrySources.shearing(kind)?"adult-white-unshorn":"living-cod:"+target.getHealth());
            a.put("ToolBefore",ItemFingerprint.of(n.getMainHandItem()).save());a.putInt("ToolDamageBefore",n.getMainHandItem().getDamageValue());
            a.putString("State","ACTING");data.putAction(a);
            var before=c.level().getEntitiesOfClass(ItemEntity.class,target.getBoundingBox().inflate(4)).stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            boolean effected;
            if(NaturalIndustrySources.shearing(kind)){
                IShearable shearable=(IShearable)target;
                if(!shearable.isShearable(null,n.getMainHandItem(),c.level(),target.blockPosition())){a.putString("State","READY");data.putAction(a);return waitFor("native shearing refused",c.gameTime());}
                var result=shearable.onSheared(null,n.getMainHandItem(),c.level(),target.blockPosition());
                var merged=new ArrayList<ItemStack>();
                for(var stack:result){var same=merged.stream().filter(x->ItemStack.isSameItemSameComponents(x,stack)).findFirst().orElse(null);if(same==null)merged.add(stack.copy());else same.grow(stack.getCount());}
                for(var stack:merged)shearable.spawnShearedDrop(c.level(),target.blockPosition(),stack);
                effected=target instanceof Sheep sh && sh.isSheared();
            }else{effected=n.doHurtTarget(MinecraftApi.serverLevel(n), target) && !target.isAlive();}
            if(!effected)return review(data,a,"native action not conclusively completed; retain actual target/tool state");
            IndustryToolTask.damageOwnedHand(n,1);
            var rights=RightsStateData.get(MinecraftApi.server(n));var actor=SubjectRef.character(n.getCharacterId());var bag=ContainerIds.npcPersonal(n.getCharacterId());
            var drops=new ListTag();
            for(var item:c.level().getEntitiesOfClass(ItemEntity.class,target.getBoundingBox().inflate(4))){
                if(before.contains(item.getUUID()) || item.getItem().isEmpty())continue;
                boolean allowed=NaturalIndustrySources.shearing(kind)?item.getItem().is(Items.WHITE_WOOL):item.getItem().is(Items.COD)||item.getItem().is(Items.BONE_MEAL);
                if(!allowed || item.getOwner()!=null && item.getOwner()!=n && item.getOwner()!=target)return review(data,a,"unexpected or foreign native drop");
                var dropId=IndustryTransfers.id("native-drop:"+item.getUUID());
                var old=rights.context(dropId);if(old!=null && !old.defaultBeneficialOwner().equals(actor))return review(data,a,"conflicting drop title");
                rights.ensureContext(dropId,"native-output:"+a.read("Id", UUIDUtil.CODEC).orElseThrow(),actor,actor,AccessPolicy.PRIVATE);
                var row=new CompoundTag();row.store("Id", UUIDUtil.CODEC,item.getUUID());row.store("Container", UUIDUtil.CODEC,dropId);row.put("Item",ItemFingerprint.of(item.getItem()).save());row.putInt("Quantity",item.getItem().getCount());drops.add(row);
            }
            a.put("Drops",drops);a.putString("State","ACTED");a.putLong("UpdatedAt",c.gameTime());a.putInt("ToolDamageAfter",n.getMainHandItem().getDamageValue());
            if(drops.isEmpty())return review(data,a,"native effect occurred without attributable drops");
            data.putAction(a);return TaskTickResult.running("native result recorded; original dropped entities await lawful pickup");
        }
        var actor=SubjectRef.character(n.getCharacterId());var bag=ContainerIds.npcPersonal(n.getCharacterId());var rights=RightsStateData.get(MinecraftApi.server(n));
        String token="native-output:"+a.read("Id", UUIDUtil.CODEC).orElseThrow();var list=MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){
            var drop=list.getCompoundOrEmpty(i);var fp=ItemFingerprint.load(drop.getCompoundOrEmpty("Item"));int q=drop.getIntOr("Quantity", 0);
            var pickupId=IndustryTransfers.id(a.read("Id", UUIDUtil.CODEC).orElseThrow()+":pickup:"+drop.read("Id", UUIDUtil.CODEC).orElseThrow());
            var legs=List.of(new TransferLeg(drop.read("Container", UUIDUtil.CODEC).orElseThrow(),bag,fp,q));
            if(!drop.getBooleanOr("Picked", false)){
                var outcome=IndustryTransfers.confirmed(MinecraftApi.server(n),pickupId,actor,TransferIntent.DELIVERY,legs,token);
                if(outcome.state()!=IndustryTransfers.State.SUCCESS){
                    var item=c.level().getEntity(drop.read("Id", UUIDUtil.CODEC).orElseThrow());
                    if(!(item instanceof ItemEntity live) || !fp.matches(live.getItem()) || live.getItem().getCount()!=q)return review(data,a,"original drop missing/merged/changed without confirmed transfer");
                    if(n.distanceToSqr(live)>6.25){approach(n,live.blockPosition());return TaskTickResult.running("approaching actual drop");}
                    if(live.hasPickUpDelay())return TaskTickResult.running("respecting native pickup delay");
                    outcome=IndustryTransfers.once(MinecraftApi.server(n),pickupId,actor,TransferIntent.DELIVERY,legs,Map.of(drop.read("Container", UUIDUtil.CODEC).orElseThrow(),new TransferEndpoint(drop.read("Container", UUIDUtil.CODEC).orElseThrow(),IndustryContainers.dropped(live)),bag,new TransferEndpoint(bag,n.getPersonalInventory())),token,c.gameTime());
                }
                if(outcome.state()==IndustryTransfers.State.REVIEW)return review(data,a,outcome.detail());
                if(outcome.state()!=IndustryTransfers.State.SUCCESS)return waitFor(outcome.detail(),c.gameTime());
                drop.putBoolean("Picked",true);drop.store("PickupReceipt", UUIDUtil.CODEC,outcome.actualId());
                if(!rights.reserve(new AssetReservation(IndustryTransfers.id(a.read("Id", UUIDUtil.CODEC).orElseThrow()+":cargo:"+i),bag,fp,q,actor,token,"native output already committed to original need",c.gameTime(),Long.MAX_VALUE),n.getPersonalInventory().count(fp::matches),c.gameTime()))return review(data,a,"cannot protect actual carried output");
                data.putAction(a);
            }
        }
        var output=FieldworkStorage.load(source.getCompoundOrEmpty("Output"));var target=SupplyFlowService.container(MinecraftApi.server(n),output);
        if(target==null)return waitFor("owned output store unloaded; original cargo retained",c.gameTime());
        if(!SupplyFlowService.validOwner(MinecraftApi.server(n),output)||!output.owner().equals(actor))return review(data,a,"output title changed");
        if(!FieldworkRoutes.closeEnough(n,output.position())){
            var route=FieldworkRoutes.plan(n,output.position(),false);if(route==null)return waitFor("output path blocked; original cargo retained",c.gameTime());
            FieldworkRoutes.move(n,route.stand());return TaskTickResult.running("carrying actual native output to owned store");
        }
        var legs=new ArrayList<TransferLeg>();for(var x:list){var d=(CompoundTag)x;legs.add(new TransferLeg(bag,output.containerId(),ItemFingerprint.load(d.getCompoundOrEmpty("Item")),d.getIntOr("Quantity", 0)));}
        var outcome=IndustryTransfers.once(MinecraftApi.server(n),IndustryTransfers.id(a.read("Id", UUIDUtil.CODEC).orElseThrow()+":deposit"),actor,TransferIntent.DELIVERY,legs,
                Map.of(bag,new TransferEndpoint(bag,n.getPersonalInventory()),output.containerId(),new TransferEndpoint(output.containerId(),target)),token,c.gameTime());
        if(outcome.state()==IndustryTransfers.State.REVIEW)return review(data,a,outcome.detail());
        if(outcome.state()!=IndustryTransfers.State.SUCCESS)return waitFor(outcome.detail(),c.gameTime());
        a.store("DeliveryReceipt", UUIDUtil.CODEC,outcome.actualId());a.putString("State","DONE");a.putLong("UpdatedAt",c.gameTime());data.putAction(a);rights.releaseReservations(token);n.getNavigation().stop();
        return TaskTickResult.stepSucceeded("native output physically stored; ordinary supply continues original need");
    }
    private static boolean room(NpcBase n,String kind){return NaturalIndustrySources.shearing(kind)?FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.WHITE_WOOL))>=3
            :FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.COD))>=1 && FieldworkDepositTaskExecutor.capacity(n.getPersonalInventory(),new ItemStack(Items.BONE_MEAL))>=1;}
    static boolean toolAllowed(NpcBase n,String kind,long now){
        var tool=n.getMainHandItem();
        if(NaturalIndustrySources.shearing(kind)?!tool.is(Items.SHEARS)
                :kind.equals(NaturalIndustrySources.ANGLING)?!tool.is(Items.FISHING_ROD):!(tool.is(net.minecraft.tags.ItemTags.SWORDS)))return false;
        if(tool.isDamageableItem()&&tool.getDamageValue()>=tool.getMaxDamage())return false;
        var r=RightsStateData.get(MinecraftApi.server(n));var id=ContainerIds.npcPersonal(n.getCharacterId());var owner=SubjectRef.character(n.getCharacterId());
        return r.context(id)!=null && r.context(id).defaultBeneficialOwner().equals(owner)
                && r.context(id).custodian().equals(owner)
                && IndustryToolTask.slotClaimsMatch(IndustryToolTask.claimsForSlot(r,id,n.getPersonalInventory().getContainerSize()),owner,ItemFingerprint.of(tool),tool.getCount())
                && r.reservedQuantity(id,ItemFingerprint.of(tool),null,now)==0
                && new RightsService(r).can(owner,AssetRight.USE,id,n.getPersonalInventory().getContainerSize(),ItemFingerprint.of(tool),now).status()==RightsVerdict.Status.ALLOW;
    }
    static boolean validSource(NpcBase n,EntityResourceActionData data,CompoundTag source){
        var level=(net.minecraft.server.level.ServerLevel)n.level();
        if(!source.getStringOr("Dimension", "").equals(level.dimension().identifier().toString())
                ||!source.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()))return false;
        String kind=source.getStringOr("Kind", "");
        if(kind.equals(NaturalIndustrySources.ANGLING)){
            var water=BlockPos.of(source.getLongOr("Water", 0L));
            var shore=BlockPos.of(source.getLongOr("Shore", 0L));
            return shore.distSqr(water)<=2&&SettlementIndustryAccess.local(n,water)&&SettlementIndustryAccess.local(n,shore)
                    &&NaturalIndustrySources.fishingWater(level,water)&&FieldworkRoutes.walkable(n,shore);
        }
        var entity=level.getEntity(source.read("Target", UUIDUtil.CODEC).orElseThrow());
        if(!(entity instanceof LivingEntity living)||!living.isAlive())return false;
        if(NaturalIndustrySources.shearing(kind)&&(!(living instanceof Sheep sheep)||!sheep.readyForShearing()))return false;
        return kind.equals(NaturalIndustrySources.WILD_SHEEP)
                ?NaturalIndustrySources.wildSheepAllowed(n,(Sheep)living,data):EntityResourceAuthority.allows(n,source,living);
    }

    private static TaskTickResult fish(NpcEntityExecutionContext context,NpcBase worker,EntityResourceActionData data,
                                       CompoundTag action,CompoundTag source){
        var water=BlockPos.of(source.getLongOr("Water", 0L));
        var shore=BlockPos.of(source.getLongOr("Shore", 0L));
        var now=context.gameTime();
        if(!worker.onGround()||worker.distanceToSqr(shore.getX()+.5,shore.getY(),shore.getZ()+.5)>1){
            if(!FieldworkRoutes.walkable(worker,shore)||!FieldworkRoutes.move(worker,shore))
                return waitFor("recorded river or ocean shore blocked",now);
            return TaskTickResult.running("walking to the observed river or ocean shore");
        }
        for(var fish:List.of(Items.COD,Items.SALMON,Items.PUFFERFISH,Items.TROPICAL_FISH))
            if(FieldworkDepositTaskExecutor.capacity(worker.getPersonalInventory(),new ItemStack(fish))<1)
                return waitFor("insufficient room for actual fish",now);
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(water.getX()+.5,water.getY()+.8,water.getZ()+.5);
        var labor=ProductionLaborData.get(MinecraftApi.server(worker));
        var id=action.read("Id", UUIDUtil.CODEC).orElseThrow();
        if(labor.worked(id)==0)worker.swing(InteractionHand.MAIN_HAND);
        if(labor.tick(id,NaturalIndustrySources.ANGLING_TICKS,now)<NaturalIndustrySources.ANGLING_TICKS){
            if(now%40==0)context.level().sendParticles(ParticleTypes.SPLASH,water.getX()+.5,water.getY()+.95,water.getZ()+.5,3,.15,.04,.15,.03);
            return TaskTickResult.running("real shore work with a fishing rod; waiting for a bite");
        }
        var rod=worker.getMainHandItem();
        action.putString("TargetBefore","loaded river or ocean source water:"+water.asLong());
        action.put("ToolBefore",ItemFingerprint.of(rod).save());
        action.putInt("ToolDamageBefore",rod.getDamageValue());
        action.putString("State","ACTING");data.putAction(action);
        var result=NaturalIndustrySources.catchFish(context.level(),worker,water);
        if(result.size()!=1||result.getFirst().getCount()!=1||!List.of(Items.COD,Items.SALMON,Items.PUFFERFISH,Items.TROPICAL_FISH).contains(result.getFirst().getItem()))
            return review(data,action,"fishing table did not return one supported fish");
        var caught=result.getFirst();
        worker.swing(InteractionHand.MAIN_HAND);
        var drop=new ItemEntity(context.level(),worker.getX(),worker.getY()+.2,worker.getZ(),caught);
        drop.setPickUpDelay(10);
        if(!context.level().addFreshEntity(drop))return review(data,action,"original caught item could not enter the world");
        var rights=RightsStateData.get(MinecraftApi.server(worker));
        var actor=SubjectRef.character(worker.getCharacterId());
        var dropId=IndustryTransfers.id("native-drop:"+drop.getUUID());
        rights.ensureContext(dropId,"native-output:"+id,actor,actor,AccessPolicy.PRIVATE);
        var row=new CompoundTag();row.store("Id", UUIDUtil.CODEC,drop.getUUID());row.store("Container", UUIDUtil.CODEC,dropId);
        row.put("Item",ItemFingerprint.of(caught).save());row.putInt("Quantity",1);
        var drops=new ListTag();drops.add(row);
        IndustryToolTask.damageOwnedHand(worker,1);
        action.putInt("ToolDamageAfter",worker.getMainHandItem().getDamageValue());
        action.put("Drops",drops);action.putString("State","ACTED");action.putLong("UpdatedAt",now);data.putAction(action);
        return TaskTickResult.running("caught a real fish; lawful pickup and deposit follow");
    }
    private static TaskTickResult review(EntityResourceActionData d,CompoundTag a,String reason){a.putString("State","REVIEW");a.putString("Detail",reason);d.putAction(a);return TaskTickResult.failed("native REVIEW: "+reason);}
    private static TaskTickResult waitFor(String reason,long now){return TaskTickResult.blocked(reason,new NpcPlanBlocker("NATIVE_WAIT",reason,now+100,null));}
    private static void approach(NpcBase n,BlockPos p){
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.route(n,p))return;
        var stands=new ArrayList<BlockPos>();for(var q:BlockPos.betweenClosed(p.offset(-1,-1,-1),p.offset(1,1,1)))
            if(FieldworkRoutes.walkable(n,q))stands.add(q.immutable());
        stands.sort(Comparator.comparingDouble(n.blockPosition()::distSqr));
        // Known moving animals can be beyond the native follow range. Reuse the existing
        // bounded physical route; the caller still checks authority, distance and sight.
        for(var q:stands)if(FieldworkRoutes.move(n,q))return;
    }
}
