package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.core.structure.NaturalFarmRules;
import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkDepositTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.logistics.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import java.util.*;

/** Authority-scoped demand aggregation and stock matching; never scans unknown remote settlements. */
public final class SupplyFlowService {
    private static final Map<MinecraftServer,Long> LAST=new WeakHashMap<>();
    // Diagnostic de-duplication only, never an authority or retry-budget record.
    private static final Map<MinecraftServer,Map<UUID,String>> EMERGENCY_DIAGNOSTICS=new WeakHashMap<>();
    // Scheduling only: absence never grants stock or renews the durable attempt/deadline budget.
    private static final Map<MinecraftServer,Map<UUID,Long>> STOCK_RECOVERY_POLL=new WeakHashMap<>();
    private SupplyFlowService(){}
    public static UUID receipt(UUID id,String stage){return ContainerIds.named("need:"+id+":"+stage);}
    public static ServerLevel level(MinecraftServer server,String dimension){
        var id=net.minecraft.resources.Identifier.tryParse(dimension);return id==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
    }
    public static Container container(MinecraftServer server,FieldworkStorage point){
        if(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.protects(server,point.dimension(),point.position()))return null;
        var level=level(server,point.dimension());return level==null||!level.hasChunkAt(point.position())?null:WarehouseContainers.asContainer(level.getBlockEntity(point.position()));
    }
    public static boolean validOwner(MinecraftServer server,FieldworkStorage point){
        var context=RightsStateData.get(server).context(point.containerId());
        return context!=null&&context.defaultBeneficialOwner().equals(point.owner());
    }
    public static int count(Container inventory,ItemFingerprint item){
        int count=0;if(inventory!=null)for(int slot=0;slot<inventory.getContainerSize();slot++)if(item.matches(inventory.getItem(slot)))count+=inventory.getItem(slot).getCount();return count;
    }
    /** Only uncontested, unencumbered owned/granted stacks qualify; physical counts are rechecked at pickup. */
    public static int available(MinecraftServer server,FieldworkStorage point,SubjectRef principal,ItemFingerprint item,long now){
        if(!validOwner(server,point))return 0;var inventory=container(server,point);if(inventory==null)return 0;
        var rights=RightsStateData.get(server);var service=new RightsService(rights);int total=0;
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var stack=inventory.getItem(slot);if(!item.matches(stack))continue;
            var claim=rights.claimAt(point.containerId(),slot,item);
            if(claim!=null&&(!claim.encumbrances().isEmpty()||!claim.reservations().isEmpty()||claim.quantity()!=stack.getCount()))continue;
            if(service.can(principal,AssetRight.TRANSFER,point.containerId(),slot,item,now).status()==RightsVerdict.Status.ALLOW)total+=stack.getCount();
        }
        return Math.max(0,total-rights.reservedQuantity(point.containerId(),item,null,now)-com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.incomingCropReserve(server,point,principal,item));
    }
    public static NeedRequest request(MinecraftServer server,SubjectRef principal,String purpose,FieldworkStorage destination,
                                      ItemFingerprint item,int quantity,long start,long deadline){
        var flows=SupplyFlowData.get(server);flows.register(destination);
        var request=NeedRequestData.get(server).openOrUpdate(new NeedRequest.Key(principal.id(),principal.type()+":"+purpose,item,destination.containerId(),start,deadline),quantity,32,Math.max(start,server.overworld().getGameTime()));
        flows.demand(request.id(),principal,destination.containerId());return request;
    }
    public static void tick(NpcBase npc){
        if(com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(MinecraftApi.server(npc),npc.getCharacterId()))return;
        var server=MinecraftApi.server(npc);long now=server.overworld().getGameTime();
        long previous=LAST.getOrDefault(server,-100L);if(now>=previous&&now-previous<100)return;LAST.put(server,now);
        aggregate(server,now);match(server,now);
    }
    public static void aggregate(MinecraftServer server,long now){
        var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);
        reconcileHouseholdDestinations(server,now);
        FurnaceWorkAdapter.reservePendingInputs(server,now);
        openLocalKitchenNeeds(server,now);
        Set<UUID> plannedCharacters = plannedCharacters(server);
        for(var household:HouseholdData.get(server).households()){
            if(household.sharedStorage()==null||household.members().stream().noneMatch(m->plannedCharacters.contains(m.characterId())))continue;
            var principal=SubjectRef.household(household.id());
            var target=new FieldworkStorage(household.dimension(),household.sharedStorage(),ContainerIds.block("household_storage",household.dimension(),household.sharedStorage()),principal,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household_supply");
            RightsStateData.get(server).ensureContext(target.containerId(),target.dimension()+":"+target.position().asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
            var inventory=container(server,target);if(inventory==null||!validOwner(server,target))continue;
            flows.register(target);flows.reveal(principal,target.containerId());
            var snapshot=HouseholdData.get(server).provisionPlan(household.id()).inspect(inventory);
            householdDemand(server,principal,"food",target,new ItemFingerprint("minecraft:bread",0),snapshot.foodDeficit(),now);
            householdDemand(server,principal,"fuel",target,new ItemFingerprint("minecraft:coal",0),snapshot.fuelDeficit(),now);
            householdDemand(server,principal,"tools",target,new ItemFingerprint("minecraft:iron_pickaxe",0),snapshot.toolDeficit(),now);
        }
        // Explicit target policies can represent household, workshop, or institutional buffers.
        for(var policy:flows.policies()){
            var target=flows.point(policy.destination());var inventory=container(server,target);
            if(inventory==null||!validOwner(server,target))continue;
            int missing=Math.max(0,policy.target()-count(inventory,policy.item()));
            var current=needs.requests().stream().filter(r->r.key().purpose().equals(policy.principal().type()+":"+policy.category()+":"+policy.id())&&!r.status().terminal()&&r.status()!=NeedRequestStatus.DELIVERED).findFirst().orElse(null);
            if(current!=null&&current.key().deadline()<=now&&current.reserved()==0&&current.inTransit()==0){
                needs.transition(current.id(),NeedRequestStatus.CANCELLED,now,"储备请求时间窗已结束，按当前实物重新聚合");current=null;
            }
            // A linked production order has fixed inputs/output; changing its demand alone
            // could make its eventual receipt exceed the request. Leave that contract intact.
            if(current!=null&&flows.production(current.id())==null)
                needs.reconcileStockDeficit(current.id(),missing,now);
            else if(current==null&&missing>0)
                request(server,policy.principal(),policy.category()+":"+policy.id(),target,policy.item(),missing,now,now+24000);
        }
        // Production's exact missing-input requests bridge into one durable demand per order/specification.
        for(var legacy:ProductionMaterialRequestData.get(server).openRequests()){
            var order=ProductionOrderData.get(server).byId(legacy.orderId()).orElse(null);if(order==null||order.terminal())continue;
            if(now>=order.createdAt()+72000)continue;
            var originalContext=RightsStateData.get(server).context(order.targetContainerId());
            if(originalContext==null)continue;
            // Material replenishment must describe the same registered facility; it
            // cannot silently recast a household/institutional warehouse as PRIVATE.
            FieldworkStorage target;
            try{target=FurnaceWorkAdapter.recognized(order)?FurnaceWorkAdapter.inputPoint(server,order):new FieldworkStorage(order.targetContainerDimension(),order.targetContainerPosition(),order.targetContainerId(),order.outputOwner(),originalContext.accessPolicy(),TransferIntent.DELIVERY,order.token());}
            catch(IllegalStateException|NoSuchElementException invalidBinding){continue;}
            if(!validOwner(server,target))continue;
            var inventory=container(server,target);if(inventory==null)continue;
            var required=order.inputs().stream().filter(m->m.fingerprint().equals(legacy.material().fingerprint())).findFirst().orElse(null);if(required==null)continue;
            int missing=required.quantity()-count(inventory,required.fingerprint());if(missing<=0)continue;
            var r=request(server,order.outputOwner(),"production:"+order.id(),target,required.fingerprint(),legacy.material().quantity(),order.createdAt(),order.createdAt()+72000);
            for(var parent:needs.requests())if(linkedProductionOrder(server,parent,order.id(),now)&&!needs.dependencies(parent.id()).contains(r.id()))
                needs.dependOn(parent.id(),r.id(),now);
            // Learn only the institution's own registered facilities, never unrelated stock.
            for(var warehouse:LogisticsData.get(server).warehouses())if(order.outputOwner().equals(SubjectRef.institution(warehouse.settlementId()))){
                var point=new FieldworkStorage(warehouse.dimension(),warehouse.position(),warehouse.id(),order.outputOwner(),AccessPolicy.INSTITUTION,TransferIntent.DELIVERY,"institution_supply");
                RightsStateData.get(server).ensureContext(point.containerId(),point.dimension()+":"+point.position().asLong(),point.owner(),point.owner(),point.policy());flows.register(point);flows.reveal(order.outputOwner(),point.containerId());
            }
        }
        LocalWorkshopNeeds.aggregate(server,now);
        openLocalSupplierNeeds(server,now);
        // Settled production receipts, not elapsed time or inventory guesses, fulfill produced demands.
        for(var r:needs.requests()){
            if(r.key().purpose().contains(":production:")){
                UUID materialNeedId=r.id();
                int at=r.key().purpose().indexOf(":production:");
                var original=ProductionOrderData.get(server).byId(UUID.fromString(r.key().purpose().substring(at+12))).orElse(null);
                if(original!=null&&original.status()==ProductionOrderStatus.COMPLETED){
                    if(r.status()==NeedRequestStatus.DELIVERED)
                        needs.transition(r.id(),NeedRequestStatus.CONSUMED,now,"真实生产完成，缺料交付已被批次消耗");
                    else if((uncommittedUnitMaterialIsRedundant(server,r,original,ProductionBatchData.get(server).batch(original.batchId()))
                            ||uncommittedNativeMaterialIsRedundant(server,r,original))
                            &&flows.routes().stream().noneMatch(route->route.need().equals(materialNeedId)
                            &&(!route.closed()||route.pending()!=null||route.stage()==SupplyFlowData.Stage.REVIEW))){
                        needs.transition(r.id(),NeedRequestStatus.CANCELLED,now,
                                "原单件制造已实际消费投入并完成；取消未承诺的多余补料，保留历史数量与回执");
                    }
                }
            }
            UUID orderId=flows.production(r.id());if(orderId==null||r.status().terminal()||r.status()==NeedRequestStatus.DELIVERED)continue;
            var series=productionSeries(server,r,flows.demand(r.id()),now);if(series==null)continue;
            for(var order:series){
            r=needs.request(r.id());
            var batch=ProductionBatchData.get(server).batch(order.batchId());
            boolean nativeComplete=FurnaceWorkAdapter.completedDelivery(server,order);
            if(nativeComplete||batch!=null&&batch.status()==ProductionBatchStatus.COMPLETED){
                if(!nativeComplete&&(order.output().quantity()==1||WorkshopTask.recognized(server,order))&&!completedUnitMatches(order,batch)){
                    productionReview(needs,needs.request(r.id()),now,"原单件批次与制造意图不一致");continue;
                }
                if(now>=r.key().deadline()){
                    if(r.status()==NeedRequestStatus.OPEN||r.status()==NeedRequestStatus.WAITING_EXTERNAL)
                        needs.transition(r.id(),NeedRequestStatus.UNREACHABLE,now,"生产批次已完成但需求时间窗已结束，保留实物和批次供人工核对");
                    continue;
                }
                if(r.status()==NeedRequestStatus.WAITING_EXTERNAL)needs.transition(r.id(),NeedRequestStatus.OPEN,now,"真实生产批次已完成");
                needs.acknowledge(receipt(order.id(),"produced-reserve"),r.id(),NeedRequestData.Action.RESERVE,order.output().quantity(),now);
                needs.acknowledge(receipt(order.id(),"produced-dispatch"),r.id(),NeedRequestData.Action.DISPATCH,order.output().quantity(),now);
                needs.acknowledge(receipt(order.id(),"produced-delivery"),r.id(),NeedRequestData.Action.DELIVER,order.output().quantity(),now);
            }
            }
        }
        // Cancellation/expiry before pickup releases real leases. Carried goods retain their obligation.
        for(var route:flows.routes()){
            if(route.closed())continue;var r=needs.request(route.need());
            RightsStateData.get(server).renewReservations(route.token(),now,now+1200);
            boolean cancelled=r.key().purpose().contains(":production:")&&productionCancelled(server,r.key().purpose());
            if((cancelled||now>=r.key().deadline())&&route.stage()==SupplyFlowData.Stage.HELD&&route.pending()==null)release(server,route,now,"请求订单取消或到期");
        }
    }
    /** Household metadata may change without moving any inventory or rewriting an old order. */
    private static boolean currentHouseholdDestination(MinecraftServer server,NeedRequest request){
        String purpose=request.key().purpose();
        if(!purpose.equals("HOUSEHOLD:household:food")&&!purpose.equals("HOUSEHOLD:household:fuel")
                &&!purpose.equals("HOUSEHOLD:household:tools"))return true;
        var household=HouseholdData.get(server).byId(request.key().requester());
        return household!=null&&household.sharedStorage()!=null&&request.key().destination().equals(
                ContainerIds.block("household_storage",household.dimension(),household.sharedStorage()));
    }
    private static void reconcileHouseholdDestinations(MinecraftServer server,long now){
        var needs=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);
        var obsolete=needs.requests().stream().filter(r->!r.status().terminal()
                &&!currentHouseholdDestination(server,r)).toList();
        if(obsolete.isEmpty())return;
        // Group once instead of scanning every historical route for every old request.
        var routesByNeed=new HashMap<UUID,List<SupplyFlowData.Route>>();
        for(var route:flows.routes())routesByNeed.computeIfAbsent(route.need(),id->new ArrayList<>()).add(route);
        for(var snapshot:obsolete){
            var r=needs.request(snapshot.id());
            // The production contract (and any input delivery) remains independently binding.
            if(flows.production(r.id())!=null&&r.status()!=NeedRequestStatus.DELIVERED)continue;
            boolean uncertain=false;
            for(var route:routesByNeed.getOrDefault(r.id(),List.of())){
                if(route.stage()==SupplyFlowData.Stage.HELD&&route.pending()==null)
                    release(server,route,now,"家庭粮仓变更或家庭已解散，释放未取货预留");
                else if(route.pending()!=null||route.stage()==SupplyFlowData.Stage.CARRYING
                        ||route.stage()==SupplyFlowData.Stage.REVIEW)uncertain=true;
            }
            r=needs.request(r.id());
            if(uncertain||r.reserved()>0||r.inTransit()>0){
                if(r.status().canTransitionTo(NeedRequestStatus.UNREACHABLE))
                    needs.transition(r.id(),NeedRequestStatus.UNREACHABLE,now,
                            "家庭粮仓变更或家庭已解散，保留原目标与在途/待核对义务");
                continue;
            }
            needs.transition(r.id(),r.status()==NeedRequestStatus.DELIVERED
                    ?NeedRequestStatus.CLOSED:NeedRequestStatus.CANCELLED,now,
                    "原粮仓补给义务已结清，保留实际交付历史并结束剩余需求");
        }
    }
    /** One current snapshot per aggregation, with no persistent cache to become stale. */
    private static Set<UUID> plannedCharacters(MinecraftServer server) {
        Set<UUID> characters = new HashSet<>();
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof com.jgeted.sagadyssey.npc.entity.NpcBase npc
                        && npc.isAlive() && NpcBrainRuntime.isPlanned(npc))
                    characters.add(npc.getCharacterId());
            }
        }
        return characters;
    }
    private static boolean householdFood(NeedRequest request) {
        return request.key().purpose().equals("HOUSEHOLD:household:food");
    }

    private static List<NeedRequest> householdFoodHistory(NeedRequestData needs, UUID household) {
        return needs.requests().stream().filter(r -> householdFood(r) && r.key().requester().equals(household)).toList();
    }

    /** REVIEW is closed for scheduling, not settled custody. Terminal requests may still owe assets. */
    private static boolean uncertainFoodRoute(MinecraftServer server, NeedRequest request, long now) {
        var flows = SupplyFlowData.get(server);
        for (var route : flows.routes()) {
            if (!route.need().equals(request.id())) continue;
            if (route.pending() != null || route.stage() == SupplyFlowData.Stage.REVIEW) return true;
            if (!route.closed() && request.reserved() == 0 && request.inTransit() == 0) return true;
            if (route.closed() && !RightsStateData.get(server).reservationsForToken(route.token(), now).isEmpty()) return true;
        }
        return false;
    }

    private static boolean unsettledFood(MinecraftServer server, NeedRequest request, long now) {
        var flows = SupplyFlowData.get(server);
        if (request.reserved() > 0 || request.inTransit() > 0 || uncertainFoodRoute(server, request, now)) return true;
        if (flows.routes().stream().anyMatch(r -> r.need().equals(request.id()) && !r.closed())) return true;
        // Only the original production path may turn a fixed contract into completed delivery.
        return flows.production(request.id()) != null && request.status() != NeedRequestStatus.DELIVERED
                && request.status() != NeedRequestStatus.CLOSED && request.status() != NeedRequestStatus.CONSUMED;
    }

    private static void foodBlocked(NeedRequestData needs, NeedRequest request, long now, String reason) {
        if (request != null && request.status().canTransitionTo(NeedRequestStatus.UNREACHABLE))
            needs.transition(request.id(), NeedRequestStatus.UNREACHABLE, now, reason);
    }

    /** Selection observes existing knowledge/rights only; reservation and pickup still revalidate. */
    private static ItemFingerprint selectHouseholdFood(MinecraftServer server, SubjectRef principal,
                                                      FieldworkStorage target, ItemFingerprint preferred, long now) {
        var flows = SupplyFlowData.get(server);
        var candidates = new TreeSet<ItemFingerprint>(Comparator.comparing(ItemFingerprint::itemId)
                .thenComparingInt(ItemFingerprint::componentsHash));
        for (UUID source : flows.known(principal).stream().sorted().toList()) {
            var point = flows.point(source);
            if (source.equals(target.containerId()) || !point.dimension().equals(target.dimension())) continue;
            var inventory = container(server, point);
            if (inventory == null || !validOwner(server, point)) continue;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (!com.jgeted.sagadyssey.social.life.NpcFoodItems.canConsumePlanned(stack, false)) continue;
                var item = ItemFingerprint.of(stack);
                if (available(server, point, principal, item, now) > 0) candidates.add(item);
            }
        }
        if (preferred != null && candidates.contains(preferred)) return preferred;
        var bread = new ItemFingerprint("minecraft:bread", 0);
        return candidates.contains(bread) ? bread : candidates.isEmpty() ? null : candidates.first();
    }

    private static void foodWaiting(NeedRequestData needs, NeedRequest request, long now) {
        if (request.status() == NeedRequestStatus.UNREACHABLE)
            request = needs.transition(request.id(), NeedRequestStatus.OPEN, now, "重核家庭食品供给");
        if (request.status().canTransitionTo(NeedRequestStatus.WAITING_EXTERNAL))
            needs.transition(request.id(), NeedRequestStatus.WAITING_EXTERNAL, now, "无已知、已加载且有权取用的日常食品，保留原请求等待");
    }

    private static void householdFoodDemand(MinecraftServer server, SubjectRef principal, FieldworkStorage target,
                                            int deficit, long now) {
        var needs = NeedRequestData.get(server);
        var history = householdFoodHistory(needs, principal.id());
        var active = history.stream().filter(r -> !r.status().terminal()).toList();
        if (active.size() > 1) {
            active.forEach(r -> foodBlocked(needs, r, now, "同用途存在多条活动请求，停止自动选品和新建，保留履约待核对"));
            return;
        }
        var current = active.isEmpty() ? null : active.getFirst();
        if (history.stream().anyMatch(r -> r.status().terminal() && unsettledFood(server, r, now))) {
            foodBlocked(needs, current, now, "历史食品请求仍有未结义务，不新建替代请求");
            return;
        }
        if (current != null) {
            if (!current.key().destination().equals(target.containerId())) return;
            if (SupplyFlowData.get(server).production(current.id()) != null && current.status() != NeedRequestStatus.DELIVERED) return;
            if (uncertainFoodRoute(server, current, now)) {
                foodBlocked(needs, current, now, "食品转移或路线待核对，保留原品种、数量和回执");
                return;
            }
            if (unsettledFood(server, current, now)) {
                needs.reconcileStockDeficit(current.id(), deficit, now);
                return;
            }
            if (deficit == 0) {
                needs.reconcileStockDeficit(current.id(), 0, now);
                return;
            }
        } else if (deficit == 0) return;

        var selected = selectHouseholdFood(server, principal, target, current == null ? null : current.key().item(), now);
        if (current != null && current.status() != NeedRequestStatus.DELIVERED) {
            needs.reconcileStockDeficit(current.id(), deficit, now);
            current = needs.request(current.id());
            if (selected == null) { foodWaiting(needs, current, now); return; }
            if (selected.equals(current.key().item()) && current.key().deadline() > now) {
                if (current.status() == NeedRequestStatus.WAITING_EXTERNAL || current.status() == NeedRequestStatus.UNREACHABLE)
                    needs.transition(current.id(), NeedRequestStatus.OPEN, now, "原品种已有合法供给，沿用原请求");
                return;
            }
            if (current.status() == NeedRequestStatus.BLOCKED_CYCLE) return;
        }
        // A missing source creates at most one ordinary bread intent, retaining the existing waiting model.
        var item = selected == null ? new ItemFingerprint("minecraft:bread", 0) : selected;
        // Do not reuse a terminal identity if repeated calls change sources within the same game tick.
        if (history.stream().anyMatch(r -> r.key().item().equals(item) && r.key().destination().equals(target.containerId())
                && r.key().windowStart() == now)) return;
        if (current != null)
            needs.transition(current.id(), current.status() == NeedRequestStatus.DELIVERED ? NeedRequestStatus.CLOSED : NeedRequestStatus.CANCELLED,
                    now, "原食品义务已结清，按当前缺口和合法来源建立独立请求；保留原交付历史");
        request(server, principal, "household:food", target, item, deficit, now, now + 24000);
    }

    private static void householdDemand(MinecraftServer server,SubjectRef principal,String purpose,FieldworkStorage target,ItemFingerprint item,int deficit,long now){
        if (purpose.equals("food")) { householdFoodDemand(server, principal, target, deficit, now); return; }
        var needs=NeedRequestData.get(server);
        var existing=needs.requests().stream().filter(r->r.key().requester().equals(principal.id())&&r.key().purpose().equals("HOUSEHOLD:household:"+purpose)&&!r.status().terminal()).findFirst().orElse(null);
        // Do not apply the new pantry's observation to an old, still binding obligation.
        if(existing!=null&&!existing.key().destination().equals(target.containerId()))return;
        if(existing!=null&&existing.key().deadline()<=now&&existing.reserved()==0&&existing.inTransit()==0&&existing.status()!=NeedRequestStatus.DELIVERED){
            needs.transition(existing.id(),NeedRequestStatus.CANCELLED,now,"家庭请求时间窗结束，核对当前缺口后进入新时间窗");existing=null;
        }
        if(existing!=null&&existing.status()==NeedRequestStatus.DELIVERED){
            if(deficit<1)return;
            needs.transition(existing.id(),NeedRequestStatus.CLOSED,now,"已入家庭库存，下一轮为新的消耗需求");
            existing=null;
        }
        if(existing==null){
            if(deficit>0)request(server,principal,"household:"+purpose,target,item,deficit,now,now+24000);
        }else if(SupplyFlowData.get(server).production(existing.id())==null){
            // Household deficits are category-based observations of the real pantry.
            // Preserve fixed production contracts, leases and actual carried goods.
            needs.reconcileStockDeficit(existing.id(),deficit,now);
        }
    }
    public static void merchantDelivered(MinecraftServer server,MerchantSupplyData.Offer offer,UUID transaction,int quantity,long now){
        var needs=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);int remaining=quantity;
        for(var r:needs.requests()){
            if(remaining<1)break;
            if(r.status().terminal()||r.status()==NeedRequestStatus.DELIVERED||r.status()==NeedRequestStatus.BLOCKED_CYCLE
                    ||!r.key().destination().equals(offer.point())||!r.key().item().equals(offer.item())||r.remaining()<1)continue;
            int delivered=Math.min(remaining,r.remaining());
            if(r.status()==NeedRequestStatus.WAITING_EXTERNAL||r.status()==NeedRequestStatus.UNREACHABLE){
                if(r.reserved()>0||r.inTransit()>0)continue;needs.transition(r.id(),NeedRequestStatus.OPEN,now,"真实商人交货回执");}
            String base=transaction+":"+r.id();
            needs.acknowledge(receipt(transaction,base+":reserve"),r.id(),NeedRequestData.Action.RESERVE,delivered,now);
            needs.acknowledge(receipt(transaction,base+":dispatch"),r.id(),NeedRequestData.Action.DISPATCH,delivered,now);
            needs.acknowledge(receipt(transaction,base+":delivery"),r.id(),NeedRequestData.Action.DELIVER,delivered,now);
            remaining-=delivered;
        }
    }
    private static boolean productionCancelled(MinecraftServer server,String purpose){
        int at=purpose.indexOf(":production:");try{var order=ProductionOrderData.get(server).byId(UUID.fromString(purpose.substring(at+12))).orElse(null);return order==null||order.status()==ProductionOrderStatus.CANCELLED||order.status()==ProductionOrderStatus.FAILED;}catch(IllegalArgumentException e){return false;}
    }
    /**
     * Server-thread publication: each ledger validates before invoking the next publisher.
     * Expected validation failures leave no demand receipt, route or logistics order behind.
     * This does not claim atomic disk saves across separate SavedData files.
     */
    static boolean reserveRoute(RightsStateData rights, NeedRequestData needs, SupplyFlowData flows,
                                LogisticsData logistics, AssetReservation reservation,
                                SupplyFlowData.Route route, int physical, long now) {
        if (!rights.reserve(reservation, physical, now)) return false;
        try {
            needs.acknowledgeAndPublish(receipt(route.id(), "reserve"), route.need(),
                    NeedRequestData.Action.RESERVE, route.quantity(), now,
                    () -> flows.createRouteWithOrder(route,
                            () -> logistics.createManagedOrder(route.id(), route.source(),
                                    route.destination(), reservation.fingerprint(), route.quantity(),
                                    route.principal().id(), route.need(), now)));
            return true;
        } catch (RuntimeException exception) {
            rights.releaseReservations(reservation.token());
            throw exception;
        }
    }

    /** Checks the durable opportunity before touching rights; replay must never recreate a released lease. */
    static boolean reserveNaturalEmergencyRoute(RightsStateData rights, NeedRequestData needs, SupplyFlowData flows,
                                                LogisticsData logistics, AssetReservation reservation,
                                                SupplyFlowData.Route route, int physical, long now) {
        var need = needs.request(route.need());
        if (!route.id().equals(NeedRequestData.emergencyRouteId(need.id()))
                || !route.principal().equals(SubjectRef.household(need.key().requester()))
                || !route.destination().equals(need.key().destination())
                || !reservation.id().equals(receipt(route.id(), "lease"))
                || !reservation.token().equals(route.token()) || !reservation.containerId().equals(route.source())
                || !reservation.fingerprint().equals(need.key().item()) || reservation.quantity() != route.quantity()
                || route.stage() != SupplyFlowData.Stage.HELD || route.carrierEntity() != null || route.pending() != null)
            throw new IllegalStateException("invalid natural emergency route publication identity");
        var previous = flows.routeOrNull(route.id()); var order = logistics.order(route.id());
        if (needs.emergencyReserved(need.id())) {
            if (previous == null || order == null || !previous.need().equals(route.need())
                    || !previous.source().equals(route.source()) || !previous.destination().equals(route.destination())
                    || !previous.principal().equals(route.principal()) || previous.quantity() != route.quantity()
                    || !order.sourceWarehouseId().equals(route.source()) || !order.destinationWarehouseId().equals(route.destination())
                    || !order.itemId().equals(need.key().item().itemId()) || order.componentsHash() != need.key().item().componentsHash()
                    || order.requestedCount() != route.quantity() || !Objects.equals(order.needRequestId(), route.need())
                    || !Objects.equals(order.createdByCharacter(), route.principal().id()))
                throw new IllegalStateException("emergency receipt has inconsistent linked route/order; review required");
            needs.acknowledgeEmergencyReserveAndPublish(need.id(), route.quantity(), now,
                    () -> { throw new IllegalStateException("spent emergency opportunity must never publish again"); });
            return false;
        }
        if (previous != null || order != null || rights.reservations().stream().anyMatch(r -> r.token().equals(route.token())))
            throw new IllegalStateException("emergency linked state exists without its reserve receipt; review required");
        if (flows.production(need.id()) != null || need.reserved() != 0 || need.inTransit() != 0) return false;
        for (var old : flows.routes()) if (old.need().equals(need.id())
                && (!old.closed() || old.stage() == SupplyFlowData.Stage.REVIEW || old.pending() != null
                || !rights.reservationsForToken(old.token(), now).isEmpty())) return false;
        if (!rights.reserve(reservation, physical, now)) return false;
        try {
            needs.acknowledgeEmergencyReserveAndPublish(need.id(), route.quantity(), now,
                    () -> flows.createRouteWithOrder(route,
                            () -> logistics.createManagedOrder(route.id(), route.source(), route.destination(),
                                    reservation.fingerprint(), route.quantity(), route.principal().id(), route.need(), now)));
            return true;
        } catch (RuntimeException exception) {
            // This token had no prior receipt, route, order or lease; never release an old duty here.
            rights.releaseReservations(reservation.token());
            throw exception;
        }
    }

    /** Observe spent opportunities even when the ordinary matcher would skip a full/terminal request. */
    private static boolean inspectSpentEmergency(MinecraftServer server, NeedRequest request) {
        if (request.attempts() != 32 || request.maxAttempts() != 32
                || !request.key().purpose().equals("HOUSEHOLD:household:natural-diet:" + request.key().requester())) return false;
        String issue = null;
        try {
            int quantity = NeedRequestData.get(server).emergencyReservedQuantity(request.id());
            if (quantity == 0) return false;
            UUID routeId = NeedRequestData.emergencyRouteId(request.id());
            var route = SupplyFlowData.get(server).routeOrNull(routeId); var order = LogisticsData.get(server).order(routeId);
            if (route == null || order == null) issue = "固定紧急预留回执存在，但原路线或物流订单缺失；保留原账，禁止再次发布";
            else if (!route.need().equals(request.id()) || route.quantity() != quantity
                    || !route.principal().equals(SubjectRef.household(request.key().requester()))
                    || !route.destination().equals(request.key().destination())
                    || !order.sourceWarehouseId().equals(route.source()) || !order.destinationWarehouseId().equals(route.destination())
                    || order.requestedCount() != quantity || !Objects.equals(order.needRequestId(), request.id())
                    || !Objects.equals(order.createdByCharacter(), route.principal().id())
                    || !order.itemId().equals(request.key().item().itemId()) || order.componentsHash() != request.key().item().componentsHash())
                issue = "固定紧急预留回执、原路线与物流订单身份或数量不一致；保留原账，禁止再次发布";
        } catch (RuntimeException error) { issue = "紧急回执关联无法只读核对：" + error.getMessage(); }
        var seen = EMERGENCY_DIAGNOSTICS.computeIfAbsent(server, ignored -> new HashMap<>());
        if (issue == null) seen.remove(request.id());
        else if (!issue.equals(seen.put(request.id(), issue)))
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.warn("NATURAL_EMERGENCY_REVIEW need={} reason={} repaired=false opportunityReissued=false", request.id(), issue);
        return true;
    }

    /** An exhausted original v3 demand may publish one real route, never renew its age or ordinary retry budget. */
    private static void tryNaturalFoodEmergency(MinecraftServer server, NeedRequest r, SupplyFlowData.Demand scope, long now) {
        if (r.attempts() != 32 || r.maxAttempts() != 32 || r.reserved() != 0 || r.inTransit() != 0
                || !Set.of(NeedRequestStatus.OPEN, NeedRequestStatus.WAITING_EXTERNAL, NeedRequestStatus.UNREACHABLE).contains(r.status())
                || !r.key().purpose().equals("HOUSEHOLD:household:natural-diet:" + r.key().requester())
                || !r.key().item().equals(new ItemFingerprint("minecraft:carrot", 0))
                || !scope.principal().equals(SubjectRef.household(r.key().requester()))
                || !scope.destination().equals(r.key().destination())) return;
        var needs = NeedRequestData.get(server); var flows = SupplyFlowData.get(server); var rights = RightsStateData.get(server);
        if (inspectSpentEmergency(server, r) || flows.production(r.id()) != null || !needs.dependencies(r.id()).isEmpty()) return;
        for (var old : needs.requests()) if (old.key().requester().equals(r.key().requester())
                && old.key().purpose().equals(r.key().purpose())
                && ((!old.id().equals(r.id()) && !old.status().terminal()) || unsettledFood(server, old, now))) return;
        var life = NaturalVillageLifeData.existing(server); if (life == null) return;
        NaturalVillageBuilding village = null;
        for (var row : life.villages()) if (row.getBooleanOr("Ready", false)) {
            var candidate = NaturalVillageLifeData.decode(row);
            if (NaturalVillageLayout.autonomousFarm(candidate) && NaturalVillageLifeData.home(candidate).equals(r.key().requester())) {
                village = candidate; break;
            }
        }
        if (village == null) return;
        var home = HouseholdData.get(server).byId(r.key().requester()); var l = level(server, village.dimension());
        var pantry = NaturalVillageLayout.at(village, NaturalVillageLayout.PANTRY);
        if (home == null || !Objects.equals(home.settlementId(), village.settlementId()) || !home.dimension().equals(village.dimension())
                || !Objects.equals(home.sharedStorage(), pantry) || l == null
                || !r.key().destination().equals(ContainerIds.block("household_storage", village.dimension(), pantry))) return;
        var policy = flows.policies().stream().filter(p -> p.id().equals("natural-diet:" + home.id())).findFirst().orElse(null);
        if (policy == null || !policy.principal().equals(scope.principal()) || !policy.destination().equals(scope.destination())
                || !policy.item().equals(r.key().item()) || !policy.category().equals("household")) return;
        var entity = l.getEntity(NaturalVillageLifeData.body(village, 1));
        if (!(entity instanceof NpcBase courier) || !courier.getCharacterId().equals(NaturalVillageLifeData.person(village, 1))
                || !home.contains(courier.getCharacterId()) || courier.isNoAi() || courier.getProfession() != NpcProfession.COURIER
                || !SupplyTransportTask.eligible(courier) || !NaturalFarmRules.foodEmergency(courier)) return;
        var person = CharacterRegistryData.get(server).byId(courier.getCharacterId()); if (person == null || !person.alive()) return;
        var active = NpcBrainRuntime.data(server).active(courier.getUUID()).orElse(null);
        if (active != null && active.kind() == NpcPlanKind.COMMITMENT) return;
        if (flows.routes().stream().anyMatch(old -> courier.getUUID().equals(old.carrierEntity())
                && (!old.closed() || old.stage() == SupplyFlowData.Stage.REVIEW || old.pending() != null))) return;
        var destination = flows.point(scope.destination()); var target = container(server, destination);
        if (target == null || !validOwner(server, destination) || FieldworkRoutes.plan(courier, destination.position(), false) == null) return;
        var cargoId = ContainerIds.npcCargo(courier.getCharacterId()); var cargoContext = rights.context(cargoId);
        if (cargoContext != null && !cargoContext.defaultBeneficialOwner().equals(SubjectRef.character(courier.getCharacterId()))) return;
        var carrot = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT);
        int capacity = Math.min(FieldworkDepositTaskExecutor.capacity(target, carrot),
                FieldworkDepositTaskExecutor.capacity(new LimitedContainerView(courier.getTransportInventory(), courier.getTransportSlotLimit()), carrot));
        if (capacity < 1) return;
        // Choose the largest lawful single load; RAW wins a tie. Never spend the sole route on a smaller avoidable load.
        FieldworkStorage bestSource = null; int bestQuantity = 0;
        for (var at : List.of(NaturalVillageLayout.RAW, NaturalVillageLayout.WOOD)) {
            UUID sourceId = ContainerIds.block("private_storage", village.dimension(), NaturalVillageLayout.at(village, at));
            if (!flows.known(scope.principal()).contains(sourceId)) continue;
            var source = flows.point(sourceId);
            if (!source.dimension().equals(village.dimension()) || !validOwner(server, source)) continue;
            int quantity = Math.min(r.remaining(), Math.min(64, Math.min(capacity, available(server, source, scope.principal(), r.key().item(), now))));
            if (quantity <= bestQuantity || FieldworkRoutes.plan(courier, source.position(), false) == null) continue;
            bestSource = source; bestQuantity = quantity;
        }
        if (bestSource == null) return;
        UUID routeId = NeedRequestData.emergencyRouteId(r.id());
        var route = new SupplyFlowData.Route(routeId, r.id(), bestSource.containerId(), destination.containerId(), scope.principal(), bestQuantity,
                null, null, SupplyFlowData.Stage.HELD, now, 0, null, "原自然食物请求预算已耗尽；一次合法紧急预留，保留原年龄与次数");
        if (!SupplyTransportTask.accepts(courier, route)) return;
        var reservation = new AssetReservation(receipt(routeId, "lease"), bestSource.containerId(), r.key().item(), bestQuantity,
                bestSource.owner(), route.token(), "原自然家庭食物的一次紧急补给", now, now + 1200);
        if (reserveNaturalEmergencyRoute(rights, needs, flows, LogisticsData.get(server), reservation, route,
                count(container(server, bestSource), r.key().item()), now))
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("NATURAL_EMERGENCY_ROUTE need={} route={} quantity={} originalCreated={} attempts={}/{} originalDeadline={} candidateCourier={}",
                    r.id(), routeId, bestQuantity, r.createdAt(), r.attempts(), r.maxAttempts(), r.key().deadline(), courier.getCharacterId());
    }

    static boolean materialRecoveryMatches(NeedRequest r, SupplyFlowData.Demand scope, ProductionOrder order,
                                           ProductionMaterialRequest legacy, ProductionBatch batch, long now) {
        if (!r.existingStockRecoveryEligible(now) || scope == null || order == null || legacy == null
                || order.terminal() || legacy.terminal() || batch != null) return false;
        var effective = order.status() == ProductionOrderStatus.BLOCKED ? order.blockedFromStatus() : order.status();
        return (effective == ProductionOrderStatus.OPEN || effective == ProductionOrderStatus.CLAIMED)
                && r.key().purpose().equals(order.outputOwner().type()+":production:"+order.id())
                && scope.principal().equals(order.outputOwner()) && r.key().requester().equals(order.outputOwner().id())
                && scope.destination().equals(r.key().destination()) && scope.destination().equals(legacy.containerId())
                && legacy.orderId().equals(order.id()) && legacy.material().fingerprint().equals(r.key().item())
                && legacy.material().quantity() == r.requested() && r.originalRequested() == r.requested()
                && order.inputs().contains(legacy.material()) && r.key().windowStart() == order.createdAt()
                && r.key().deadline() == order.createdAt()+72000;
    }

    static boolean completedSupplierRecoveryMatches(NeedRequest parent, NeedRequest child,
                                                    ProductionOrder order, ProductionBatch batch) {
        return child != null && order != null && child.status() == NeedRequestStatus.DELIVERED
                && child.reserved() == 0 && child.inTransit() == 0
                && child.key().purpose().equals("CHARACTER:local-supplier:"+parent.id()+":"+parent.key().item().itemId())
                && child.key().item().equals(parent.key().item())
                && child.key().requester().equals(order.outputOwner().id())
                && order.outputOwner().type() == SubjectRef.SubjectType.CHARACTER
                && order.issuer().equals(order.outputOwner()) && child.key().destination().equals(order.targetContainerId())
                && order.output().fingerprint().equals(child.key().item())
                && child.delivered() == child.requested() && child.delivered() == order.output().quantity()
                && order.status() == ProductionOrderStatus.COMPLETED && completedUnitMatches(order,batch);
    }

    private static boolean recoveryObligationsClear(RightsStateData rights, NeedRequestData needs,
                                                    SupplyFlowData flows, LogisticsData logistics,
                                                    NeedRequest need, long now) {
        if (need.reserved()!=0 || need.inTransit()!=0) return false;
        var unmatched = new HashMap<>(needs.reserveReceipts(need.id()));
        int delivered=0, released=0;
        for (var route : flows.routes()) if (route.need().equals(need.id())) {
            if (!route.closed() || route.pending()!=null || route.stage()==SupplyFlowData.Stage.REVIEW) return false;
            Integer quantity = unmatched.remove(receipt(route.id(),"reserve"));
            if (quantity==null || quantity!=route.quantity() || !linkedRecoveryOrder(logistics,need,route)
                    || !rights.reservationsForToken(route.token(),now).isEmpty())
                throw new IllegalStateException("原路线、预留回执或物流订单未核清");
            var order=logistics.order(route.id());
            if (route.stage()==SupplyFlowData.Stage.DONE) {
                if(order.pickedUpCount()!=quantity || order.deliveredCount()!=quantity
                        || order.status()!=LogisticsOrderStatus.DELIVERED)
                    throw new IllegalStateException("完成路线缺少物流实际取货或交付数量");
                delivered=Math.addExact(delivered,quantity);
            } else {
                if(order.pickedUpCount()!=0 || order.deliveredCount()!=0 || order.status()!=LogisticsOrderStatus.CANCELLED
                        || !needs.hasReceipt(receipt(route.id(),"release"),need.id(),NeedRequestData.Action.RELEASE_RESERVED,quantity))
                    throw new IllegalStateException("取消路线缺少原预留释放证据或仍有货物");
                released=Math.addExact(released,quantity);
            }
        }
        if (!unmatched.isEmpty()) throw new IllegalStateException("存在无原路线的预留回执，禁止以新路线重扣");
        for (var order : logistics.orders()) if (need.id().equals(order.needRequestId()) && flows.routeOrNull(order.id())==null)
            throw new IllegalStateException("存在无原路线的物流义务，禁止以新路线重扣");
        // Transfer operation IDs are random pending IDs, not derived route IDs.
        if(delivered!=need.delivered() || needs.acknowledgedQuantity(need.id(),NeedRequestData.Action.DELIVER)!=delivered
                ||needs.acknowledgedQuantity(need.id(),NeedRequestData.Action.DISPATCH)!=delivered
                ||needs.acknowledgedQuantity(need.id(),NeedRequestData.Action.RELEASE_RESERVED)!=released)
            throw new IllegalStateException("需求数量、已关闭路线和实际转移回执总量不一致");
        return true;
    }

    private static boolean linkedRecoveryOrder(LogisticsData logistics, NeedRequest need, SupplyFlowData.Route route) {
        var order=logistics.order(route.id());
        return order!=null && route.need().equals(need.id()) && route.destination().equals(need.key().destination())
                && route.principal().id().equals(need.key().requester()) && Objects.equals(order.needRequestId(),need.id())
                && order.sourceWarehouseId().equals(route.source()) && order.destinationWarehouseId().equals(route.destination())
                && Objects.equals(order.createdByCharacter(),route.principal().id()) && order.requestedCount()==route.quantity()
                && order.itemId().equals(need.key().item().itemId()) && order.componentsHash()==need.key().item().componentsHash();
    }

    /** Replays inspect the existing receipt before rights.reserve; an old released lease is never revived. */
    static boolean reserveExistingStockRoute(RightsStateData rights, NeedRequestData needs, SupplyFlowData flows,
                                             LogisticsData logistics, AssetReservation reservation,
                                             SupplyFlowData.Route route, int physical, long now) {
        var need=needs.request(route.need()); var scope=flows.demand(need.id());
        var source=flows.pointOrNull(route.source());
        if (scope==null || source==null || !scope.principal().equals(route.principal())
                || !scope.destination().equals(route.destination()) || !need.key().destination().equals(route.destination())
                || !need.key().requester().equals(route.principal().id())
                || !reservation.id().equals(receipt(route.id(),"lease")) || !reservation.token().equals(route.token())
                || !reservation.containerId().equals(route.source()) || !reservation.reservedFor().equals(source.owner())
                || !reservation.fingerprint().equals(need.key().item()) || reservation.quantity()!=route.quantity()
                || route.stage()!=SupplyFlowData.Stage.HELD || route.carrierEntity()!=null || route.pending()!=null)
            throw new IllegalStateException("existing stock recovery publication identity conflict");
        var previous=flows.routeOrNull(route.id());
        if (needs.hasReceipt(receipt(route.id(),"reserve"),need.id(),NeedRequestData.Action.RESERVE,route.quantity())) {
            if (previous==null || !previous.need().equals(route.need()) || !previous.source().equals(route.source())
                    || !previous.destination().equals(route.destination()) || !previous.principal().equals(route.principal())
                    || previous.quantity()!=route.quantity() || !linkedRecoveryOrder(logistics,need,route))
                throw new IllegalStateException("existing stock reserve receipt lost its linked identity");
            return false;
        }
        if (previous!=null || logistics.order(route.id())!=null
                || rights.reservations().stream().anyMatch(r->r.token().equals(route.token())))
            throw new IllegalStateException("existing stock recovery has linked state without reserve receipt");
        if (!need.existingStockRecoveryEligible(now) || !recoveryObligationsClear(rights,needs,flows,logistics,need,now)) return false;
        if (!rights.reserve(reservation,physical,now)) return false;
        try {
            needs.acknowledgeExistingStockReserveAndPublish(receipt(route.id(),"reserve"),need.id(),route.quantity(),now,
                    ()->flows.createRouteWithOrder(route,()->logistics.createManagedOrder(route.id(),route.source(),
                            route.destination(),reservation.fingerprint(),route.quantity(),route.principal().id(),need.id(),now)));
            return true;
        } catch (RuntimeException error) {
            rights.releaseReservations(reservation.token());
            throw error;
        }
    }

    private static void tryExistingStockRecovery(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,long now) {
        if (!r.existingStockRecoveryEligible(now) || !scope.destination().equals(r.key().destination())
                || !scope.principal().id().equals(r.key().requester())) return;
        var polls=STOCK_RECOVERY_POLL.computeIfAbsent(server,s->new HashMap<>());
        Long last=polls.get(r.id()); if(last!=null && now>=last && now-last<600) return;
        polls.put(r.id(),now);
        var needs=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);var rights=RightsStateData.get(server);
        try {
            if (flows.production(r.id())!=null || !recoveryObligationsClear(rights,needs,flows,LogisticsData.get(server),r,now)) return;
            var destination=flows.pointOrNull(scope.destination());
            if(destination==null || !validOwner(server,destination) || container(server,destination)==null) return;
            int limit=r.remaining();
            Set<UUID> permittedSources=null;
            int marker=r.key().purpose().indexOf(":production:");
            if(marker>=0) {
                var order=ProductionOrderData.get(server).byId(UUID.fromString(r.key().purpose().substring(marker+12))).orElse(null);
                if(order==null) throw new IllegalStateException("原生产订单缺失");
                if(order.terminal()) return;
                var material=new ProductionMaterial(r.key().item(),r.requested());
                var legacy=ProductionMaterialRequestData.get(server).byKey(ProductionMaterialRequest.keyFor(order.id(),material)).orElse(null);
                var batch=ProductionBatchData.get(server).batch(order.batchId());
                if(legacy==null)throw new IllegalStateException("原缺料记录缺失");
                if(!materialRecoveryMatches(r,scope,order,legacy,batch,now)) return;
                FieldworkStorage expected=FurnaceWorkAdapter.recognized(order)?FurnaceWorkAdapter.inputPoint(server,order)
                        :new FieldworkStorage(order.targetContainerDimension(),order.targetContainerPosition(),order.targetContainerId(),
                            order.outputOwner(),destination.policy(),TransferIntent.DELIVERY,order.token());
                if(!expected.containerId().equals(destination.containerId()) || !expected.position().equals(destination.position())
                        || !expected.dimension().equals(destination.dimension()) || !expected.owner().equals(destination.owner()))
                    throw new IllegalStateException("原缺料需求与实际生产容器不一致");
                if(!rights.reservationsForToken(order.token(),now).isEmpty()
                        ||!rights.reservationsForToken("production:"+order.batchId(),now).isEmpty()) return;
                limit=Math.min(limit,Math.max(0,material.quantity()-count(container(server,destination),r.key().item())));
            } else {
                if(!scope.principal().equals(SubjectRef.household(r.key().requester())) || householdFood(r)) return;
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(r.key().item().itemId()));
                if(com.jgeted.sagadyssey.social.life.NpcFoodItems.rationValue(new net.minecraft.world.item.ItemStack(item))>0) return;
                var home=HouseholdData.get(server).byId(r.key().requester());
                if(home==null || home.sharedStorage()==null || !home.dimension().equals(destination.dimension())
                        || !home.sharedStorage().equals(destination.position()) || !destination.owner().equals(scope.principal())) return;
                int deficit=0;
                for(var policy:flows.policies()) if(r.key().purpose().equals(policy.principal().type()+":"+policy.category()+":"+policy.id())
                        && policy.principal().equals(scope.principal()) && policy.destination().equals(scope.destination()) && policy.item().equals(r.key().item()))
                    deficit=Math.max(0,policy.target()-count(container(server,destination),r.key().item()));
                var provision=HouseholdData.get(server).provisionPlan(home.id()).inspect(container(server,destination));
                if(r.key().purpose().equals("HOUSEHOLD:household:tools") && r.key().item().equals(new ItemFingerprint("minecraft:iron_pickaxe",0))) deficit=provision.toolDeficit();
                if(r.key().purpose().equals("HOUSEHOLD:household:fuel") && r.key().item().equals(new ItemFingerprint("minecraft:coal",0))) deficit=provision.fuelDeficit();
                if(deficit<=0)return;limit=Math.min(limit,deficit);permittedSources=new HashSet<>();
                for(UUID childId:needs.dependencies(r.id())) {
                    var child=needs.request(childId);
                    if(!child.key().purpose().equals("CHARACTER:local-supplier:"+r.id()+":"+r.key().item().itemId())
                            ||child.status()!=NeedRequestStatus.DELIVERED)continue;
                    UUID orderId=flows.production(child.id());
                    var order=orderId==null?null:ProductionOrderData.get(server).byId(orderId).orElse(null);
                    var batch=order==null?null:ProductionBatchData.get(server).batch(order.batchId());
                    if(!completedSupplierRecoveryMatches(r,child,order,batch)) throw new IllegalStateException("原本地供应完成证据不一致");
                    var target=flows.pointOrNull(child.key().destination());var childScope=flows.demand(child.id());
                    if(target==null||childScope==null)throw new IllegalStateException("原供应库或作用域缺失");
                    if(!woodProductionIntent(child,childScope,target,order,0) && !unitProductionIntent(child,childScope,target,order,0))
                        throw new IllegalStateException("原本地供应制造意图不一致，保留核对");
                    if(!needs.hasReceipt(receipt(order.id(),"produced-reserve"),child.id(),NeedRequestData.Action.RESERVE,order.output().quantity())
                            ||!needs.hasReceipt(receipt(order.id(),"produced-dispatch"),child.id(),NeedRequestData.Action.DISPATCH,order.output().quantity())
                            ||!needs.hasReceipt(receipt(order.id(),"produced-delivery"),child.id(),NeedRequestData.Action.DELIVER,order.output().quantity()))
                        throw new IllegalStateException("原本地供应缺少真实生产结算回执");
                    if(flows.routes().stream().anyMatch(route->route.need().equals(childId)&&(!route.closed()||route.pending()!=null||route.stage()==SupplyFlowData.Stage.REVIEW)))return;
                    if(!rights.reservationsForToken(order.token(),now).isEmpty()
                            ||!rights.reservationsForToken("production:"+order.batchId(),now).isEmpty())return;
                    permittedSources.add(child.key().destination());
                }
                if(permittedSources.isEmpty())return;
            }
            if(limit<=0)return;
            for(UUID sourceId:flows.known(scope.principal()).stream().sorted().toList()) {
                if(sourceId.equals(scope.destination()) || permittedSources!=null&&!permittedSources.contains(sourceId))continue;
                var source=flows.pointOrNull(sourceId);if(source==null||!source.dimension().equals(destination.dimension()))continue;
                int quantity=Math.min(limit,Math.min(64,available(server,source,scope.principal(),r.key().item(),now)));
                if(quantity<=0)continue;
                UUID routeId=UUID.randomUUID();
                var route=new SupplyFlowData.Route(routeId,r.id(),sourceId,destination.containerId(),scope.principal(),quantity,
                        null,null,SupplyFlowData.Stage.HELD,now,0,null,"原请求32次后取得已确认合法现货；保留原身份、年龄、期限和次数");
                var lease=new AssetReservation(receipt(routeId,"lease"),sourceId,r.key().item(),quantity,source.owner(),route.token(),"原请求现货恢复",now,now+1200);
                if(reserveExistingStockRoute(rights,needs,flows,LogisticsData.get(server),lease,route,count(container(server,source),r.key().item()),now)) {
                    com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("SUPPLY_EXISTING_STOCK_RECOVERY need={} route={} item={} quantity={} originalCreated={} attempts={}/{} originalDeadline={}",
                            r.id(),route.id(),r.key().item(),quantity,r.createdAt(),r.attempts(),r.maxAttempts(),r.key().deadline());
                    return;
                }
            }
        } catch(IllegalStateException|IllegalArgumentException|NoSuchElementException error) {
            productionReview(needs,needs.request(r.id()),now,"现货恢复证据不足："+error.getMessage());
        }
    }

    public static void match(MinecraftServer server,long now){
        var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);var rights=RightsStateData.get(server);
        Set<UUID> routedNeeds = new HashSet<>();
        for (var route : flows.routes()) {
            if (!route.closed() || route.stage() == SupplyFlowData.Stage.REVIEW)
                routedNeeds.add(route.need());
        }
        for(var snapshot:needs.requests()){
            var r=needs.request(snapshot.id());var scope=flows.demand(r.id());
            if (inspectSpentEmergency(server, r)) continue;
            if(scope==null||r.status().terminal()||r.status()==NeedRequestStatus.DELIVERED||r.status()==NeedRequestStatus.BLOCKED_CYCLE
                    ||r.key().deadline()<=now||r.remaining()==0||now-r.updatedAt()<600&&r.status()==NeedRequestStatus.WAITING_EXTERNAL)continue;
            if(!currentHouseholdDestination(server,r)||SettlementExchangeData.managedNeed(r))continue;
            if (r.attempts() >= r.maxAttempts()) {
                tryNaturalFoodEmergency(server, r, scope, now);
                tryExistingStockRecovery(server, needs.request(r.id()), scope, now);
                continue;
            }
            if (householdFood(r)) {
                var history = householdFoodHistory(needs, r.key().requester());
                UUID currentId = r.id();
                if (history.stream().filter(n -> !n.status().terminal()).count() > 1
                        || history.stream().anyMatch(n -> !n.id().equals(currentId) && unsettledFood(server, n, now))
                        || uncertainFoodRoute(server, r, now)) continue;
                // An independent match call cannot select unsupported food or switch an old identity.
                if (r.reserved() == 0 && r.inTransit() == 0 && flows.production(r.id()) == null) {
                    var item = selectHouseholdFood(server, scope.principal(), flows.point(scope.destination()), r.key().item(), now);
                    if (!r.key().item().equals(item)) { foodWaiting(needs, r, now); continue; }
                }
            }
            if(r.key().purpose().contains(":production:")&&productionCancelled(server,r.key().purpose())){
                if(r.reserved()==0&&r.inTransit()==0)needs.transition(r.id(),NeedRequestStatus.CANCELLED,now,"原始生产订单已终止");
                continue;
            }
            if (routedNeeds.contains(r.id())) continue;
            boolean cancelledStockOnly=false;
            UUID linkedProduction=flows.production(r.id());
            if(linkedProduction!=null){
                var series=productionSeries(server,r,scope,now);if(series==null)continue;
                var old=series.isEmpty()?null:series.getLast();
                int unit=Math.max(0,series.size()-1);
                if(old!=null&&old.status()==ProductionOrderStatus.COMPLETED){
                    // Wood recipes keep their native batch size and immutable input specification.
                    // Only a confirmed earlier batch permits another batch for the same unmet need.
                    if(toolRecipe(r.key().item())==null){
                        if(woodBatchRecipe(old.recipeId())&&WorkshopTask.recognized(server,old)){
                            var destination=flows.point(scope.destination());
                            int delivered=series.stream().mapToInt(o->o.output().quantity()).sum();
                            if(!woodProductionIntent(r,scope,destination,old,unit)
                                    ||!completedUnitMatches(old,ProductionBatchData.get(server).batch(old.batchId()))){
                                productionReview(needs,r,now,"木工原批次完成证据或身份不足，不接续新批次");continue;
                            }
                            if(woodContinuationReady(r,delivered,old.output().quantity(),series.size(),
                                    woodBufferStillNeeds(server,r,scope,destination,old.output().quantity())))
                                createProduction(server,r,scope,destination,now,series.size());
                        }
                        continue;
                    }
                    var destination=flows.point(scope.destination());
                    if(!unitProductionIntent(r,scope,destination,old,unit)
                            ||!completedUnitMatches(old,ProductionBatchData.get(server).batch(old.batchId()))){
                        productionReview(needs,r,now,"原制造完成证据或单件意图不足，不接续新批次");continue;
                    }
                    // Reconcile real per-unit receipts before promising another unit.
                    if(r.delivered()<series.size())continue;
                    if(series.size()<Math.min(64,r.requested())&&r.remaining()>0)
                        createProduction(server,r,scope,destination,now,series.size());
                    continue;
                }
                if(old!=null&&old.status()!=ProductionOrderStatus.CANCELLED)continue;
                String issue=cancelledStockIssue(server,r,scope,old,now,unit);
                if(issue!=null){productionReview(needs,r,now,issue);continue;}
                // Keep the immutable original association as history. It must not bar
                // lawful existing stock, and it must never cause replacement manufacture.
                cancelledStockOnly=true;
            }
            var destination=flows.point(scope.destination());
            if(container(server,destination)==null||!validOwner(server,destination)){
                if(r.status()==NeedRequestStatus.OPEN||r.status()==NeedRequestStatus.MATCHED)needs.transition(r.id(),NeedRequestStatus.UNREACHABLE,now,"目标容器不可用或所有权已变");continue;
            }
            if(r.status()==NeedRequestStatus.UNREACHABLE||r.status()==NeedRequestStatus.WAITING_EXTERNAL)r=needs.transition(r.id(),NeedRequestStatus.OPEN,now,"重核已知供给与目的地");
            int remaining=r.remaining();boolean found=false;
            for(UUID sourceId:flows.known(scope.principal()).stream().sorted().toList()){
                var point=flows.point(sourceId);if(sourceId.equals(destination.containerId())||!point.dimension().equals(destination.dimension()))continue;
                int quantity=Math.min(remaining,Math.min(64,available(server,point,scope.principal(),r.key().item(),now)));if(quantity<=0)continue;
                UUID routeId=UUID.randomUUID();String token="need-route:"+routeId;
                var reservation=new AssetReservation(receipt(routeId,"lease"),sourceId,r.key().item(),quantity,point.owner(),token,"补货",now,now+1200);
                int physical=count(container(server,point),r.key().item());
                var route = new SupplyFlowData.Route(routeId, r.id(), sourceId,
                        destination.containerId(), scope.principal(), quantity, null, null,
                        SupplyFlowData.Stage.HELD, now, 0, null, "已预留真实库存");
                if (reserveRoute(rights, needs, flows, LogisticsData.get(server),
                        reservation, route, physical, now)) {
                    found = true;
                    break;
                }
            }
            if(!found){
                if(cancelledStockOnly){
                    needs.transition(r.id(),NeedRequestStatus.WAITING_EXTERNAL,now,
                            "原制造已取消且无未核实效果；等待合法现货，保留原关联与剩余缺口，不重开制造");
                }else if(!createProduction(server,r,scope,destination,now)){
                    needs.failedAttempt(r.id(),now,"已知且有权取用的供给不足");
                    needs.transition(r.id(),NeedRequestStatus.WAITING_EXTERNAL,now,"等待外部供给，不重复签发物流单");
                }
            }
        }
    }
    /** A missing batch is not generally proof of no effect. Only unclaimed orders
     * or this existing, exact preflight failure can establish it without a batch. */
    static boolean cancelledBatchHasNoEffect(ProductionOrder order,ProductionBatch batch){
        if(order==null||order.status()!=ProductionOrderStatus.CANCELLED)return false;
        if(batch==null){
            if(order.assignedWorkerId()==null)return true;
            return order.recipeId().equals("sagadyssey:iron_pickaxe")
                    &&order.output().fingerprint().equals(new ItemFingerprint("minecraft:iron_pickaxe",0))
                    &&order.output().quantity()>1
                    &&order.detail().equals("执行器异常: 单批成品超过堆叠上限");
        }
        return (batch.status()==ProductionBatchStatus.CANCELLED||batch.status()==ProductionBatchStatus.FAILED)
                &&batch.id().equals(order.batchId())&&batch.recipeId().equals(order.recipeId())
                &&batch.workerId().equals(order.assignedWorkerId())&&batch.outputOwner().equals(order.outputOwner())
                &&batch.containerId().equals(order.targetContainerId())&&batch.inputs().equals(order.inputs())
                &&batch.output().equals(order.output())&&batch.requiredWorkTicks()==order.requiredWorkTicks();
    }
    private static String cancelledStockIssue(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,
                                               ProductionOrder order,long now,int unit){
        if(order==null)return "原关联制造单缺失，不能推定没有效果";
        var flows=SupplyFlowData.get(server);var target=flows.point(scope.destination());
        if(!order.id().equals(unitOrderId(r.id(),unit))
                ||!order.batchId().equals(unitBatchId(r.id(),unit))
                ||!order.issuer().equals(scope.principal())||!order.outputOwner().equals(scope.principal())
                ||!order.output().fingerprint().equals(r.key().item())
                ||!order.targetContainerId().equals(scope.destination())
                ||!order.targetContainerDimension().equals(target.dimension())
                ||!order.targetContainerPosition().equals(target.position()))return "原需求、制造意图与物权/目的地身份不一致";
        var batch=ProductionBatchData.get(server).batch(order.batchId());
        if(!cancelledBatchHasNoEffect(order,batch))return "取消制造的批次效果尚未核清";
        var rights=RightsStateData.get(server);
        if(!rights.reservationsForToken(order.token(),now).isEmpty()
                ||!rights.reservationsForToken("production:"+order.batchId(),now).isEmpty())return "原制造仍有投入预留";
        var needs=NeedRequestData.get(server);var obligations=new HashSet<>(needs.dependencies(r.id()));obligations.add(r.id());
        String purpose=order.outputOwner().type()+":production:"+order.id();
        for(var input:needs.requests())if(input.key().purpose().equals(purpose))obligations.add(input.id());
        for(var id:obligations){var n=needs.request(id);if(n.reserved()>0||n.inTransit()>0)return "原需求或投入仍有预留/在途责任";}
        for(var route:flows.routes())if(obligations.contains(route.need())&&(!route.closed()||route.pending()!=null))
            return "原制造投入或交付路线仍未核清";
        return null;
    }
    private static void productionReview(NeedRequestData needs,NeedRequest r,long now,String issue){
        String reason="PRODUCTION_REVIEW: "+issue;
        if(r.status()==NeedRequestStatus.UNREACHABLE&&r.detail().equals(reason))return;
        if(r.status()==NeedRequestStatus.WAITING_EXTERNAL)r=needs.transition(r.id(),NeedRequestStatus.OPEN,now,"重核取消制造的原始事实");
        if(r.status().canTransitionTo(NeedRequestStatus.UNREACHABLE))needs.transition(r.id(),NeedRequestStatus.UNREACHABLE,now,reason);
    }
    /** The existing immutable link remains the first order; later units have derived IDs,
     * independent existing order/batch/labor records, and per-order delivery receipts. */
    static UUID unitOrderId(UUID need,int unit){return unitId(need,unit,"production-order");}
    static UUID unitBatchId(UUID need,int unit){return unitId(need,unit,"production-batch");}
    private static UUID unitId(UUID need,int unit,String kind){
        if(unit<0||unit>=64)throw new IllegalArgumentException("unit outside bounded production request");
        return receipt(need,unit==0?kind:kind+":unit:"+unit);
    }
    private record ToolRecipe(String id,List<ProductionMaterial> inputs){}
    static boolean localMaterialRecipe(MinecraftServer server,ProductionOrder order){
        if(WorkshopTask.recognized(server,order))return true;
        var spec=toolRecipe(order.output().fingerprint());
        return spec!=null&&order.recipeId().equals(spec.id())&&order.inputs().equals(spec.inputs())
                &&order.output().quantity()==1&&order.requiredWorkTicks()==80;
    }
    private static ToolRecipe toolRecipe(ItemFingerprint output){
        if(output.componentsHash()!=0)return null;
        var iron=new ItemFingerprint("minecraft:iron_ingot",0);var sticks=new ItemFingerprint("minecraft:stick",0);
        return switch(output.itemId()){
            case "minecraft:iron_pickaxe" -> new ToolRecipe("sagadyssey:iron_pickaxe",List.of(new ProductionMaterial(iron,3),new ProductionMaterial(new ItemFingerprint("minecraft:coal",0),1)));
            case "minecraft:iron_axe" -> new ToolRecipe("minecraft:iron_axe",List.of(new ProductionMaterial(iron,3),new ProductionMaterial(sticks,2)));
            case "minecraft:iron_hoe" -> new ToolRecipe("minecraft:iron_hoe",List.of(new ProductionMaterial(iron,2),new ProductionMaterial(sticks,2)));
            case "minecraft:shears" -> new ToolRecipe("minecraft:shears",List.of(new ProductionMaterial(iron,2)));
            default -> null;
        };
    }
    static boolean unitProductionIntent(NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage target,ProductionOrder o,int unit){
        var spec=toolRecipe(r.key().item());
        return scope!=null&&o!=null&&spec!=null
                &&o.id().equals(unitOrderId(r.id(),unit))&&o.batchId().equals(unitBatchId(r.id(),unit))
                &&o.recipeId().equals(spec.id())&&o.issuer().equals(scope.principal())
                &&o.outputOwner().equals(scope.principal())&&o.output().equals(new ProductionMaterial(r.key().item(),1))
                &&o.inputs().equals(spec.inputs())
                &&o.requiredWorkTicks()==80&&o.targetContainerId().equals(scope.destination())
                &&o.targetContainerDimension().equals(target.dimension())&&o.targetContainerPosition().equals(target.position());
    }
    static boolean completedUnitMatches(ProductionOrder order,ProductionBatch batch){
        return batch!=null&&batch.status()==ProductionBatchStatus.COMPLETED&&order.assignedWorkerId()!=null
                &&batch.id().equals(order.batchId())&&batch.workerId().equals(order.assignedWorkerId())
                &&batch.recipeId().equals(order.recipeId())&&batch.outputOwner().equals(order.outputOwner())
                &&batch.containerId().equals(order.targetContainerId())&&batch.inputs().equals(order.inputs())
                &&batch.output().equals(order.output())&&batch.requiredWorkTicks()==order.requiredWorkTicks();
    }
    static String woodBusiness(UUID need,int index){
        if(index<0||index>=64)throw new IllegalArgumentException("wood batch outside bounded production request");
        return "need:"+need+(index==0?"":":batch:"+index);
    }
    static UUID woodOrderId(UUID need,UUID person,String recipe,int index){
        String prefix=switch(recipe){case WorkshopTask.PLANKS->"industry-planks";case WorkshopTask.STICKS->"industry-sticks";case WorkshopTask.BED->"industry-bed";default->throw new IllegalArgumentException("not a wood recipe");};
        return IndustryTransfers.id(prefix+":"+person+":"+woodBusiness(need,index));
    }
    static UUID woodBatchId(UUID order){return IndustryTransfers.id(order+":batch");}
    static boolean woodBatchRecipe(String recipe){
        return recipe.equals(WorkshopTask.PLANKS)||recipe.equals(WorkshopTask.STICKS)||recipe.equals(WorkshopTask.BED);
    }
    static boolean woodContinuationReady(NeedRequest r,int confirmedOutput,int quantity,int batches,boolean bufferNeedsBatch){
        return !r.status().terminal()&&r.status()!=NeedRequestStatus.DELIVERED&&r.status()!=NeedRequestStatus.BLOCKED_CYCLE
                &&quantity>0&&batches>0&&batches<64&&r.delivered()>=confirmedOutput
                &&r.remaining()>=quantity&&bufferNeedsBatch;
    }
    static boolean woodProductionIntent(NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage target,ProductionOrder o,int index){
        if(scope==null||target==null||o==null||scope.principal().type()!=SubjectRef.SubjectType.CHARACTER)return false;
        int quantity=switch(o.recipeId()){case WorkshopTask.PLANKS->12;case WorkshopTask.STICKS->4;case WorkshopTask.BED->1;default->0;};
        return quantity>0&&r.requested()>=(index+1)*quantity
                &&o.id().equals(woodOrderId(r.id(),scope.principal().id(),o.recipeId(),index))
                &&o.batchId().equals(woodBatchId(o.id()))
                &&o.issuer().equals(scope.principal())&&o.outputOwner().equals(scope.principal())
                &&o.output().equals(new ProductionMaterial(r.key().item(),quantity))
                &&com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes.shape(o.recipeId(),o.inputs(),o.output(),o.requiredWorkTicks())
                &&o.targetContainerId().equals(scope.destination())&&r.key().destination().equals(scope.destination())
                &&r.key().requester().equals(scope.principal().id())
                &&o.targetContainerDimension().equals(target.dimension())&&o.targetContainerPosition().equals(target.position());
    }
    private static boolean woodBufferStillNeeds(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage target,int batch){
        var inventory=container(server,target);if(inventory==null||!validOwner(server,target))return false;
        // An independent lawful delivery can fill a buffer while the earlier batch is working.
        // Do not credit that arrival twice, or launch new production merely because the old need remains.
        for(var policy:SupplyFlowData.get(server).policies())if(r.key().purpose().equals(policy.principal().type()+":"+policy.category()+":"+policy.id())
                &&policy.principal().equals(scope.principal())&&policy.destination().equals(scope.destination())&&policy.item().equals(r.key().item()))
            return Math.max(0,policy.target()-count(inventory,policy.item()))>=batch;
        return true;
    }
    static boolean uncommittedUnitMaterialIsRedundant(NeedRequest r,ProductionOrder order,ProductionBatch batch){
        return redundantUnitMaterial(r,order,batch,order!=null&&WorkshopTask.recognized(order));
    }
    private static boolean uncommittedUnitMaterialIsRedundant(MinecraftServer server,NeedRequest r,ProductionOrder order,ProductionBatch batch){
        return redundantUnitMaterial(r,order,batch,order!=null&&WorkshopTask.recognized(server,order));
    }
    private static boolean redundantUnitMaterial(NeedRequest r,ProductionOrder order,ProductionBatch batch,boolean woodRecipe){
        return order!=null&&order.status()==ProductionOrderStatus.COMPLETED
                &&((toolRecipe(order.output().fingerprint())!=null&&order.recipeId().equals(toolRecipe(order.output().fingerprint()).id())&&order.output().quantity()==1)
                    ||woodRecipe)
                &&completedUnitMatches(order,batch)&&!r.status().terminal()&&r.status()!=NeedRequestStatus.DELIVERED
                &&r.reserved()==0&&r.inTransit()==0
                &&r.key().requester().equals(order.outputOwner().id())
                &&r.key().purpose().equals(order.outputOwner().type()+":production:"+order.id())
                &&r.key().destination().equals(order.targetContainerId())
                &&order.inputs().stream().anyMatch(m->m.fingerprint().equals(r.key().item())&&m.quantity()==r.requested());
    }
    private static List<ProductionOrder> productionSeries(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,long now){
        UUID root=SupplyFlowData.get(server).production(r.id());if(root==null)return List.of();
        var orders=ProductionOrderData.get(server);var first=orders.byId(root).orElse(null);if(first==null)return List.of();
        if(scope==null)return List.of(first);
        var target=SupplyFlowData.get(server).point(scope.destination());
        boolean wood=woodBatchRecipe(first.recipeId())&&WorkshopTask.recognized(server,first);
        if(wood&&!woodProductionIntent(r,scope,target,first,0)){
            productionReview(NeedRequestData.get(server),r,now,"木工首单与原需求身份不一致，保留核对");return null;
        }
        if(!wood&&!unitProductionIntent(r,scope,target,first,0))return List.of(first);
        var result=new ArrayList<ProductionOrder>();result.add(first);boolean gap=false;
        int batchSize=wood?first.output().quantity():1;
        for(int unit=1;unit<Math.min(64,r.requested()/batchSize);unit++){
            UUID nextId=wood?woodOrderId(r.id(),scope.principal().id(),first.recipeId(),unit):unitOrderId(r.id(),unit);
            var next=orders.byId(nextId).orElse(null);
            if(next==null){
                if(ProductionBatchData.get(server).batch(wood?woodBatchId(nextId):unitBatchId(r.id(),unit))!=null){
                    productionReview(NeedRequestData.get(server),r,now,"单件订单缺失但批次存在，保留核对");return null;
                }
                gap=true;continue;
            }
            var previous=result.getLast();
            if(gap||previous.status()!=ProductionOrderStatus.COMPLETED
                    ||!completedUnitMatches(previous,ProductionBatchData.get(server).batch(previous.batchId()))
                    ||(wood? !woodProductionIntent(r,scope,target,next,unit)||!WorkshopTask.recognized(server,next)
                            ||!Objects.equals(first.workstationDimension(),next.workstationDimension())||!Objects.equals(first.workstationPosition(),next.workstationPosition())
                            :!unitProductionIntent(r,scope,target,next,unit))){
                productionReview(NeedRequestData.get(server),r,now,"单件序列存在身份冲突或前单未核实，不新承诺");return null;
            }
            result.add(next);
        }
        return result;
    }
    private static boolean linkedProductionOrder(MinecraftServer server,NeedRequest parent,UUID order,long now){
        if(order.equals(SupplyFlowData.get(server).production(parent.id())))return true;
        var series=productionSeries(server,parent,SupplyFlowData.get(server).demand(parent.id()),now);
        return series!=null&&series.stream().anyMatch(o->o.id().equals(order));
    }
    /** First recipe slice: explicit iron-pickaxe manufacturing, not arbitrary output guessing. */
    private static boolean createProduction(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage destination,long now){
        return createProduction(server,r,scope,destination,now,0);
    }
    private static boolean createProduction(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage destination,long now,int unit){
        var toolSpec=toolRecipe(r.key().item());
        if(toolSpec==null)return createDeclaredIndustryProduction(server,r,scope,destination,now,unit);
        if(r.remaining()<=0||r.remaining()>64)return false;
        if(!toolSpec.id().equals("sagadyssey:iron_pickaxe")&&industryToolOwner(server,scope.principal(),r.key().item())==null)return false;
        if(container(server,destination)==null||!validOwner(server,destination))return false;
        var orders=ProductionOrderData.get(server);UUID id=unitOrderId(r.id(),unit);
        if(orders.byId(id).isPresent()||ProductionBatchData.get(server).batch(unitBatchId(r.id(),unit))!=null){
            productionReview(NeedRequestData.get(server),r,now,"下一单件标识已有记录，不覆盖或重扣");return true;
        }
        var level=level(server,destination.dimension());if(level==null)return false;BlockPos station=null;
        for(BlockPos p:BlockPos.betweenClosed(destination.position().offset(-8,-2,-8),destination.position().offset(8,2,8))){
            if(!level.hasChunkAt(p))continue;var b=level.getBlockState(p).getBlock();
            if(b instanceof net.minecraft.world.level.block.AnvilBlock||b==net.minecraft.world.level.block.Blocks.SMITHING_TABLE){station=p.immutable();break;}
        }
        if(station==null)return false;int quantity=1;
        var inputs=toolSpec.inputs();
        var order=ProductionOrder.open(id,scope.principal(),scope.principal(),toolSpec.id(),inputs,new ProductionMaterial(r.key().item(),quantity),80,unitBatchId(r.id(),unit),destination.dimension(),station,destination.containerId(),destination.dimension(),destination.position(),now,"真实需求自动形成锻造单");
        if(ProductionOrderData.get(server).byId(id).isEmpty())ProductionOrderData.get(server).put(order);
        if(unit==0)SupplyFlowData.get(server).production(r.id(),id);return true;
    }
    private static boolean uncommittedNativeMaterialIsRedundant(MinecraftServer server,NeedRequest r,ProductionOrder o){
        if(!FurnaceWorkAdapter.completedDelivery(server,o)||r.status().terminal()||r.status()==NeedRequestStatus.DELIVERED||r.reserved()!=0||r.inTransit()!=0)return false;
        try{return r.key().requester().equals(o.outputOwner().id())&&r.key().purpose().equals(o.outputOwner().type()+":production:"+o.id())
                &&r.key().destination().equals(FurnaceWorkAdapter.inputPoint(server,o).containerId())
                &&o.inputs().stream().anyMatch(m->m.fingerprint().equals(r.key().item())&&m.quantity()==r.requested());}
        catch(IllegalStateException|NoSuchElementException bad){return false;}
    }
    static boolean industryToolRequester(com.jgeted.sagadyssey.npc.profession.NpcProfession profession,String item){
        return com.jgeted.sagadyssey.social.industry.common.IndustryToolTask.supported(item)
                &&(profession==NpcProfession.WORKER||profession==NpcProfession.FARMER&&item.equals("minecraft:iron_hoe"));
    }
    private static NpcBase industryToolOwner(MinecraftServer server,SubjectRef owner,ItemFingerprint item){
        if(owner.type()!=SubjectRef.SubjectType.CHARACTER)return null;
        for(var l:server.getAllLevels())for(var e:l.getAllEntities())if(e instanceof NpcBase n&&n.isAlive()&&n.getCharacterId().equals(owner.id())
                &&EntityResourceTask.enabled(n)&&industryToolRequester(n.getProfession(),item.itemId()))return n;
        return null;
    }
    private static NpcBase industryOwner(MinecraftServer server,SubjectRef owner){
        if(owner.type()!=SubjectRef.SubjectType.CHARACTER)return null;
        for(var l:server.getAllLevels())for(var e:l.getAllEntities())if(e instanceof NpcBase n&&n.getCharacterId().equals(owner.id())&&EntityResourceTask.enabled(n)&&n.getProfession()==NpcProfession.WORKER)return n;
        return null;
    }
    /** Closed declared recipes only. Normal worlds require a registered, known and owned workstation. */
    private static boolean createDeclaredIndustryProduction(MinecraftServer server,NeedRequest r,SupplyFlowData.Demand scope,FieldworkStorage target,long now,int index){
        var owner=industryOwner(server,scope.principal());if(owner==null||!target.owner().equals(scope.principal())||r.remaining()<=0)return false;
        var flows=SupplyFlowData.get(server);var l=level(server,target.dimension());if(l==null||!owner.level().dimension().identifier().toString().equals(target.dimension()))return false;
        boolean furnace=FurnaceWorkAdapter.supports(r.key().item());String item=r.key().item().itemId();
        boolean rod=r.key().item().equals(new ItemFingerprint("minecraft:fishing_rod",0));
        if(index>0&&(furnace||rod))return false;
        if(!furnace&&!rod&&com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes.outputQuantity(r.key().item())==0)return false;
        if(furnace&&r.remaining()>8)return false;
        boolean fixture=SettlementIndustryAccess.fixture(owner,"sagadyssey.industry.native");
        BlockPos station=null;
        if(fixture){
            for(var p:BlockPos.betweenClosed(target.position().offset(-8,-2,-8),target.position().offset(8,2,8))){
                if(l.hasChunkAt(p)&&matchingIndustryStation(l,p,r.key().item(),furnace)){
                    station=p.immutable();break;
                }
            }
        }else{
            if(!SettlementIndustryAccess.local(owner,target.position())||!validOwner(server,target))return false;
            station=knownIndustryStation(owner,target,r.key().item(),furnace);
        }
        if(station==null)return false;
        try{
            ProductionOrder order;
            if(furnace){
                BlockPos fixed=station;
                if(ProductionOrderData.get(server).assignedTo(owner.getCharacterId()).stream().anyMatch(o->!o.terminal()&&FurnaceWorkAdapter.recognized(o)&&fixed.equals(o.workstationPosition())))return false;
                // Only an explicitly configured same-owner raw buffer can override the output endpoint.
                // It was already registered and known; no inventory/permission is invented by discovery.
                var raw=new ItemFingerprint(FurnaceWorkAdapter.rawItem(r.key().item()),0);FieldworkStorage input=target;
                for(var p:flows.policies())if(!r.key().purpose().equals("CHARACTER:local-kitchen")
                        &&p.principal().equals(scope.principal())&&p.item().equals(raw)&&flows.known(scope.principal()).contains(p.destination())){
                    var known=flows.point(p.destination());if(known!=null&&known.owner().equals(scope.principal())&&known.dimension().equals(target.dimension())&&known.position().distSqr(station)<=256
                            &&(fixture||SettlementIndustryAccess.local(owner,known.position())&&validOwner(server,known))){input=known;break;}
                }
                order=FurnaceWorkAdapter.requestDemand(owner,r,station,input,target,now);
            }else if(rod){
                if(r.remaining()!=1)return false;
                order=WorkshopTask.requestFishingRod(owner,"need:"+r.id(),target,station,now);
            }else{
                int expected=com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes.outputQuantity(r.key().item());if(r.remaining()<expected||index>=64)return false;
                // Existing orders replay their fixed materials; a new batch selects actual lawful native inputs.
                order=WorkshopTask.requestProduct(owner,woodBusiness(r.id(),index),target,station,r.key().item(),now);
            }
            if(index==0)flows.production(r.id(),order.id());return true;
        }catch(IllegalArgumentException unavailable){return false;}
        catch(IllegalStateException conflict){productionReview(NeedRequestData.get(server),r,now,"declared production identity/facility unavailable: "+conflict.getMessage());return true;}
    }
    private static boolean matchingIndustryStation(ServerLevel level, BlockPos position, ItemFingerprint item, boolean furnace){
        return furnace ? FurnaceWorkAdapter.supportsStation(level.getBlockEntity(position),item)
                : level.getBlockState(position).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
    }
    static BlockPos knownIndustryStation(NpcBase operator, FieldworkStorage target,
                                                  ItemFingerprint product, boolean furnace) {
        var server = MinecraftApi.server(operator);
        var flows = SupplyFlowData.get(server);
        var level = (ServerLevel) operator.level();
        return flows.known(target.owner()).stream().map(flows::pointOrNull)
                .filter(point -> point != null && point.dimension().equals(target.dimension())
                        && point.owner().equals(target.owner()))
                .map(FieldworkStorage::position)
                .filter(pos -> pos.distSqr(target.position()) <= 64 && SettlementIndustryAccess.local(operator, pos))
                .filter(pos -> FurnaceWorkAdapter.ownsStation(server, target.dimension(), pos, target.owner())
                        && matchingIndustryStation(level, pos, product, furnace))
                .min(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(target.position()))
                        .thenComparingLong(BlockPos::asLong)).orElse(null);
    }

    /** A known local supplier may produce a missing intermediate in its own stock.
     * The requesting household never acquires the supplier's furnace or ingredients.
     * Fixed batches remain separate from the original transport obligation. */
    private static void openLocalSupplierNeeds(MinecraftServer server,long now) {
        if(!com.jgeted.sagadyssey.core.config.SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.get())return;
        var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);
        var unresolved=new HashSet<UUID>();
        for(var route:flows.routes())if(!route.closed()||route.pending()!=null||route.stage()==SupplyFlowData.Stage.REVIEW)
            unresolved.add(route.need());
        // A deadline is not evidence that a transfer/production vanished. Only never-bound,
        // fully uncommitted intentions may expire; all previous records and receipts remain.
        for(var old:needs.requests())if(LocalSupplierRules.expireUncommitted(old,
                flows.production(old.id())!=null,unresolved.contains(old.id()),now))
            needs.transition(old.id(),NeedRequestStatus.CANCELLED,now,"本地供应意向到期且无制造、预留、在途或未核对转移；保留历史后重新评估");
        var snapshot=new ArrayList<>(needs.requests());
        var claimedParents=new HashSet<UUID>();
        var workers=new ArrayList<NpcBase>();
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities())
            if(entity instanceof NpcBase worker&&worker.getProfession()==NpcProfession.WORKER
                    &&SettlementIndustryAccess.resident(worker))workers.add(worker);
        workers.sort(Comparator.comparing(NpcBase::getCharacterId));
        for(var worker:workers){
            var life=com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).record(worker.getCharacterId());
            if(life==null||life.privateStoragePosition()==null||!SettlementIndustryAccess.local(worker,life.privateStoragePosition()))continue;
            var actor=SubjectRef.character(worker.getCharacterId());
            var stock=flows.pointOrNull(ProductionContainerIds.resolve(server,life.dimension(),life.privateStoragePosition()));
            if(stock==null||!stock.owner().equals(actor)||!validOwner(server,stock))continue;
            for(var prior:snapshot){
                var need=needs.request(prior.id());
                if(!LocalSupplierRules.eligibleParent(need,now)||LocalWorkshopNeeds.child(need)||claimedParents.contains(need.id())
                        ||unresolved.contains(need.id()))continue;
                var linked=needs.dependencies(need.id());
                if(linked.size()>=NeedRequestData.MAX_DEPENDENCIES)continue;
                boolean awaitingSupplier=false;
                for(var id:linked){
                    var child=needs.request(id);if(!LocalSupplierRules.supplier(child))continue;
                    var original=flows.pointOrNull(child.key().destination());
                    // Missing/unloaded stock is unknown, not permission to produce its replacement.
                    var inventory=original==null?null:container(server,original);
                    int remainingStock=inventory==null?Integer.MAX_VALUE:count(inventory,child.key().item());
                    if(LocalSupplierRules.blocksNextBatch(child,unresolved.contains(id),remainingStock)){
                        awaitingSupplier=true;break;
                    }
                }
                if(awaitingSupplier)continue;
                var demand=flows.demand(need.id());var item=need.key().item();
                if(demand==null||demand.destination().equals(stock.containerId())
                        ||!SettlementIndustryAccess.servesLocalPrincipal(worker,demand.principal())
                        ||!flows.known(demand.principal()).contains(stock.containerId())
                        ||new RightsService(RightsStateData.get(server)).can(demand.principal(),AssetRight.TRANSFER,
                            stock.containerId(),0,item,now).status()!=RightsVerdict.Status.ALLOW)continue;
                int knownFree=0;
                for(UUID sourceId:flows.known(demand.principal())){
                    if(sourceId.equals(demand.destination()))continue;
                    var source=flows.pointOrNull(sourceId);
                    if(source==null||!source.dimension().equals(stock.dimension()))continue;
                    knownFree+=(int)Math.min((long)need.remaining()-knownFree,available(server,source,demand.principal(),item,now));
                    if(knownFree>=need.remaining())break;
                }
                int deficit=LocalSupplierRules.uncovered(need,knownFree);if(deficit<1)continue;
                boolean iron=item.equals(new ItemFingerprint("minecraft:iron_ingot",0));
                int batch=iron?Math.min(3,deficit):com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes.outputQuantity(item);
                if(batch<1||knownIndustryStation(worker,stock,item,iron)==null)continue;
                // Existing obligations in this same supplier stock also block overlapping batches.
                if(needs.requests().stream().anyMatch(n->!n.status().terminal()&&n.status()!=NeedRequestStatus.DELIVERED
                        &&n.key().destination().equals(stock.containerId())&&n.key().item().equals(item)))continue;
                if(iron){
                    int raw=available(server,stock,actor,new ItemFingerprint("minecraft:raw_iron",0),now);
                    batch=Math.min(batch,raw);if(batch<1)continue;
                }
                var child=request(server,actor,"local-supplier:"+need.id()+":"+item.itemId(),stock,item,batch,now,now+24000);
                // Existing persisted dependency edges bind this batch to one actual downstream need.
                // A new child has no dependencies and cannot create a cycle. No new ledger is added.
                if(!needs.dependOn(need.id(),child.id(),now))
                    throw new IllegalStateException("new local supplier demand unexpectedly forms a dependency cycle");
                claimedParents.add(need.id());
            }
        }
    }
    /** An empty prepared-food buffer may use up to two already available potatoes from the cook's own stock. */
    private static void openLocalKitchenNeeds(MinecraftServer server, long now) {
        if (!com.jgeted.sagadyssey.core.config.SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.get()) return;
        var flows = SupplyFlowData.get(server);
        var needs = NeedRequestData.get(server);
        var orders = ProductionOrderData.get(server);
        var baked = new ItemFingerprint("minecraft:baked_potato",0);
        var potato = new ItemFingerprint("minecraft:potato",0);
        var coal = new ItemFingerprint("minecraft:coal",0);
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            if (!(entity instanceof NpcBase worker) || worker.getProfession()!=NpcProfession.WORKER
                    || worker.getCommand()!=com.jgeted.sagadyssey.npc.entity.NpcCommand.WORK || worker.getTarget()!=null
                    || !SettlementIndustryAccess.resident(worker)) continue;
            var life = com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).record(worker.getCharacterId());
            if (life.privateStoragePosition()==null || !SettlementIndustryAccess.local(worker,life.privateStoragePosition())) continue;
            var actor = SubjectRef.character(worker.getCharacterId());
            var storage = ProductionContainerIds.resolve(server,life.dimension(),life.privateStoragePosition());
            var point = flows.point(storage);
            if (point==null || !point.owner().equals(actor) || !point.dimension().equals(life.dimension())
                    || !point.position().equals(life.privateStoragePosition()) || available(server,point,actor,baked,now)>0) continue;
            int quantity = Math.min(2,available(server,point,actor,potato,now));
            if (quantity==0 || needs.requests().stream().anyMatch(r -> r.key().destination().equals(storage)
                    && r.key().item().equals(baked) && !r.status().terminal() && r.status()!=NeedRequestStatus.DELIVERED)
                    || orders.assignedTo(worker.getCharacterId()).stream().anyMatch(o -> FurnaceWorkAdapter.recognized(o)
                    && o.output().fingerprint().equals(baked) && !o.terminal())) continue;
            var station = knownIndustryStation(worker,point,baked,true);
            if (station==null || !(level.getBlockEntity(station) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)
                    || !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()
                    || !furnace.getItem(1).isEmpty() && !coal.matches(furnace.getItem(1))
                    || furnace.getItem(1).isEmpty() && available(server,point,actor,coal,now)<1) continue;
            request(server,actor,"local-kitchen",point,baked,quantity,now,now+24000);
        }
    }
    /** Death drops physical cargo through NpcLogisticsEvents; never pretend it was delivered. */
    public static void carrierLost(NpcBase npc,long now){
        var server=MinecraftApi.server(npc);var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);
        for(var route:flows.routes())if(!route.closed()&&npc.getUUID().equals(route.carrierEntity())){
            if(route.stage()==SupplyFlowData.Stage.HELD&&route.pending()==null)release(server,route,now,"运输者死亡，未取货预留已释放");
            else{
                RightsStateData.get(server).releaseReservations(route.token());
                flows.route(route.progress(SupplyFlowData.Stage.REVIEW,route.pending(),now,route.failures(),"运输者死亡，实体货物掉落；在途义务待核对，不作交付"));
                var r=needs.request(route.need());
                if(r.status()==NeedRequestStatus.IN_TRANSIT||r.status()==NeedRequestStatus.MATCHED)
                    needs.transition(r.id(),NeedRequestStatus.UNREACHABLE,now,"持货者死亡，核对实体掉落后另行安排");
            }
        }
        var merchants=MerchantSupplyData.get(server);
        for(var journey:merchants.journeys())if(npc.getUUID().equals(journey.merchantEntity())
                &&journey.stage()!=MerchantSupplyData.Stage.DONE&&journey.stage()!=MerchantSupplyData.Stage.REVIEW){
            RightsStateData.get(server).releaseReservations(journey.token());
            merchants.journey(journey.update(MerchantSupplyData.Stage.REVIEW,journey.transfer(),journey.personalRevision(),journey.cargoRevision(),journey.marketRevision(),journey.time(),now,"商人死亡，货物掉落待核对，不登记虚假卖出或利润"));
        }
    }
    public static void release(MinecraftServer server,SupplyFlowData.Route route,long now,String reason){
        if(route.stage()!=SupplyFlowData.Stage.HELD||route.pending()!=null)throw new IllegalStateException("cannot release carried or uncertain route");
        var needs=NeedRequestData.get(server);RightsStateData.get(server).releaseReservations(route.token());
        needs.acknowledge(receipt(route.id(),"release"),route.need(),NeedRequestData.Action.RELEASE_RESERVED,route.quantity(),now);
        SupplyFlowData.get(server).route(route.progress(SupplyFlowData.Stage.CANCELLED,null,now,route.failures(),reason));
        LogisticsData.get(server).cancelManagedOrder(route.id(),route.need(),now);
    }
}
