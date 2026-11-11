package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Files;
import java.util.*;

/** Full stopped-world continuation. Only initial three planks and a household bed target are added. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class HouseholdBedWoolGameTests {
    private static final String PHASE="household-bed-wool";
    private static final UUID PERSON=UUID.fromString("9b300dd0-2fff-3d87-9e0e-e7261a45fcc2");
    private static final UUID BODY=UUID.fromString("d53d52b5-a5a2-37c5-a108-74008393f4b8");
    private static final UUID SHEEP=UUID.fromString("86a51d53-155f-4d81-ac21-eccb4c733345");
    private static final UUID HOME=UUID.fromString("de169e51-6ca2-3ca3-afdd-a0f55b912cea");
    private static final ItemFingerprint BED=new ItemFingerprint("minecraft:white_bed",0);
    private static final ItemFingerprint WOOL=new ItemFingerprint("minecraft:white_wool",0);
    private static final ItemFingerprint PLANKS=new ItemFingerprint("minecraft:oak_planks",0);
    private static NaturalVillageBuilding village;
    private static long savedTime, savedDay;
    private static boolean selected(){return PHASE.equals(System.getProperty("sagadyssey.playability.phase",""));}

    @GameTestGenerator public static Collection<TestFunction> tests(){
        return selected()?List.of(new TestFunction("household_bed_wool","household_bed_wool", "sagadyssey_playability_fix:empty",18000,0,true,HouseholdBedWoolGameTests::run)):List.of();
    }

    @SubscribeEvent public static void boot(ServerStartedEvent event){
        if(!selected()||!(event.getServer() instanceof GameTestServer))return;
        var server=event.getServer();var level=server.overworld();
        try{
            var path=server.getWorldPath(LevelResource.ROOT).resolve("level.dat");
            var saved=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            savedTime=saved.getLongOr("Time", 0L);savedDay=saved.getLongOr("DayTime", 0L);
            if(savedTime<7936||savedDay<8890)throw new IllegalStateException("complete original stopped wool world required");
            // GameTestServer creates test world metadata. Restore the original clock, without adding ticks or changing daylight.
            server.getWorldData().overworldData().setGameTime(savedTime);
            server.getWorldData().overworldData().setDayTime(savedDay);
            // The test server also replaces rules. Restore the four original values, not a preferred test outcome.
            var savedRules=saved.getCompoundOrEmpty("GameRules");
            for(String key:List.of("doDaylightCycle","doMobSpawning","randomTickSpeed","mobGriefing"))
                if(!MinecraftApi.containsTag(savedRules, key,Tag.TAG_STRING))throw new IllegalStateException("missing original game rule "+key);
            boolean daylight=Boolean.parseBoolean(savedRules.getStringOr("doDaylightCycle", ""));
            boolean spawning=Boolean.parseBoolean(savedRules.getStringOr("doMobSpawning", ""));
            boolean griefing=Boolean.parseBoolean(savedRules.getStringOr("mobGriefing", ""));
            int random=Integer.parseInt(savedRules.getStringOr("randomTickSpeed", ""));
            if(random<0)throw new IllegalStateException("negative saved randomTickSpeed");
            level.getGameRules().set(GameRules.ADVANCE_TIME, daylight,server);
            level.getGameRules().set(GameRules.SPAWN_MOBS, spawning,server);
            level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, random,server);
            level.getGameRules().set(GameRules.MOB_GRIEFING, griefing,server);
            var life=NaturalVillageLifeData.existing(server);
            if(life==null)throw new IllegalStateException("original natural registry absent; no replacement initialization");
            village=life.villages().stream().map(NaturalVillageLifeData::decode)
                    .filter(v->NaturalVillageLifeData.person(v,2).equals(PERSON)).findFirst().orElseThrow();
            NaturalVillageR1GameTests.visiting(level,village,true);
            SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
            level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_playability_fix:empty"))
                    .load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            Sagadyssey.LOGGER.info("HBED_CLOCK initialClockRestored=true source={} savedTime={} savedDay={} noAdvance=true daylightUnchanged=true boundedLoadedRegion=true",path,savedTime,savedDay);
            Sagadyssey.LOGGER.info("HBED_RULES source={} savedDaylight={} runtimeDaylight={} savedMobSpawning={} runtimeMobSpawning={} savedRandomTickSpeed={} runtimeRandomTickSpeed={} savedMobGriefing={} runtimeMobGriefing={}",path,daylight,level.getGameRules().get(GameRules.ADVANCE_TIME),spawning,level.getGameRules().get(GameRules.SPAWN_MOBS),random,level.getGameRules().get(GameRules.RANDOM_TICK_SPEED),griefing,level.getGameRules().get(GameRules.MOB_GRIEFING));
            Sagadyssey.LOGGER.info("HBED_BATCH batch=household_bed_wool isolatedFrom=playability_fix beforeBatchDay6000Override=false onlyBootMetadataRestore=true");
        }catch(Exception failure){throw new IllegalStateException("household bed continuation requires original complete stopped world",failure);}
    }

    private static void run(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();
        if(village==null){h.fail("original world was not loaded");return;}
        long began=level.getGameTime();boolean[] initialized={false},finished={false};long[] acceptedAt={-1};
        UUID[] newOrder={null},materialNeed={null},parentNeed={null};
        Map<UUID,NeedRequest> oldNeeds=new LinkedHashMap<>();
        Map<UUID,SupplyFlowData.Route> oldRoutes=new LinkedHashMap<>();
        Map<UUID,CompoundTag> oldActions=new LinkedHashMap<>();
        Map<UUID,SubjectRef> oldOwners=new LinkedHashMap<>();
        Set<RightGrant> oldGrants=new HashSet<>();Set<UUID> oldOrders=new HashSet<>();
        int[] initialWool={0},initialBeds={0},initialPlanks={0};
        var stop=server.getWorldPath(LevelResource.ROOT).resolve("household-bed-wool-stop-request.json");
        server.tickRateManager().requestGameToSprint(18200);
        h.onEachTick(()->{
            if(finished[0])return;
            try{
                long now=level.getGameTime(),elapsed=now-began;
                if(Files.exists(stop))throw new IllegalStateException("frozen wall-clock stop requested");
                if(elapsed>=17990)throw new IllegalStateException("18000 total tick limit: household bed chain not completed; do not extend or inject wool");
                var worker=NaturalVillageR1GameTests.actor(level,village,2);
                var sheep=level.getEntity(SHEEP);
                if(!initialized[0]&&(worker==null||!(sheep instanceof Sheep))){
                    if(elapsed>=200)throw new IllegalStateException("original actors did not load within 200 preparation ticks");
                    return;
                }
                h.assertTrue(worker!=null&&worker.isAlive()&&worker.getUUID().equals(BODY)&&worker.getCharacterId().equals(PERSON),"original living worker, never respawned");
                h.assertTrue(sheep instanceof Sheep original&&original.isAlive(),"original sheep retained");
                var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);
                var orders=ProductionOrderData.get(server);var rights=RightsStateData.get(server);
                var nativeData=EntityResourceActionData.get(server);
                var stock=flows.point(NaturalVillageEconomyData.storeId(village,2));
                var pantry=flows.point(NaturalVillageEconomyData.storeId(village,1));
                var privateBox=SupplyFlowService.container(server,stock);var familyBox=SupplyFlowService.container(server,pantry);
                if(!initialized[0]){
                    h.assertTrue(elapsed<=200,"initialization limit is relative to this run, not original absolute gameTime");
                    h.assertTrue(SettlementIndustryAccess.resident(worker)&&!SettlementIndustryAccess.fixture(worker,"sagadyssey.industry.native"),"normal resident admission without fixture bypass");
                    h.assertTrue(level.getGameRules().get(GameRules.ADVANCE_TIME)&&!level.getGameRules().get(GameRules.SPAWN_MOBS)
                            &&level.getGameRules().get(GameRules.RANDOM_TICK_SPEED)==3&&level.getGameRules().get(GameRules.MOB_GRIEFING),"original daylight/grass/mob rules preserved");
                    h.assertTrue(HouseholdData.get(server).forMember(PERSON).id().equals(HOME),"original household identity");
                    var site=SettlementCoordinatorData.existing(server).site(village.buildingKey());
                    h.assertTrue(site.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("BED").getStringOr("State", "").equals("DONE")
                            &&site.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("TEMP_BED").getStringOr("State", "").equals("DONE"),"old public bed already complete");
                    h.assertTrue(count(privateBox,Items.OAK_PLANKS)==0&&count(privateBox,Items.WHITE_WOOL)==1&&count(privateBox,Items.WHITE_BED)==1,
                            "sealed private inventory: no boards, one wool, one bed");
                    h.assertTrue(count(familyBox,Items.WHITE_BED)==0&&count(familyBox,Items.WHITE_WOOL)==1,"sealed household contains no bed and one separate wool");
                    h.assertTrue(privateBox.getItem(1).isEmpty(),"initial plank slot must be empty");
                    h.assertTrue(stock.owner().equals(SubjectRef.character(PERSON))&&pantry.owner().equals(SubjectRef.household(HOME)),"original property endpoints");
                    h.assertTrue(flows.known(SubjectRef.household(HOME)).contains(stock.containerId())
                            &&new RightsService(rights).can(SubjectRef.household(HOME),AssetRight.TRANSFER,stock.containerId(),0,BED,now).status()==RightsVerdict.Status.ALLOW,"existing lawful family source");
                    needs.requests().forEach(n->oldNeeds.put(n.id(),n));flows.routes().forEach(r->oldRoutes.put(r.id(),r));
                    nativeData.actions().forEach(a->oldActions.put(a.read("Id", UUIDUtil.CODEC).orElseThrow(),a));
                    rights.contexts().forEach(c->oldOwners.put(c.containerId(),c.defaultBeneficialOwner()));oldGrants.addAll(rights.grants());
                    for(var raw:MinecraftApi.listOfType(orders.save(new CompoundTag(),server.registryAccess()), "Orders",Tag.TAG_COMPOUND))oldOrders.add(((CompoundTag)raw).read("Id", UUIDUtil.CODEC).orElseThrow());
                    initialWool[0]=physical(level,Items.WHITE_WOOL);initialBeds[0]=physical(level,Items.WHITE_BED)+placedBeds(level);
                    initialPlanks[0]=physical(level,Items.OAK_PLANKS);
                    privateBox.setItem(1,new ItemStack(Items.OAK_PLANKS,3));rights.bumpContext(stock.containerId());
                    var policy=new SupplyFlowData.Policy("household-bed-wool-regression:"+HOME,SubjectRef.household(HOME),pantry.containerId(),BED,2,"household");
                    flows.policy(policy);flows.policy(policy); // Same initial business declaration, no callback or completion injection.
                    h.assertTrue(flows.policies().stream().filter(p->p.id().equals(policy.id())).count()==1,"duplicate declaration produces one policy");
                    snapshot(server,"hbed-initial.nbt");initialized[0]=true;
                    Sagadyssey.LOGGER.info("HBED_INITIAL tick={} elapsed={} day={} person={} body={} sheep={} home={} stock={} pantry={} addedPrivatePlanks=3 initialPrivateWool=1 initialPrivateBed=1 familyBedTarget=2 woolPolicy=false directBedOrder=false oldNeedIds={} oldRouteIds={} deadline=18000",
                            now,elapsed,level.getDayTime(),PERSON,BODY,SHEEP,HOME,stock.containerId(),pantry.containerId(),oldNeeds.keySet(),oldRoutes.keySet());
                    return;
                }
                for(var old:oldNeeds.values()){
                    var current=needs.request(old.id());h.assertTrue(current.key().equals(old.key())&&current.delivered()>=old.delivered(),"old need identity/delivered obligation retained "+old.id());
                }
                for(UUID id:oldOrders)h.assertTrue(orders.byId(id).isPresent(),"old manufacturing history retained "+id);
                for(int i=0;i<3;i++){var person=NaturalVillageR1GameTests.actor(level,village,i);h.assertTrue(person!=null&&person.isAlive()&&person.getCharacterId().equals(NaturalVillageLifeData.person(village,i)),"all three original people retain their living identity");}
                for(var old:oldRoutes.values())if(old.closed())h.assertTrue(flows.route(old.id()).equals(old),"old settled route retained "+old.id());
                for(var old:oldActions.entrySet())h.assertTrue(nativeData.action(old.getKey()).equals(old.getValue()),"old settled native facts retained");
                for(var owner:oldOwners.entrySet())h.assertTrue(rights.context(owner.getKey()).defaultBeneficialOwner().equals(owner.getValue()),"original container ownership retained");
                h.assertTrue(new HashSet<>(rights.grants()).equals(oldGrants),"no new permissions needed");
                h.assertTrue(nativeData.actions().stream().noneMatch(a->!oldActions.containsKey(a.read("Id", UUIDUtil.CODEC).orElseThrow())&&a.getStringOr("State", "").equals("REVIEW")),"unknown native effect is a failure");
                var familyNeeds=needs.requests().stream().filter(n->!oldNeeds.containsKey(n.id())&&n.key().requester().equals(HOME)&&n.key().item().equals(BED)).toList();
                h.assertTrue(familyNeeds.size()<=1,"one family bed request in this time window");
                if(!familyNeeds.isEmpty())parentNeed[0]=familyNeeds.getFirst().id();
                var newBedOrders=new ArrayList<ProductionOrder>();
                for(var raw:MinecraftApi.listOfType(orders.save(new CompoundTag(),server.registryAccess()), "Orders",Tag.TAG_COMPOUND)){
                    var tag=(CompoundTag)raw;UUID id=tag.read("Id", UUIDUtil.CODEC).orElseThrow();
                    if(!oldOrders.contains(id)&&tag.getCompoundOrEmpty("Output").getCompoundOrEmpty("Fingerprint").getStringOr("Item", "").equals("minecraft:white_bed"))newBedOrders.add(orders.byId(id).orElseThrow());
                }
                h.assertTrue(newBedOrders.size()<=1,"same family shortage cannot duplicate the bed order");
                if(!newBedOrders.isEmpty()){
                    var order=newBedOrders.getFirst();
                    if(newOrder[0]==null){newOrder[0]=order.id();snapshot(server,"hbed-order.nbt");Sagadyssey.LOGGER.info("HBED_ORDER tick={} parent={} order={} batch={} inputs={}",now,parentNeed[0],order.id(),order.batchId(),order.inputs());}
                    h.assertTrue(newOrder[0].equals(order.id())&&order.outputOwner().equals(SubjectRef.character(PERSON))&&order.targetContainerId().equals(stock.containerId()),"new bed has original producer and private destination");
                    h.assertTrue(order.inputs().equals(List.of(new ProductionMaterial(PLANKS,3),new ProductionMaterial(WOOL,3)))&&order.requiredWorkTicks()==400,"fixed native bed material and labor contract");
                    var material=needs.requests().stream().filter(n->n.key().purpose().equals("CHARACTER:production:"+order.id())&&n.key().item().equals(WOOL)).toList();
                    h.assertTrue(material.size()<=1,"one durable wool input obligation");
                    if(!material.isEmpty())materialNeed[0]=material.getFirst().id();
                    var suppliers=needs.requests().stream().filter(n->order.id().equals(flows.production(n.id()))).toList();
                    h.assertTrue(suppliers.size()==1&&parentNeed[0]!=null&&needs.dependencies(parentNeed[0]).contains(suppliers.getFirst().id()),"original family need links this exact supplier order");
                }
                if(elapsed%600==0)Sagadyssey.LOGGER.info("HBED_PROGRESS tick={} elapsed={} day={} parent={} order={} woolNeed={} privateBoards={} privateWool={} privateBeds={} familyBeds={} newActions={}",now,elapsed,level.getDayTime(),parentNeed[0],newOrder[0],materialNeed[0],count(privateBox,Items.OAK_PLANKS),count(privateBox,Items.WHITE_WOOL),count(privateBox,Items.WHITE_BED),count(familyBox,Items.WHITE_BED),nativeData.actions().size()-oldActions.size());
                if(newOrder[0]==null||materialNeed[0]==null||parentNeed[0]==null)return;
                var order=orders.byId(newOrder[0]).orElseThrow();
                var actualShears=nativeData.actions().stream().filter(a->!oldActions.containsKey(a.read("Id", UUIDUtil.CODEC).orElseThrow())&&a.read("Need", UUIDUtil.CODEC).orElseThrow().equals(materialNeed[0])&&a.getStringOr("State", "").equals("DONE")).toList();
                if(order.status()!=ProductionOrderStatus.COMPLETED||count(familyBox,Items.WHITE_BED)!=2||actualShears.isEmpty())return;
                if(acceptedAt[0]>=0&&now-acceptedAt[0]<300)return;
                var batch=ProductionBatchData.get(server).batch(order.batchId());
                h.assertTrue(batch!=null&&batch.status()==ProductionBatchStatus.COMPLETED&&batch.workerId().equals(PERSON)
                        &&batch.inputs().equals(order.inputs())&&batch.output().equals(order.output())&&ProductionLaborData.get(server).worked(order.id())==400,"real original material batch and 400 actual work ticks");
                int produced=nativeData.actions().stream().filter(a->!oldActions.containsKey(a.read("Id", UUIDUtil.CODEC).orElseThrow())&&a.getStringOr("State", "").equals("DONE"))
                        .flatMap(a->MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND).stream()).map(t->(CompoundTag)t).filter(t->t.getCompoundOrEmpty("Item").getStringOr("Item", "").equals("minecraft:white_wool")).mapToInt(t->t.getIntOr("Quantity", 0)).sum();
                int related=actualShears.stream().flatMap(a->MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND).stream()).map(t->(CompoundTag)t).mapToInt(t->t.getIntOr("Quantity", 0)).sum();
                h.assertTrue(related>=2&&actualShears.stream().allMatch(a->a.read("Person", UUIDUtil.CODEC).orElseThrow().equals(PERSON)&&a.read("Body", UUIDUtil.CODEC).orElseThrow().equals(BODY)
                        &&nativeData.source(a.read("Source", UUIDUtil.CODEC).orElseThrow()).read("Target", UUIDUtil.CODEC).orElseThrow().equals(SHEEP)&&a.read("DeliveryReceipt", UUIDUtil.CODEC).isPresent()),"at least two new wool from original sheep, assigned to the new bed's material need");
                int addedBeds=physical(level,Items.WHITE_BED)+placedBeds(level)-initialBeds[0];
                h.assertTrue(addedBeds==1&&initialWool[0]+produced==physical(level,Items.WHITE_WOOL)+3*addedBeds,"wool and newly manufactured beds conserved");
                h.assertTrue(physical(level,Items.OAK_PLANKS)==initialPlanks[0],"exact three added private planks consumed; old public stock not borrowed");
                var family=needs.request(parentNeed[0]);
                h.assertTrue(family.delivered()==2&&family.reserved()==0&&family.inTransit()==0,"actual two-bed delivery, no pending bed balance");
                h.assertTrue(flows.routes().stream().filter(r->r.need().equals(parentNeed[0])).allMatch(r->r.stage()==SupplyFlowData.Stage.DONE&&r.pending()==null),"bed transportation actually settled");
                // Deserialize separate copies only; never replace live ledgers, and never claim a full restart.
                var roundNeeds=NeedRequestData.load(needs.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
                var roundFlows=SupplyFlowData.load(flows.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
                var roundNative=EntityResourceActionData.load(nativeData.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
                h.assertTrue(roundNeeds.request(parentNeed[0]).equals(family)&&roundNeeds.request(materialNeed[0]).equals(needs.request(materialNeed[0]))
                        &&roundNeeds.dependencies(parentNeed[0]).equals(needs.dependencies(parentNeed[0]))&&roundFlows.routes().equals(flows.routes())
                        &&roundNative.actions().equals(nativeData.actions()),"linked NBT component roundtrip identity, not a game restart");
                if(acceptedAt[0]<0){acceptedAt[0]=now;snapshot(server,"hbed-delivered.nbt");Sagadyssey.LOGGER.info("HBED_DELIVERED tick={} elapsed={} parent={} order={} woolNeed={} relatedWool={} produced={} familyBeds=2 labor=400 nbtComponentRoundtrip=true",now,elapsed,parentNeed[0],newOrder[0],materialNeed[0],related,produced);}
                if(now-acceptedAt[0]<300)return;
                snapshot(server,"hbed-pass.nbt");finished[0]=true;
                Sagadyssey.LOGGER.info("HBED_ACCEPT tick={} elapsed={} duplicateWindow=300 family={} person={} sheep={} parent={} order={} woolNeed={} originalObligationsRetained=true runtimeReplenishment=false",now,elapsed,HOME,PERSON,SHEEP,parentNeed[0],newOrder[0],materialNeed[0]);h.succeed();
            }catch(Exception failure){
                finished[0]=true;snapshot(server,"hbed-first-failure.nbt");
                Sagadyssey.LOGGER.error("HBED_FIRST_FAILURE tick={} elapsed={} order={} woolNeed={} cause={}",level.getGameTime(),level.getGameTime()-began,newOrder[0],materialNeed[0],failure.toString());
                h.fail(failure.toString());
            }
        });
    }

    private static int count(Container c,Item item){if(c==null)return 0;int total=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))total+=c.getItem(i).getCount();return total;}
    private static int physical(ServerLevel level,Item item){
        int total=0;for(int i=0;i<4;i++)total+=count(NaturalPolityData.inventory(level.getServer(),village,i),item);
        for(int i=0;i<3;i++){var npc=NaturalVillageR1GameTests.actor(level,village,i);if(npc==null)throw new IllegalStateException("original resident missing");total+=npc.getPersonalInventory().count(s->s.is(item));total+=count(npc.getTransportInventory(),item);if(npc.getMainHandItem().is(item))total+=npc.getMainHandItem().getCount();if(npc.getOffhandItem().is(item))total+=npc.getOffhandItem().getCount();}
        for(var e:level.getEntitiesOfClass(ItemEntity.class,new AABB(village.buildingOrigin()).inflate(80)))if(e.getItem().is(item))total+=e.getItem().getCount();return total;
    }
    private static int placedBeds(ServerLevel level){int result=0;var o=village.buildingOrigin();for(var p:BlockPos.betweenClosed(o.offset(-50,-2,-50),o.offset(50,8,50))){if(!level.hasChunkAt(p))continue;var state=level.getBlockState(p);if(state.getBlock() instanceof BedBlock&&state.getValue(BedBlock.PART)==BedPart.HEAD)result++;}return result;}
    private static void snapshot(MinecraftServer server,String name){
        try{var path=server.getWorldPath(LevelResource.ROOT).resolve(name);if(Files.exists(path))return;var root=new CompoundTag();root.putLong("Time",server.overworld().getGameTime());root.putLong("DayTime",server.overworld().getDayTime());var rules=new CompoundTag();rules.putBoolean("doDaylightCycle",server.overworld().getGameRules().get(GameRules.ADVANCE_TIME));rules.putBoolean("doMobSpawning",server.overworld().getGameRules().get(GameRules.SPAWN_MOBS));rules.putInt("randomTickSpeed",server.overworld().getGameRules().get(GameRules.RANDOM_TICK_SPEED));rules.putBoolean("mobGriefing",server.overworld().getGameRules().get(GameRules.MOB_GRIEFING));root.put("GameRules",rules);root.put("Needs",NeedRequestData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Supply",SupplyFlowData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Orders",ProductionOrderData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Batches",ProductionBatchData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Labor",ProductionLaborData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Native",EntityResourceActionData.get(server).save(new CompoundTag(),server.registryAccess()));root.put("Rights",RightsStateData.get(server).save(new CompoundTag(),server.registryAccess()));NbtIo.writeCompressed(root,path);}catch(Exception failure){Sagadyssey.LOGGER.error("HBED_SNAPSHOT_FAILED file={} cause={}",name,failure.toString());}
    }
}
