package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Named local wood prerequisites. A child is a real manufacturing demand, never a
 * transfer into the same container or a synthetic receipt for its smaller parent. */
final class LocalWorkshopNeeds {
    private LocalWorkshopNeeds() { }
    static boolean child(NeedRequest need) {
        return need.key().purpose().startsWith("CHARACTER:local-workshop:");
    }
    static boolean eligible(NeedRequest need,long now) {
        return !child(need) && !LocalSupplierRules.supplier(need) && !need.status().terminal()
                && need.status()!=NeedRequestStatus.DELIVERED && need.status()!=NeedRequestStatus.BLOCKED_CYCLE
                && !need.detail().startsWith("PRODUCTION_REVIEW:") && need.remaining()>0
                && need.reserved()==0 && need.inTransit()==0 && need.key().deadline()>now;
    }
    static boolean activeChild(NeedRequest need,UUID actor,UUID stock,ItemFingerprint item) {
        return child(need) && need.key().requester().equals(actor) && need.key().destination().equals(stock)
                && need.key().item().equals(item) && !need.status().terminal() && need.status()!=NeedRequestStatus.DELIVERED;
    }
    static boolean expireUncommitted(NeedRequest need,boolean bound,boolean unresolved,long now) {
        return child(need) && !need.status().terminal() && need.status()!=NeedRequestStatus.DELIVERED
                && need.key().deadline()<=now && !bound && !unresolved && need.reserved()==0 && need.inTransit()==0;
    }
    static boolean openWithoutEffects(ProductionOrder order,boolean batchExists,SubjectRef actor,UUID stock) {
        return order!=null && order.status()==ProductionOrderStatus.OPEN && !batchExists
                && order.outputOwner().equals(actor) && order.targetContainerId().equals(stock);
    }

