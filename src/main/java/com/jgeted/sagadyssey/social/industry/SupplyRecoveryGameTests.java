package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Normal dispatch only. Initial finite resources, an absolute timed grant, and a declared
 * physical road opening are the only interventions. No attempts, goods, tasks or receipts
 * are written after initialization. "continue" reuses a complete stopped world. */
@GameTestHolder("sagadyssey_industries_completion")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class SupplyRecoveryGameTests {
    private static final String R32="recovery-r32-start", R32_CONTINUE="recovery-r32-continue";
    private static final String EXPIRE="recovery-expire-start", EXPIRE_CONTINUE="recovery-expire-continue";
    private static final String MARK="recovery-manifest.nbt";
    private static CompoundTag prior;
    private static String phase(){return System.getProperty("sagadyssey.completion.phase", "disabled");}
    private static boolean selected(){return Set.of(R32,R32_CONTINUE,EXPIRE,EXPIRE_CONTINUE).contains(phase());}
    private static boolean continuing(){return phase().equals(R32_CONTINUE)||phase().equals(EXPIRE_CONTINUE);}
    private static boolean r32(){return phase().equals(R32)||phase().equals(R32_CONTINUE);}

    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!selected()||!Boolean.getBoolean("sagadyssey.industry.native"))return List.of();
        int limit=phase().equals(R32)?22600:phase().equals(EXPIRE)?1700:6000;
        return List.of(new TestFunction("industry_recovery",phase(),"sagadyssey_industries_completion:empty",
                limit,0,true,SupplyRecoveryGameTests::run));
    }

    @SubscribeEvent public static void boot(ServerStartedEvent event){
        if(!selected()||!(event.getServer() instanceof GameTestServer))return;
        prior=null;
        var server=event.getServer();var level=server.overworld();var root=server.getWorldPath(LevelResource.ROOT);
        if(!continuing()){
            require(!Files.exists(root.resolve(MARK)),"start requires a new independent world; do not overwrite old test objects");
            return;
        }
        try{
            prior=NbtIo.readCompressed(root.resolve(MARK),NbtAccounter.unlimitedHeap());
            require(prior.getStringOr("Kind", "").equals(r32()?"R32":"EXPIRE"),"continuation kind must match original world");
            var checkpoint=NbtIo.readCompressed(root.resolve(r32()?"recovery-r32-attempts32.nbt":"recovery-expire-bound-checkpoint.nbt"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Manifest");
            require(checkpoint.getLongOr("Start", 0L)==prior.getLongOr("Start", 0L)&&checkpoint.getCompoundOrEmpty("Stock").equals(prior.getCompoundOrEmpty("Stock")),"checkpoint does not describe the original initial world");
            for(String key:List.of("Person","Body","Home","RootNeed"))require(checkpoint.read(key, UUIDUtil.CODEC).orElseThrow().equals(prior.read(key, UUIDUtil.CODEC).orElseThrow()),"checkpoint identity mismatch: "+key);
            // Test metadata remembers observed identities; it never creates/rebinds any production record.
            for(String key:r32()?List.of("ObservedRootOrder","ObservedMaterialNeed"):List.of("ObservedRootOrder","ObservedChild","ObservedChildOrder")){
                require(checkpoint.read(key, UUIDUtil.CODEC).isPresent(),"missing observed original binding "+key);prior.store(key, UUIDUtil.CODEC,checkpoint.read(key, UUIDUtil.CODEC).orElseThrow());
            }
            var saved=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            long time=saved.getLongOr("Time", 0L), day=saved.getLongOr("DayTime", 0L);
            require(time>=prior.getLongOr("Start", 0L),"saved clock precedes original initialization");
            server.getWorldData().overworldData().setGameTime(time);
            server.getWorldData().overworldData().setDayTime(day);
            var rules=saved.getCompoundOrEmpty("game_rules");
            for (String key : List.of("minecraft:advance_time", "minecraft:spawn_mobs",
                    "minecraft:random_tick_speed", "minecraft:mob_griefing")) {
                require(MinecraftApi.containsTag(rules, key, MinecraftApi.ANY_NUMERIC_TAG), "missing saved game rule " + key);
            }
            MinecraftApi.loadRules(level, rules);
            server.setDifficulty(Difficulty.byId(saved.getByteOr("Difficulty", (byte) 0)),true);
            var cp=new ChunkPos(at(0,0,0));
            for(int x=cp.x-1;x<=cp.x+2;x++)for(int z=cp.z-1;z<=cp.z+2;z++){
                level.setChunkForced(x,z,true);level.getChunk(x,z);
            }
            Sagadyssey.LOGGER.info("RECOVERY_RESTART phase={} savedTime={} savedDay={} originalStart={} absoluteGrant={} noOfflineAdvance=true noReinitialization=true source={}",
                    phase(),time,day,prior.getLongOr("Start", 0L),prior.getLongOr("GrantAt", 0L),root);
        }catch(Exception failure){throw new IllegalStateException("complete stopped recovery world required",failure);}
    }

    private static void run(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();long runStart=level.getGameTime();
        var marker=continuing()?prior.copy():initialize(h);
        var state=new Observation();state.resumeStart=runStart;
        sprint(h,phase().equals(R32)?22600:phase().equals(EXPIRE)?1700:6000);
        h.onEachTick(()->{
            if(state.finished)return;
            try{
                long now=level.getGameTime();
                require(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("industry-stop-request.json")),"external frozen wall-clock stop");
                var entity=level.getEntity(marker.read("Body", UUIDUtil.CODEC).orElseThrow());
                // A newly forced region publishes its queued original entities on
                // following ticks too. Wait for that exact UUID, never respawn it.
                if(entity==null&&now-runStart<100)return;
                require(entity instanceof NpcBase,"original body did not load; no replacement spawn");
                var worker=(NpcBase)entity;
                require(worker.isAlive()&&worker.getCharacterId().equals(marker.read("Person", UUIDUtil.CODEC).orElseThrow()),"original living identity changed");
                require(HouseholdData.get(server).forMember(worker.getCharacterId()).id().equals(marker.read("Home", UUIDUtil.CODEC).orElseThrow()),"original home changed");
                if(r32()){
                    var carrier=level.getEntity(marker.read("CarrierBody", UUIDUtil.CODEC).orElseThrow());
                    if(carrier==null&&continuing()&&now-runStart<100)return;
                    require(carrier instanceof NpcBase npc&&npc.isAlive()&&npc.getCharacterId().equals(marker.read("CarrierPerson", UUIDUtil.CODEC).orElseThrow()),"original hired carrier identity missing or changed");
                }
                var stock=FieldworkStorage.load(marker.getCompoundOrEmpty("Stock"));
                require(box(h,stock)!=null,"original private stock unavailable");
                endpoint(server,stock);
                var root=NeedRequestData.get(server).request(marker.read("RootNeed", UUIDUtil.CODEC).orElseThrow());
                var scope=SupplyFlowData.get(server).demand(root.id());
                require(root.key().requester().equals(worker.getCharacterId())&&root.key().destination().equals(stock.containerId())
                        &&root.key().item().equals(fp("fishing_rod"))&&root.requested()==1&&root.key().windowStart()==marker.getLongOr("Start", 0L)
                        &&scope!=null&&scope.principal().equals(stock.owner())&&scope.destination().equals(stock.containerId()),"original root identity, scope or destination changed");
                if(!state.loaded){
                    state.loaded=true;snapshot(server,marker,worker,phase()+"-entry.nbt");
                    Sagadyssey.LOGGER.info("RECOVERY_ENTRY phase={} tick={} runStart={} person={} body={} originalNeed={}",phase(),now,runStart,worker.getCharacterId(),worker.getUUID(),marker.read("RootNeed", UUIDUtil.CODEC).orElseThrow());
                }
                if(r32())observe32(h,marker,worker,state,now);else observeExpiry(h,marker,worker,state,now);
            }catch(Exception failure){
                state.finished=true;
                var entity=level.getEntity(marker.read("Body", UUIDUtil.CODEC).orElseThrow());
                snapshot(server,marker,entity instanceof NpcBase npc?npc:null,phase()+"-first-failure.nbt");
                saveWorld(server);
                Sagadyssey.LOGGER.error("RECOVERY_FIRST_FAILURE phase={} tick={} originalStart={} reason={} originalFailureMustRemain=true",phase(),level.getGameTime(),marker.getLongOr("Start", 0L),failure.toString());
                h.fail(failure.toString());
            }
        });
    }

    private static CompoundTag initialize(GameTestHelper h){
        floor(h,0);var level=h.getLevel();var server=level.getServer();server.setDifficulty(Difficulty.NORMAL,true);
        long start=level.getGameTime();var worker=npc(h,3,6,NpcProfession.WORKER);
        NpcBase carrier=r32()?npc(h,8,4,NpcProfession.COURIER):null;
        var home=family(h,"bounded supply recovery",carrier==null?List.of(worker):List.of(worker,carrier));bed(h,worker,4,18);
        if(carrier!=null){
            bed(h,carrier,7,18);hire(carrier,worker,start);
            require(SupplyTransportTask.eligible(carrier)&&worker.getCharacterId().equals(NpcWorkContext.from(carrier).employerCharacterId()),"original hired courier has actual cargo capacity and original employer authority");
        }
        var actor=SubjectRef.character(worker.getCharacterId());var stock=point(h,at(15,0,6),actor,TransferIntent.DELIVERY);
        level.setBlockAndUpdate(at(16,0,6),Blocks.CRAFTING_TABLE.defaultBlockState());
        require(NpcLifeData.get(server).assignPrivateStorage(server,actor.id(),stock.dimension(),stock.position(),start),"initial private stock assignment");
        var flows=SupplyFlowData.get(server);flows.reveal(actor,stock.containerId());
        var mark=new CompoundTag();mark.putString("Kind",r32()?"R32":"EXPIRE");mark.putLong("Start",start);
        mark.store("Person", UUIDUtil.CODEC,actor.id());mark.store("Body", UUIDUtil.CODEC,worker.getUUID());mark.store("Home", UUIDUtil.CODEC,home);mark.put("Stock",stock.save());
        if(r32()){
            mark.store("CarrierBody", UUIDUtil.CODEC,carrier.getUUID());mark.store("CarrierPerson", UUIDUtil.CODEC,carrier.getCharacterId());
            box(h,stock).setItem(0,new ItemStack(Items.STICK,3));
            var source=point(h,at(8,0,6),SubjectRef.household(home),TransferIntent.GIFT);
            box(h,source).setItem(0,new ItemStack(Items.STRING,2));flows.reveal(actor,source.containerId());
            long grantAt=start+19800;var grantId=UUID.randomUUID();
            RightsStateData.get(server).grant(new RightGrant(grantId,source.containerId(),actor,Set.of(AssetRight.TRANSFER),
                    "initial finite household material becomes available at the declared game tick",grantAt,null,true));
            require(new RightsService(RightsStateData.get(server)).can(actor,AssetRight.TRANSFER,source.containerId(),0,fp("string"),start).status()==RightsVerdict.Status.DENY,"future grant is not currently active");
            mark.put("Source",source.save());mark.putLong("GrantAt",grantAt);mark.store("Grant", UUIDUtil.CODEC,grantId);
        }else{
            box(h,stock).setItem(0,new ItemStack(Items.OAK_PLANKS,4));box(h,stock).setItem(1,new ItemStack(Items.STRING,4));
            for(int x=10;x<=22;x++)for(int z=0;z<=12;z++)if(x==10||x==22||z==0||z==12)
                for(int y=0;y<3;y++)level.setBlockAndUpdate(at(x,y,z),Blocks.STONE.defaultBlockState());
            mark.putLong("RoadOpensAt",start+1800);
        }
        var root=SupplyFlowService.request(server,actor,"recovery:"+(r32()?"32":"expiry")+":original-rod",stock,fp("fishing_rod"),1,start,start+(r32()?28000:1200));
        mark.store("RootNeed", UUIDUtil.CODEC,root.id());writeOnce(server,MARK,mark);
        snapshot(server,mark,worker,phase()+"-initial.nbt");
        Sagadyssey.LOGGER.info("RECOVERY_INITIAL phase={} tick={} person={} body={} home={} need={} stock={} breadPerOriginalNpc=8 originalPeople={} carrier={} sticks={} planks={} stringPrivate={} stringSource={} grantAt={} rootDeadline={} initialOnly=true normalDispatchOnly=true sprint=true daylight=false randomTickSpeed=0 unrelatedMobSpawn=false",
                phase(),start,actor.id(),worker.getUUID(),home,root.id(),stock.containerId(),r32()?2:1,carrier==null?null:carrier.getCharacterId(),r32()?3:0,r32()?0:4,r32()?0:4,r32()?2:0,mark.getLongOr("GrantAt", 0L),root.key().deadline());
        return mark;
    }

    private static void observe32(GameTestHelper h,CompoundTag mark,NpcBase worker,Observation state,long now){
        var server=h.getLevel().getServer();var needs=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);
        long start=mark.getLongOr("Start", 0L),grantAt=mark.getLongOr("GrantAt", 0L);
        var stock=FieldworkStorage.load(mark.getCompoundOrEmpty("Stock"));var source=FieldworkStorage.load(mark.getCompoundOrEmpty("Source"));
        endpoint(server,source);
        require(RightsStateData.get(server).context(source.containerId()).defaultBeneficialOwner().equals(SubjectRef.household(mark.read("Home", UUIDUtil.CODEC).orElseThrow())),"finite source title changed");
        UUID orderId=flows.production(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow());
        if(orderId==null){require(now-start<1000,"normal root did not create an original fishing rod order");return;}
        var order=ProductionOrderData.get(server).byId(orderId).orElseThrow();
        require(order.recipeId().equals(WorkshopTask.FISHING_ROD)&&order.outputOwner().equals(stock.owner()),"wrong original product/order owner");
        if(mark.read("ObservedRootOrder", UUIDUtil.CODEC).isPresent())require(mark.read("ObservedRootOrder", UUIDUtil.CODEC).orElseThrow().equals(orderId),"original root production binding replaced");
        else mark.store("ObservedRootOrder", UUIDUtil.CODEC,orderId);
        var matching=needs.requests().stream().filter(n->n.key().item().equals(fp("string"))&&n.key().purpose().endsWith(":production:"+orderId)).toList();
        require(matching.size()<=1,"duplicate material requests for same original order");
        if(matching.isEmpty()){require(now-start<1000,"original string material request missing");return;}
        var material=matching.getFirst();
        require(material.requested()==2&&material.key().requester().equals(worker.getCharacterId()),"wrong material request identity/quantity");
        if(mark.read("ObservedMaterialNeed", UUIDUtil.CODEC).isPresent())require(mark.read("ObservedMaterialNeed", UUIDUtil.CODEC).orElseThrow().equals(material.id()),"original material identity replaced across full restart");
        else mark.store("ObservedMaterialNeed", UUIDUtil.CODEC,material.id());
        if(state.material==null)state.material=material.id();
        require(state.material.equals(material.id()),"material request replaced");
        if(state.lastAttempts!=material.attempts()){
            Sagadyssey.LOGGER.info("RECOVERY_ATTEMPT tick={} request={} attempts={} maximum={} status={} detail={} sourceString={} cargoString={}",now,material.id(),material.attempts(),material.maxAttempts(),material.status(),material.detail(),count(box(h,source),Items.STRING),count(worker.getTransportInventory(),Items.STRING));
            state.lastAttempts=material.attempts();
        }
        if(!continuing()){
            if(material.attempts()==32&&!state.reached32){
                require(now<start+19500,"32-count preparation cutoff missed");
                require(material.delivered()==0&&material.reserved()==0&&material.inTransit()==0&&count(box(h,source),Items.STRING)==2,"effects occurred before all 32 unsuccessful attempts");
                state.reached32=true;snapshot(server,mark,worker,"recovery-r32-attempts32.nbt");
                Sagadyssey.LOGGER.info("RECOVERY_32_NATURAL tick={} need={} order={} first32=true countsNeverWrittenByTest=true",now,material.id(),orderId);
            }
            if(now>=start+19500)require(state.reached32,"target path not reached before frozen 19500 preparation boundary");
        }else{
            require(material.attempts()==32,"continuation must retain original natural 32 attempts; no reset allowed");
            require(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("recovery-r32-attempts32.nbt")),"missing original natural-32 evidence");
            state.reached32=true;
        }
        require(material.attempts()<=32,"attempt budget silently changed");
        var authority=new RightsService(RightsStateData.get(server)).can(stock.owner(),AssetRight.TRANSFER,source.containerId(),0,fp("string"),now);
        if(now<grantAt){
            require(authority.status()==RightsVerdict.Status.DENY&&count(box(h,source),Items.STRING)==2,"future permission bypass or premature source deduction");
            return;
        }
        require(authority.status()==RightsVerdict.Status.ALLOW,"initial absolute future permission did not become active");
        if(state.recoveryOrigin<0){
            var carrier=(NpcBase)h.getLevel().getEntity(mark.read("CarrierBody", UUIDUtil.CODEC).orElseThrow());
            require(carrier!=null&&SupplyTransportTask.eligible(carrier)&&NpcSchedulePlanner.allowsWork(carrier)
                    &&NpcSchedulePlanner.allowsWork(worker)&&worker.getCharacterId().equals(NpcWorkContext.from(carrier).employerCharacterId()),
                    "R32 PRECONDITION: original worker/carrier schedule or employment unavailable when grant recovered; do not call this a retry defect");
            state.recoveryOrigin=continuing()?state.resumeStart:grantAt;
            snapshot(server,mark,worker,phase()+"-conditions-restored.nbt");
            Sagadyssey.LOGGER.info("RECOVERY_CONDITIONS tick={} origin={} absoluteGrant={} continuation={} originalFailureNotRegraded=true permissions=ALLOW sourceAvailable={} originalNeed={}",now,state.recoveryOrigin,grantAt,continuing(),SupplyFlowService.available(server,source,stock.owner(),fp("string"),now),material.id());
        }
        var routes=flows.routes().stream().filter(r->r.need().equals(material.id())).toList();
        require(routes.size()<=1,"recovery generated multiple routes instead of one original material delivery");
        for(var route:routes){
            require(route.source().equals(source.containerId())&&route.destination().equals(stock.containerId())&&route.quantity()==2,"route identity changed");
            require(route.stage()!=SupplyFlowData.Stage.REVIEW,"uncertain transfer is not recovery success");
            if(route.stage()==SupplyFlowData.Stage.CARRYING||route.stage()==SupplyFlowData.Stage.DONE){
                if(state.pickupAt<0){state.pickupAt=now;snapshot(server,mark,worker,phase()+"-pickup.nbt");
                    Sagadyssey.LOGGER.info("RECOVERY_PICKUP tick={} elapsed={} need={} route={} carrier={} quantity=2",now,now-state.recoveryOrigin,material.id(),route.id(),route.carrierCharacter());}
                require(mark.read("CarrierBody", UUIDUtil.CODEC).orElseThrow().equals(route.carrierEntity())&&mark.read("CarrierPerson", UUIDUtil.CODEC).orElseThrow().equals(route.carrierCharacter()),"wrong original hired carrier identity");
            }
        }
        if(now>=state.recoveryOrigin+600)require(state.pickupAt>=0&&state.pickupAt<=state.recoveryOrigin+600,"R32 HARD FAIL: lawful original material not picked up within 600 ticks; keep first failure");
        require(now<=start+22500,"original whole-world 22500 tick bound exceeded");
        if(order.status()!=ProductionOrderStatus.COMPLETED||needs.request(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()).delivered()!=1){
            require(now<state.recoveryOrigin+2400,"original worker did not complete and acknowledge real rod within delivery/work window");return;
        }
        verifyRod(h,mark,worker,order,routes,material);
        if(state.acceptedAt<0){state.acceptedAt=now;snapshot(server,mark,worker,phase()+"-produced.nbt");}
        if(now-state.acceptedAt<300)return;
        state.finished=true;snapshot(server,mark,worker,phase()+"-pass.nbt");saveWorld(server);
        Sagadyssey.LOGGER.info("RECOVERY_R32_ACCEPT tick={} initial={} origin={} pickup={} order={} material={} attempts={} realLabor=200 rod=1 consumedString=2 consumedStick=3 duplicateWindow=300 fullWorldContinuation={} originalFailureRetained=true",now,start,state.recoveryOrigin,state.pickupAt,order.id(),material.id(),material.attempts(),continuing());h.succeed();
    }

    private static void verifyRod(GameTestHelper h,CompoundTag mark,NpcBase worker,ProductionOrder order,List<SupplyFlowData.Route> routes,NeedRequest material){
        var server=h.getLevel().getServer();var stock=FieldworkStorage.load(mark.getCompoundOrEmpty("Stock"));var source=FieldworkStorage.load(mark.getCompoundOrEmpty("Source"));
        var batch=ProductionBatchData.get(server).batch(order.batchId());
        require(batch!=null&&batch.status()==ProductionBatchStatus.COMPLETED&&batch.workerId().equals(worker.getCharacterId())&&batch.outputOwner().equals(stock.owner()),"missing real original batch");
        require(ProductionLaborData.get(server).worked(order.id())==200&&batch.requiredWorkTicks()==200,"rod requires actual 200 work ticks");
        require(order.inputs().equals(List.of(new ProductionMaterial(fp("stick"),3),new ProductionMaterial(fp("string"),2))),"actual rod inputs changed");
        require(routes.size()==1&&routes.getFirst().stage()==SupplyFlowData.Stage.DONE&&routes.getFirst().pending()==null,"actual original material route not settled");
        verifyMaterialTransfers(server,mark,material,routes.getFirst(),source,stock);
        require(material.delivered()==2&&material.reserved()==0&&material.inTransit()==0,"original material obligation not actually delivered once");
        var root=NeedRequestData.get(server).request(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow());
        require(root.delivered()==1&&root.reserved()==0&&root.inTransit()==0,"original rod request not acknowledged once");
        require(count(box(h,source),Items.STRING)==0&&physical(h,worker,stock,source,Items.STRING)==0&&physical(h,worker,stock,source,Items.STICK)==0&&physical(h,worker,stock,source,Items.FISHING_ROD)==1,"finite inputs/physical rod conservation");
        require(ProductionOrderData.get(server).ordersForWorker(worker.getCharacterId()).size()==1,"duplicate or unrelated manufacturing in bounded case");
        require(RightsStateData.get(server).reservationsForToken(routes.getFirst().token(),h.getLevel().getGameTime()).isEmpty(),"finished route still reserves material");
    }

    private static void verifyMaterialTransfers(MinecraftServer server,CompoundTag mark,NeedRequest material,SupplyFlowData.Route route,FieldworkStorage source,FieldworkStorage stock){
        var journal=MinecraftApi.listOfType(NeedRequestData.get(server).save(new CompoundTag(),server.registryAccess()), "Receipts",Tag.TAG_COMPOUND);
        var ledger=TransferLedgerData.get(server);UUID cargo=ContainerIds.npcCargo(mark.read("CarrierPerson", UUIDUtil.CODEC).orElseThrow());
        for(String action:List.of("DISPATCH","DELIVER")){
            int matches=0;
            for(var raw:journal){var row=(CompoundTag)raw;
                if(!row.read("Request", UUIDUtil.CODEC).orElseThrow().equals(material.id())||!row.getStringOr("Action", "").equals(action))continue;
                matches++;require(row.getIntOr("Quantity", 0)==2,"material receipt quantity changed");
                var receipt=ledger.receipt(row.read("Id", UUIDUtil.CODEC).orElseThrow());var identity=ledger.requestIdentity(row.read("Id", UUIDUtil.CODEC).orElseThrow());
                require(receipt!=null&&receipt.successful()&&receipt.lawful()&&identity!=null,"missing verified lawful physical transfer");
                boolean pickup=action.equals("DISPATCH");
                require(identity.getStringOr("Intent", "").equals("DELIVERY")&&identity.getStringOr("Authority", "").equals(route.token()),"wrong transfer intent/route authority");
                require(SubjectRef.load(identity.getCompoundOrEmpty("Actor")).equals(SubjectRef.character(mark.read(pickup?"Person":"CarrierPerson", UUIDUtil.CODEC).orElseThrow())),"wrong pickup principal or delivery custodian");
                var legs=MinecraftApi.listOfType(identity, "Legs",Tag.TAG_COMPOUND);require(legs.size()==1,"unexpected transfer legs");var leg=legs.getCompoundOrEmpty(0);
                require(leg.read("Source", UUIDUtil.CODEC).orElseThrow().equals(pickup?source.containerId():cargo)&&leg.read("Destination", UUIDUtil.CODEC).orElseThrow().equals(pickup?cargo:stock.containerId())
                        &&ItemFingerprint.load(leg.getCompoundOrEmpty("Item")).equals(fp("string"))&&leg.getIntOr("Quantity", 0)==2,"wrong physical material path");
            }
            require(matches==1,"original material requires exactly one verified "+action+" receipt");
        }
    }

    private static void observeExpiry(GameTestHelper h,CompoundTag mark,NpcBase worker,Observation state,long now){
        var server=h.getLevel().getServer();var level=h.getLevel();long start=mark.getLongOr("Start", 0L);
        var needs=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);var stock=FieldworkStorage.load(mark.getCompoundOrEmpty("Stock"));
        var children=needs.requests().stream().filter(n->n.key().purpose().startsWith("CHARACTER:local-workshop:")&&n.key().item().equals(fp("stick"))).toList();
        require(children.size()<=1,"duplicate stick child before expiry");
        if(children.isEmpty()){require(now<start+1000,"PRECONDITION_UNSUPPORTED: target upstream child not created before preparation cutoff");return;}
        var child=children.getFirst();UUID bound=flows.production(child.id());
        UUID rodId=flows.production(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow());
        if(bound==null||rodId==null){require(now<start+1000,"PRECONDITION_UNSUPPORTED: both original rod and short-window child must bind before expiry; late stock cannot authorize a new expired rod order");return;}
        var order=ProductionOrderData.get(server).byId(bound).orElseThrow();
        var rod=ProductionOrderData.get(server).byId(rodId).orElseThrow();
        require(child.key().purpose().equals("CHARACTER:local-workshop:"+mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()+":minecraft:stick")
                &&child.key().deadline()==start+1200&&needs.dependencies(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()).contains(child.id()),"PRECONDITION_UNSUPPORTED: short child must come from original root, not its separate 72000-tick production-material need");
        require(order.recipeId().equals(WorkshopTask.STICKS)&&rod.recipeId().equals(WorkshopTask.FISHING_ROD)
                &&order.createdAt()<start+1200&&rod.createdAt()<start+1200&&order.outputOwner().equals(stock.owner())&&rod.outputOwner().equals(stock.owner())
                &&order.targetContainerId().equals(stock.containerId())&&rod.targetContainerId().equals(stock.containerId()),"original named commitments were not established lawfully before deadline");
        require(order.status()!=ProductionOrderStatus.CANCELLED&&rod.status()!=ProductionOrderStatus.CANCELLED
                &&!needs.request(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()).status().terminal(),"PRECONDITION_UNSUPPORTED: original parent/order was cancelled; this diagnostic does not demand recreation of a cancelled commitment");
        var observed=Map.of("ObservedRootOrder",rodId,"ObservedChild",child.id(),"ObservedChildOrder",bound);
        for(var binding:observed.entrySet()){
            if(mark.read(binding.getKey(), UUIDUtil.CODEC).isPresent())require(mark.read(binding.getKey(), UUIDUtil.CODEC).orElseThrow().equals(binding.getValue()),"original production/dependency binding changed: "+binding.getKey());
            else mark.store(binding.getKey(), UUIDUtil.CODEC,binding.getValue());
        }
        if(!continuing()){
            require(ProductionLaborData.get(server).worked(order.id())==0,"closed physical route allowed early production");
            if(now<start+1500)return;
            require(!child.status().terminal()&&child.status()!=NeedRequestStatus.DELIVERED,"bound expired child incorrectly cleared without resolving production");
            snapshot(server,mark,worker,"recovery-expire-bound-checkpoint.nbt");saveWorld(server);state.finished=true;
            Sagadyssey.LOGGER.info("RECOVERY_EXPIRE_CHECKPOINT tick={} root={} rootOrder={} rootOrderCreated={} child={} childDeadline={} childOrder={} childOrderCreated={} labor=0 completeResult=false originalWorldMustContinue=true",now,mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow(),rodId,rod.createdAt(),child.id(),child.key().deadline(),order.id(),order.createdAt());h.succeed();return;
        }
        if(now>=mark.getLongOr("RoadOpensAt", 0L)&&!state.roadOpened){
            for(int y=0;y<3;y++){var p=at(10,y,6);require(level.getBlockState(p).is(Blocks.STONE),"frozen original road barrier changed");level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}
            state.roadOpened=true;snapshot(server,mark,worker,"recovery-expire-road-restored.nbt");
            Sagadyssey.LOGGER.info("RECOVERY_ROAD_OPEN tick={} originalDeclared={} removedFixtureBarrier=3 noItemsGranted=true noNpcTeleport=true",now,mark.getLongOr("RoadOpensAt", 0L));
        }
        if(order.status()!=ProductionOrderStatus.COMPLETED||rod.status()!=ProductionOrderStatus.COMPLETED){require(now<start+5900,"expired original work did not finish within frozen diagnostic window");return;}
        require(ProductionLaborData.get(server).worked(order.id())==200&&ProductionLaborData.get(server).worked(rod.id())==200,"real original stick and rod labor required");
        require(physical(h,worker,stock,null,Items.OAK_PLANKS)==2&&physical(h,worker,stock,null,Items.STRING)==2&&physical(h,worker,stock,null,Items.STICK)==1&&physical(h,worker,stock,null,Items.FISHING_ROD)==1,"late actual products and finite inputs must reconcile");
        require(ProductionOrderData.get(server).ordersForWorker(worker.getCharacterId()).size()==2,"expired intent made duplicate real batches");
        if(state.acceptedAt<0){state.acceptedAt=now;snapshot(server,mark,worker,"recovery-expire-late-products.nbt");}
        if(now-state.acceptedAt<100)return;
        require(now<=start+6000,"whole expiry diagnostic cutoff");state.finished=true;snapshot(server,mark,worker,"recovery-expire-diagnostic-complete.nbt");saveWorld(server);
        Sagadyssey.LOGGER.info("RECOVERY_EXPIRE_DIAGNOSTIC tick={} child={} childStatus={} childDelivered={} root={} rootStatus={} rootDelivered={} realRod=1 realLabor=400 safetyPassed=true automaticSettlementNotAsserted=true nextWindowDeadlockNotProven=true",
                now,child.id(),needs.request(child.id()).status(),needs.request(child.id()).delivered(),mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow(),needs.request(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()).status(),needs.request(mark.read("RootNeed", UUIDUtil.CODEC).orElseThrow()).delivered());h.succeed();
    }

    private static int physical(GameTestHelper h,NpcBase worker,FieldworkStorage stock,FieldworkStorage source,Item item){
        int total=count(box(h,stock),item)+(source==null?0:count(box(h,source),item));
        for(var e:h.getLevel().getAllEntities()){
            if(e instanceof NpcBase npc){
                total+=npc.getPersonalInventory().count(s->s.is(item))+count(npc.getTransportInventory(),item);
                if(npc.getMainHandItem().is(item))total+=npc.getMainHandItem().getCount();
                if(npc.getOffhandItem().is(item))total+=npc.getOffhandItem().getCount();
            }else if(e instanceof ItemEntity drop&&drop.getItem().is(item))total+=drop.getItem().getCount();
        }
        return total;
    }
    private static void endpoint(MinecraftServer server,FieldworkStorage original){
        var current=SupplyFlowData.get(server).point(original.containerId());var context=RightsStateData.get(server).context(original.containerId());
        require(current.dimension().equals(original.dimension())&&current.position().equals(original.position())&&current.owner().equals(original.owner())
                &&current.policy()==original.policy()&&context!=null&&context.defaultBeneficialOwner().equals(original.owner())&&context.custodian().equals(original.owner()),
                "original registered endpoint position, owner or custody changed");
    }
    private static void snapshot(MinecraftServer server,CompoundTag mark,NpcBase worker,String name){
        var root=new CompoundTag();root.put("Manifest",mark.copy());root.putLong("Time",server.overworld().getGameTime());
        root.putLong("DayTime",server.overworld().getDayTime());root.putString("Phase",phase());
        root.put("Needs",NeedRequestData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Supply",SupplyFlowData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Orders",ProductionOrderData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Batches",ProductionBatchData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Labor",ProductionLaborData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Rights",RightsStateData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Transfers",TransferLedgerData.get(server).save(new CompoundTag(),server.registryAccess()));
        root.put("Life",NpcLifeData.get(server).save(new CompoundTag(),server.registryAccess()));
        if(worker!=null)root.put("OriginalEntity",MinecraftApi.saveEntity(worker));
        if(mark.read("CarrierBody", UUIDUtil.CODEC).isPresent()){
            var carrier=server.overworld().getEntity(mark.read("CarrierBody", UUIDUtil.CODEC).orElseThrow());
            if(carrier instanceof NpcBase npc)root.put("OriginalCarrier",MinecraftApi.saveEntity(npc));
        }
        writeOnce(server,name,root);
    }
    private static void writeOnce(MinecraftServer server,String name,CompoundTag tag){
        try{var path=server.getWorldPath(LevelResource.ROOT).resolve(name);if(!Files.exists(path))NbtIo.writeCompressed(tag,path);}
        catch(Exception e){throw new IllegalStateException("cannot preserve "+name,e);}
    }
    private static void saveWorld(MinecraftServer server){server.saveEverything(true,false,true);}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    private static final class Observation{
        boolean finished,loaded,reached32,roadOpened;long resumeStart,recoveryOrigin=-1,pickupAt=-1,acceptedAt=-1;int lastAttempts=-1;UUID material;
    }
}
