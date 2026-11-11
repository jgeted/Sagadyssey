package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcPlanNbtCodec;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.server.MinecraftServer;
import java.lang.ref.WeakReference;
import java.util.*;

/** L0 owns execution. L1 advances bounded needs only; all physical obligations resume at L0. */
public final class NpcLodRuntime {
    private static final Map<MinecraftServer,Map<UUID,WeakReference<NpcBase>>> LOADED=new WeakHashMap<>();
    private static Map<UUID,WeakReference<NpcBase>> actors(MinecraftServer s) {return LOADED.computeIfAbsent(s,k->new HashMap<>());}
    public static int committed(NpcPlan plan) {
        int index=-1;if(plan!=null)for(var step:plan.steps()){if(step.status()!=NpcPlanStepStatus.SUCCEEDED)break;index=step.index();}return index;
    }
    public static LodCheckpoint capture(NpcBase npc,boolean unloaded,String result) {
        var server=MinecraftApi.server(npc);long now=server.overworld().getGameTime();var plan=NpcBrainRuntime.data(server).active(npc.getUUID()).orElse(null);
        var rights=RightsStateData.get(server);Map<UUID,Long> assets=new HashMap<>();Map<UUID,String> orders=new HashMap<>();Set<UUID> held=new HashSet<>();
        assets.put(ContainerIds.npcPersonal(npc.getCharacterId()),npc.getPersonalInventory().revision());
        addAsset(rights,assets,ContainerIds.npcCargo(npc.getCharacterId()));
        for(var order:ProductionOrderData.get(server).ordersForWorker(npc.getCharacterId()))if(!order.terminal()) {
            var revision=order.save();int worked=com.jgeted.sagadyssey.social.production.ProductionLaborData.get(server).worked(order.id());
            revision.putInt("ActualWorked",worked);orders.put(order.id(),LodFingerprints.of(revision));
            assets.put(ContainerIds.named("lod-labor:"+order.id()),(long)worked);addAsset(rights,assets,order.targetContainerId());
        }
        for(var r:rights.reservations())if(r.reservedFor().equals(SubjectRef.character(npc.getCharacterId()))) {held.add(r.id());addAsset(rights,assets,r.containerId());}
        if(plan!=null)for(var r:NpcBrainRuntime.reservations(server).reservationsForPlan(plan.id()))held.add(ContainerIds.named("lod-control:"+r.key()));
        return new LodCheckpoint(npc.getUUID(),npc.getCharacterId(),npc.level().dimension().identifier().toString(),npc.blockPosition().asLong(),
                plan==null?null:plan.id(),plan==null?"":LodFingerprints.of(NpcPlanNbtCodec.save(plan)),committed(plan),assets,orders,held,LodFingerprints.inventory(npc),now,now,unloaded,result);
    }
    private static void addAsset(RightsStateData rights,Map<UUID,Long> assets,UUID id) {var context=rights.context(id);if(context!=null)assets.put(id,context.revision());}
    public static boolean loaded(NpcBase npc) {
        if(npc.isRemoved()||!npc.isAlive()||MinecraftApi.server(npc)==null)return false;
        var server=MinecraftApi.server(npc);var live=actors(server);var reference=live.get(npc.getUUID());
        if(reference!=null&&reference.get()==npc)return !review(NpcLodData.get(server).find(npc.getUUID()));
        var data=NpcLodData.get(server);var old=data.find(npc.getUUID());
        var originalBody=data.bodyForPerson(npc.getCharacterId());
        if(originalBody!=null&&!originalBody.equals(npc.getUUID())){npc.getNavigation().stop();return false;}
        // Never manufacture a replacement body for a checkpoint or silently rebind a person.
        for(var ref:live.values()) {var other=ref.get();if(other!=null&&other!=npc&&!other.isRemoved()&&other.getCharacterId().equals(npc.getCharacterId())) {npc.getNavigation().stop();return false;}}
        if(old!=null&&review(old))return false;
        var current=capture(npc,false,"REVALIDATED: 原实体加载，执行器重新核验权威、目标与实物；不回放离线作业");
        boolean rollback=old!=null&&(!old.person().equals(current.person())
                || Objects.equals(old.plan(),current.plan())&&current.lastCommittedStep()<old.lastCommittedStep());
        if(old!=null)for(var revision:old.assetRevisions().entrySet()) {
            Long latest=current.assetRevisions().get(revision.getKey());
            if(latest!=null&&latest<revision.getValue())rollback=true;
        }
        // An unloaded body cannot legitimately change its own inventory or plan without a receipt.
        if(old!=null&&old.unloaded()&&(!old.inventoryDigest().equals(current.inventoryDigest())
                || !Objects.equals(old.plan(),current.plan())
                || !old.planRevision().equals(current.planRevision())))rollback=true;
        if(rollback) {
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.warn("LOD_REVIEW body={} inventoryChanged={} planChanged={} revisionChanged={} committed={}->{}",npc.getUUID(),!old.inventoryDigest().equals(current.inventoryDigest()),!Objects.equals(old.plan(),current.plan()),!old.planRevision().equals(current.planRevision()),old.lastCommittedStep(),current.lastCommittedStep());
            // Keep the old identity/boundary; do not overwrite evidence with the conflicting actor.
            data.put(new LodCheckpoint(old.body(),old.person(),old.dimension(),old.position(),old.plan(),old.planRevision(),old.lastCommittedStep(),old.assetRevisions(),old.orderRevisions(),old.reservations(),old.inventoryDigest(),old.capturedAt(),old.summarizedAt(),old.unloaded(),"REVIEW: 身份、资产或已提交步骤不一致；暂停执行，不补造实体或物品"));
            npc.getNavigation().stop();return false;
        }
        if(old!=null&&old.unloaded())advanceNeeds(server,old,server.overworld().getGameTime());
        data.put(current);live.put(npc.getUUID(),new WeakReference<>(npc));
        if(old!=null&&old.unloaded())NaturalVillageEconomyData.originalReturned(npc);
        return true;
    }
    public static boolean review(LodCheckpoint row) {return row!=null&&row.reconciliation().startsWith("REVIEW:");}
    public static void forgetLoaded(NpcBase npc){if(MinecraftApi.server(npc)!=null){actors(MinecraftApi.server(npc)).remove(npc.getUUID());NpcNavigationQueue.forget(MinecraftApi.server(npc),npc.getUUID());NpcPerceptionQueue.forget(MinecraftApi.server(npc),npc.getUUID());}}
    public static void unload(NpcBase npc,boolean destroyed) {
        var server=MinecraftApi.server(npc);if(server==null)return;
        // A delayed leave callback for an old object must not suspend its already-loaded original.
        var reference=actors(server).get(npc.getUUID());var current=reference==null?null:reference.get();
        if(current!=null&&current!=npc&&!current.isRemoved())return;
        actors(server).remove(npc.getUUID());NpcLodBudgets.planning(server).forget(npc.getUUID());
        NpcNavigationQueue.forget(server,npc.getUUID());
        NpcPerceptionQueue.forget(server,npc.getUUID());
        var data=NpcLodData.get(server);
        if(destroyed){
            if(NpcBrainRuntime.data(server).active(npc.getUUID()).isPresent())NpcBrainRuntime.cancel(npc,com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason.TARGET_INVALID,"实体已不可恢复移除，正式结束活动计划");
            data.remove(npc.getUUID());NpcBrainRuntime.suspend(npc);return;
        }
        if(NpcBrainRuntime.isPlanned(npc)&&!review(data.find(npc.getUUID()))) {
            data.put(capture(npc,true,"L1: 卸载，保留原订单/实体/收据，物理作业待加载"));NpcBrainRuntime.suspend(npc);
        }
    }
    public static void summarize(MinecraftServer server) {
        long start=System.nanoTime(),now=server.overworld().getGameTime();var data=NpcLodData.get(server);
        // At most 8 rows per tick, FIFO by due time; no scan of every unloaded NPC.
        for(var id:data.due(now,8)) {
            var row=data.find(id);if(row==null||review(row))continue;
            var ref=actors(server).get(id);var entity=ref==null?null:ref.get();
            if(entity!=null&&!entity.isRemoved())continue;
            long until=row.summarizedAt()+Math.max(0,now-row.summarizedAt())/200*200;
            advanceNeeds(server,row,until);data.put(row.summary(until));
        }
        LodMetrics.record(server,LodMetrics.Kind.OFFLINE,System.nanoTime()-start);
    }
    public static int restingHunger(long from,long until) {if(from<0||until<from)throw new IllegalArgumentException("invalid summary time");return (int)Math.min(1000,(until-from)/200*2);}
    private static void advanceNeeds(MinecraftServer server,LodCheckpoint row,long until) {
        if(NaturalVillageEconomyData.controls(server,row.person()))return;
        var life=NpcLifeData.get(server);var needs=life.needs(row.person());
        if(needs!=null&&until>=needs.lastUpdatedAt())life.increaseHunger(row.person(),restingHunger(Math.max(row.summarizedAt(),needs.lastUpdatedAt()),until),until);
    }
}