    static void aggregate(MinecraftServer server,long now) {
        var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);
        var orders=ProductionOrderData.get(server);var batches=ProductionBatchData.get(server);
        Set<UUID> unresolved=new HashSet<>();
        for(var route:flows.routes())if(!route.closed()||route.pending()!=null||route.stage()==SupplyFlowData.Stage.REVIEW)
            unresolved.add(route.need());
        for(var old:needs.requests())if(expireUncommitted(old,flows.production(old.id())!=null,unresolved.contains(old.id()),now))
            needs.transition(old.id(),NeedRequestStatus.CANCELLED,now,"local wood intention expired without production or transfer effects; history retained");
        // One snapshot, at most two named edges; generated children are never replanned as roots.
        var snapshot=needs.requests();
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities()) {
            if(!(entity instanceof NpcBase worker)||!worker.isAlive()||worker.getProfession()!=NpcProfession.WORKER
                    ||!EntityResourceTask.enabled(worker))continue;
            var life=NpcLifeData.get(server).record(worker.getCharacterId());
            if(life==null||life.privateStoragePosition()==null)continue;
            var actor=SubjectRef.character(worker.getCharacterId());
            var stock=flows.pointOrNull(ProductionContainerIds.resolve(server,life.dimension(),life.privateStoragePosition()));
            if(stock==null||!stock.owner().equals(actor)||!SupplyFlowService.validOwner(server,stock)
                    ||SupplyFlowService.container(server,stock)==null||!flows.known(actor).contains(stock.containerId()))continue;
            var context=RightsStateData.get(server).context(stock.containerId());
            boolean fixture=SettlementIndustryAccess.fixture(worker,"sagadyssey.industry.native");
            if(context==null||!context.custodian().equals(actor)||!fixture&&!SettlementIndustryAccess.local(worker,stock.position()))continue;
            for(var previous:snapshot) {
                var parent=needs.request(previous.id());var scope=flows.demand(parent.id());
                if(!eligible(parent,now)||unresolved.contains(parent.id())||scope==null||!scope.principal().equals(actor)
                        ||!scope.destination().equals(stock.containerId())||needs.dependencies(parent.id()).size()>=NeedRequestData.MAX_DEPENDENCIES)continue;
                UUID originalId=flows.production(parent.id());
                int materialAt=parent.key().purpose().indexOf(":production:");
                if(materialAt>=0) {
                    try { originalId=UUID.fromString(parent.key().purpose().substring(materialAt+12)); }
                    catch(IllegalArgumentException malformed){continue;}
                } else if(!parent.key().item().itemId().equals("minecraft:fishing_rod")
                        &&WoodMaterialRecipes.outputQuantity(parent.key().item())!=1)continue;
                if(originalId!=null) {
                    var original=orders.byId(originalId).orElse(null);
                    if(!openWithoutEffects(original,original!=null&&batches.batch(original.batchId())!=null,actor,stock.containerId()))continue;
                    if(!SupplyFlowService.localMaterialRecipe(server,original))continue;
                    if(materialAt>=0&&original.inputs().stream().noneMatch(m->m.fingerprint().equals(parent.key().item())))continue;
                }
                var plan=WoodMaterialRecipes.upstream(worker,stock,parent.key().item(),parent.remaining(),now);
                if(plan.isEmpty()||plan.size()>2)continue;
                // An existing final prerequisite already owns its upstream plan. Do not create
                // another plank batch merely because its original logs are now reserved/consumed.
                var finalSpec=plan.getLast();
                var existing=needs.requests().stream().filter(n->activeChild(n,actor.id(),stock.containerId(),finalSpec.output().fingerprint())).toList();
                if(!existing.isEmpty()) {
                    if(existing.size()==1) {
                        var shared=existing.getFirst();
                        if(shared.requested()==finalSpec.output().quantity()&&shared.reserved()==0&&shared.inTransit()==0
                                &&shared.status()!=NeedRequestStatus.BLOCKED_CYCLE&&shared.status()!=NeedRequestStatus.UNREACHABLE
                                &&shared.key().deadline()>now&&!unresolved.contains(shared.id())&&!needs.dependencies(parent.id()).contains(shared.id()))
                            needs.dependOn(parent.id(),shared.id(),now);
                    }
                    continue;
                }
                UUID prerequisite=null;boolean complete=true;
                for(var spec:plan) {
                    var item=spec.output().fingerprint();
                    if(!fixture&&SupplyFlowService.knownIndustryStation(worker,stock,item,false)==null){complete=false;break;}
                    var matching=needs.requests().stream().filter(n->activeChild(n,actor.id(),stock.containerId(),item)).toList();
                    if(matching.size()>1){complete=false;break;}
                    NeedRequest child=matching.isEmpty()?null:matching.getFirst();
                    if(child!=null&&(child.requested()!=spec.output().quantity()||child.key().deadline()<=now
                            ||child.status()==NeedRequestStatus.UNREACHABLE||child.status()==NeedRequestStatus.BLOCKED_CYCLE
                            ||child.reserved()>0||child.inTransit()>0||unresolved.contains(child.id()))){complete=false;break;}
                    if(child==null) {
                        // A different bound original demand owns its batch already. Wait for that physical stock.
                        boolean producing=needs.requests().stream().anyMatch(n->!n.status().terminal()
                                &&n.status()!=NeedRequestStatus.DELIVERED&&n.key().destination().equals(stock.containerId())
                                &&n.key().item().equals(item)&&flows.production(n.id())!=null);
                        if(producing){complete=false;break;}
                        child=SupplyFlowService.request(server,actor,"local-workshop:"+parent.id()+":"+item.itemId(),stock,
                                item,spec.output().quantity(),now,Math.min(parent.key().deadline(),now+24000));
                    }
                    if(prerequisite!=null&&!needs.dependencies(child.id()).contains(prerequisite)) {
                        if(needs.dependencies(child.id()).size()>=NeedRequestData.MAX_DEPENDENCIES
                                ||!needs.dependOn(child.id(),prerequisite,now)){complete=false;break;}
                    }
                    prerequisite=child.id();
                }
                if(complete&&prerequisite!=null&&!needs.dependencies(parent.id()).contains(prerequisite))
                    needs.dependOn(parent.id(),prerequisite,now);
            }
        }
    }
}
