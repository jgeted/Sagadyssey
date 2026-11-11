package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_development") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementGrowthCacheBindingGameTests {
    static CompoundTag restored;
    static String phase(){return System.getProperty("sagadyssey.development.phase","");}
    static boolean enabled(){return phase().matches("counter-cache-(t0[1-4]|e0[1-4]|f0[1-4])");}
    static String id(){return phase().substring("counter-cache-".length()).toUpperCase(Locale.ROOT);}
    static boolean food(){return phase().startsWith("counter-cache-f");}
    static boolean controlled(){return phase().endsWith("f03")||phase().endsWith("f04");}
    static void require(boolean v,String why){if(!v)throw new net.minecraft.gametest.framework.GameTestAssertException(net.minecraft.network.chat.Component.literal(why), -1);}
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!enabled()||!food()||!(e.getServer() instanceof GameTestServer))return;
        try{var s=e.getServer();var l=s.overworld();restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("development-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            var expected=restored.getCompoundOrEmpty("Links").copy();for(var x:MinecraftApi.listOfType(expected.getCompoundOrEmpty("Economy"), "Regions",Tag.TAG_COMPOUND)){var t=(CompoundTag)x;t.putBoolean("ObservedAway",true);t.putBoolean("ReturnRequested",false);}
            var actual=SettlementGrowthRetryContractGameTests.linked(l);write(l,"cache-before-first-tick.nbt",actual);
            require(LodContinuityP8GameTests.comparableState(expected).equals(LodContinuityP8GameTests.comparableState(actual)),"full original associated state before first tick");
            var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");require(saved.getLongOr("Time", 0L)==restored.getLongOr("SavedTime", 0L),"full original checkpoint time");
            MinecraftApi.loadRules(l, saved.getCompoundOrEmpty("game_rules"));s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
            Sagadyssey.LOGGER.info("CACHE_RESTORE case={} allOriginalLedgers=true beforeFirstTick=true savedTime={}",id(),saved.getLongOr("Time", 0L));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!enabled()||!food()||restored==null||!(e.getEntity() instanceof NpcBase n)||!(e.getLevel() instanceof ServerLevel l))return;
        var saved=MinecraftApi.listOfType(restored, "Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.read("Body", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID())).findFirst().orElseThrow();
        require(saved.read("Person", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()),"same original identity");require(saved.get("Personal").equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess())),"same personal items at load");require(saved.get("Cargo").equals(NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess())),"same original cargo at load");
        Sagadyssey.LOGGER.info("CACHE_BODY case={} originalPerson={} originalBody={} actualItemsMatched=true",id(),n.getCharacterId(),n.getUUID());
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!enabled())return List.of();Sagadyssey.LOGGER.info("CACHE_SELECTED case={} expected=1",id());return List.of(new TestFunction("cache-binding",id().toLowerCase(Locale.ROOT),"sagadyssey_development:empty",controlled()?6000:food()?18000:1800,0,true,h->new Run(h).start()));}
    static void write(ServerLevel l,String name,CompoundTag t){SettlementGrowthRetryContractGameTests.write(l,name,t);}
    static final class Run {
        final GameTestHelper h;final ServerLevel l;final String id=id();final List<NaturalVillageBuilding> villages;final CompoundTag proof=new CompoundTag();
        final SimpleContainer source=new SimpleContainer(1),d=new SimpleContainer(1),e=new SimpleContainer(1);
        CompoundTag job=new CompoundTag(),firstAttempt,ledgerBefore,rightsBefore,businessBefore;UUID sid,did,eid,txn,actual;SubjectRef owner;NpcBase c,w;long start,initialized=-1,rejected=-1;int stage,q,initialDest;boolean done;CompoundTag firstFood;
        Run(GameTestHelper h){this.h=h;l=h.getLevel();villages=food()?MinecraftApi.listOfType(restored, "Villages",Tag.TAG_COMPOUND).stream().map(x->NaturalVillageLifeData.decode((CompoundTag)x)).toList():List.of(NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V3));}
        void start(){start=l.getGameTime();var s=l.getServer();s.setDifficulty(Difficulty.NORMAL,true);l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
            if(food()){SettlementDevelopmentGameTests.releaseObservation(l,villages);SettlementDevelopmentGameTests.observeRoute(l,villages);for(var x:MinecraftApi.listOfType(restored.getCompoundOrEmpty("Metadata"), "TemporaryBarrier",Tag.TAG_COMPOUND)){var t=(CompoundTag)x;var at=BlockPos.of(t.getLongOr("Position", 0L));require(l.getBlockState(at).is(Blocks.GLASS),"only declared test glass removed");l.setBlock(at,NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompoundOrEmpty("Before")),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}}
            l.setDayTime(6000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,s);s.tickRateManager().requestGameToSprint(controlled()?6000:food()?18000:1800);
            h.onEachTick(()->{if(done)return;try{tick();}catch(GameTestAssertException x){fail(x.getMessage());throw x;}catch(Exception x){fail("HARNESS_ERROR: "+x);h.fail("HARNESS_ERROR: "+x);}});
        }
        boolean ready(){var data=NaturalVillageLifeData.existing(l.getServer());return data!=null&&villages.stream().allMatch(b->{var r=data.village(b.buildingKey());return r!=null&&r.getBooleanOr("Ready", false)&&NaturalVillageR1GameTests.actor(l,b,0)!=null&&NaturalVillageR1GameTests.actor(l,b,1)!=null&&NaturalVillageR1GameTests.actor(l,b,2)!=null;});}
        CompoundTag ledger(){return TransferLedgerData.get(l.getServer()).save(new CompoundTag(),l.registryAccess());}
        CompoundTag rights(){return RightsStateData.get(l.getServer()).save(new CompoundTag(),l.registryAccess());}
        int count(Container box){return SettlementDevelopmentGameTests.count(box,Items.CARROT);}
        void init(){var s=l.getServer();var b=villages.getFirst();c=NaturalVillageR1GameTests.actor(l,b,1);w=NaturalVillageR1GameTests.actor(l,b,2);initialized=l.getGameTime();
            if(id.equals("F03")||id.equals("F04")){
                var raw=NaturalPolityData.inventory(s,b,0);var rights=RightsStateData.get(s);var rawId=NaturalVillageEconomyData.storeId(b,0);var context=rights.context(rawId);var farmer=NaturalVillageR1GameTests.actor(l,b,0);
                require(raw.getItem(26).isEmpty(),"new controlled initial input uses an empty slot only");require(context!=null&&context.defaultBeneficialOwner().equals(SubjectRef.character(farmer.getCharacterId())),"original RAW remains original farmer private property");
                var original=NaturalVillageEconomyData.items(raw,l.registryAccess());raw.setItem(26,new ItemStack(Items.CARROT,29));rights.bumpContext(rawId);
                proof.put("OriginalRawBeforeControlledInitialInput",original);proof.putInt("DeclaredFiniteInitialCarrots",29);proof.putString("InitialInputOwner",farmer.getCharacterId().toString());proof.putString("InitialInputProvenance","controlled fixture external initial stock; not harvest, not post-start resupply");
            }
            if(!food()){
                var rights=RightsStateData.get(s);for(int i=0;i<NaturalVillageLayout.stores(b).size();i++){var inv=NaturalPolityData.inventory(s,b,i);if(inv!=null)clear(inv,NaturalVillageEconomyData.storeId(b,i),rights);}
                for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);n.setCommand(NpcCommand.STAY);n.setNoAi(true);n.setHealth(20);clear(n.getPersonalInventory(),ContainerIds.npcPersonal(n.getCharacterId()),rights);n.getTransportInventory().clearContent();}
                NaturalPolityData.inventory(s,b,1).setItem(0,new ItemStack(Items.CARROT,64));var farmer=NaturalVillageR1GameTests.actor(l,b,0);farmer.getPersonalInventory().setItem(0,new ItemStack(Items.CARROT,12));farmer.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_HOE));
                sid=ContainerIds.named("cache:"+id+":S");did=ContainerIds.named("cache:"+id+":D");eid=ContainerIds.named("cache:"+id+":E");txn=ContainerIds.named("cache:"+id+":pick:0");owner=SubjectRef.household(NaturalVillageLifeData.home(b));source.setItem(0,new ItemStack(Items.CARROT,30));
                rights.ensureContext(sid,"cache:"+id+":S",SubjectRef.character(farmer.getCharacterId()),SubjectRef.character(farmer.getCharacterId()),AccessPolicy.PRIVATE);rights.ensureContext(did,"cache:"+id+":D",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(eid,"cache:"+id+":E",owner,owner,AccessPolicy.PRIVATE);
                for(var who:List.of(owner,SubjectRef.character(c.getCharacterId()),SubjectRef.character(w.getCharacterId())))rights.grant(new RightGrant(ContainerIds.named("cache-grant:"+id+":"+who),sid,who,Set.of(AssetRight.TRANSFER),"finite component fixture",0,null,true));
                job.putString("Id","cache:"+id);job.putString("State","CLAIMED");job.store("Person", UUIDUtil.CODEC,c.getCharacterId());job.store("Body", UUIDUtil.CODEC,c.getUUID());job.store("Plan", UUIDUtil.CODEC,ContainerIds.named("cache:"+id+":plan"));job.putInt("Cycle",0);job.putInt("Quantity",0);
            }
            proof.put("Initial",snapshot());event("INITIAL_FROZEN");
        }
        void clear(Container inv,UUID cid,RightsStateData rights){for(int i=0;i<inv.getContainerSize();i++)rights.removeClaimsForSlot(cid,i);inv.clearContent();}
        boolean transfer(NpcBase n,CompoundTag r,UUID tx,SubjectRef actor,UUID from,Container src,UUID to,Container dst,int quantity){return SettlementGrowthData.get(l.getServer()).transfer(n,r,tx,actor,TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(Items.CARROT)),quantity)),Map.of(from,src,to,dst));}
        CompoundTag snapshot(){var t=SettlementGrowthRetryContractGameTests.snapshot(l,villages);t.put("ComponentJob",job.copy());t.put("ComponentSource",NaturalVillageEconomyData.items(source,l.registryAccess()));t.put("ComponentD",NaturalVillageEconomyData.items(d,l.registryAccess()));t.put("ComponentE",NaturalVillageEconomyData.items(e,l.registryAccess()));return t;}
        void verifyOriginalMetadata(){require(job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).equals(firstAttempt),"original cached Attempt/actualId/Success retained");require(job.read("LastTransfer", UUIDUtil.CODEC).isPresent()&&job.read("LastTransfer", UUIDUtil.CODEC).orElseThrow().equals(actual),"original LastTransfer retained");require(job.getIntOr("Cycle", 0)==0&&job.getIntOr("Quantity", 0)==0&&!job.getBooleanOr("Cargo", false),"refusal cannot report component business completion");}
        void tick() throws Exception {
            long now=l.getGameTime();if(!ready()){if(now-start>=1600)finish("UNSUPPORTED","initialization limit1600 within total");return;}if(initialized<0){init();return;}if(food()){foodTick(now);return;}
            UUID bag=ContainerIds.npcPersonal(c.getCharacterId());
            if(stage==0){require(transfer(c,job,txn,owner,sid,source,bag,c.getPersonalInventory(),3),"first original3 must succeed");actual=job.read("LastTransfer", UUIDUtil.CODEC).orElseThrow();firstAttempt=job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).copy();proof.put("FirstSuccess",snapshot());stage=1;event("FIRST_SUCCESS");return;}
            if(id.equals("T01")){
                if(stage==1){var before=ledger();require(transfer(c,job,txn,owner,sid,source,bag,c.getPersonalInventory(),3),"correct original replay after commit versions changed");require(before.equals(ledger()),"cached confirmation never submits another service request");stage=2;event("REPLAY_AFTER_COMMIT");return;}
                if(stage==2){var aux=new CompoundTag();require(transfer(c,aux,ContainerIds.named("cache:T01:later-real-gift"),SubjectRef.character(c.getCharacterId()),bag,c.getPersonalInventory(),eid,e,1),"later legitimate original1 moves, not restocked");proof.put("LaterRealTransfer",aux);stage=3;return;}
                write(l,"cache-job-roundtrip.nbt",job);write(l,"cache-ledger-roundtrip.nbt",ledger());job=NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("cache-job-roundtrip.nbt"),NbtAccounter.unlimitedHeap());var tag=NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("cache-ledger-roundtrip.nbt"),NbtAccounter.unlimitedHeap());l.getDataStorage().set(TransferLedgerData.FACTORY.type(TransferLedgerData.DATA_NAME),TransferLedgerData.FACTORY.deserializer().apply(tag,l.registryAccess()));
                var before=ledger();var title=rights();require(transfer(c,job,txn,owner,sid,source,bag,c.getPersonalInventory(),3),"NBT original replay after unrelated current revision change");verifyOriginalMetadata();require(count(source)==27&&count(c.getPersonalInventory())==2&&count(e)==1,"no re-credit after goods already moved lawfully");require(before.equals(ledger())&&title.equals(rights()),"replay changes no ledger/title");finish("PASS","original request replay, later real move and real NBT roundtrip; not process restart");return;
            }
            if(stage==1){
                if(id.equals("E01")||id.equals("E02")){
                    var tag=ledger();var rows=MinecraftApi.listOfType(tag, "Receipts",Tag.TAG_COMPOUND);for(int i=rows.size()-1;i>=0;i--)if(rows.getCompoundOrEmpty(i).read("Id", UUIDUtil.CODEC).orElseThrow().equals(actual)){if(id.equals("E01"))rows.remove(i);else rows.getCompoundOrEmpty(i).remove("RequestIdentity");}
                    l.getDataStorage().set(TransferLedgerData.FACTORY.type(TransferLedgerData.DATA_NAME),TransferLedgerData.FACTORY.deserializer().apply(tag,l.registryAccess()));proof.putString("EvidenceInput",id.equals("E01")?"component target receipt absent":"component legacy target identity absent");
                }
                if(id.equals("E03")){var other=new CompoundTag();UUID otherTxn=ContainerIds.named("cache:E03:other-child");require(transfer(c,other,otherTxn,owner,sid,source,bag,c.getPersonalInventory(),3),"second distinct real success before transplant input");job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).store("Id", UUIDUtil.CODEC,other.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());firstAttempt=job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).copy();proof.put("OtherActualSuccess",other);}
                ledgerBefore=ledger();rightsBefore=rights();businessBefore=job.copy();var n=id.equals("T03")||id.equals("E04")?w:c;UUID to=id.equals("T04")?eid:id.equals("T03")?ContainerIds.npcPersonal(w.getCharacterId()):bag;Container inv=id.equals("T04")?e:id.equals("T03")?w.getPersonalInventory():c.getPersonalInventory();
                boolean result=transfer(n,job,txn,owner,sid,source,to,inv,id.equals("T02")?4:3);proof.putBoolean("RejectedReturnValue",result);require(!result&&job.getBooleanOr("Review", false),"conflict/missing evidence must return false and enter Review");verifyOriginalMetadata();require(ledgerBefore.equals(ledger())&&rightsBefore.equals(rights()),"rejection must not submit/update title");proof.put("AfterReject",snapshot());rejected=now;stage=2;event("REJECTED_INTO_REVIEW");return;
            }
            require(job.getBooleanOr("Review", false),"Review cannot disappear during later scheduler attempts");require(!transfer(c,job,txn,owner,sid,source,bag,c.getPersonalInventory(),3),"later original request cannot silently unreview");require(!transfer(c,job,ContainerIds.named("cache:"+id+":would-be-replacement"),owner,sid,source,bag,c.getPersonalInventory(),1),"no replacement child submission while Review");verifyOriginalMetadata();require(ledgerBefore.equals(ledger())&&rightsBefore.equals(rights()),"later calls cannot add actualId/title/Attempt");
            require(count(source)==(id.equals("E03")?24:27)&&count(c.getPersonalInventory())==(id.equals("E03")?6:3)&&count(w.getPersonalInventory())==0&&count(e)==0,"original actual goods retained");
            if(now-rejected>=200)finish("PASS_SAFETY_ONLY","false+Review preserves goods/title/original metadata and blocks subsequent requests; not automatic recovery");
        }
        void foodTick(long now){var s=l.getServer();var data=SettlementDevelopmentData.get(s);var row=data.site(villages.getFirst().buildingKey());var p=SettlementGrowthRetryContractGameTests.firstProject(row);var f=SettlementGrowthRetryContractGameTests.food(row);
            for(var b:villages)for(int i=0;i<3;i++)require(CharacterRegistryData.get(s).byId(NaturalVillageLifeData.person(b,i)).alive(),"original9 alive and not substituted");
            if(firstFood==null){
                if(f.getBooleanOr("Cargo", false)&&f.getIntOr("Cycle", 0)==0&&f.read("LastTransfer", UUIDUtil.CODEC).isPresent()){
                    actual=f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow();var receipt=TransferLedgerData.get(s).receipt(actual);require(receipt!=null&&receipt.successful()&&receipt.lawful(),"real original normal pickup0 receipt");q=f.getIntOr("Quantity", 0);require(q>0&&count(c.getPersonalInventory())==q,"actual positive food physically in original courier bag");firstFood=f.copy();var dest=(Container)l.getBlockEntity(SettlementDevelopmentLayout.pantry(villages.getFirst(),p.getIntOr("Lot", 0)));require(dest!=null,"real future household pantry");initialDest=count(dest);proof.put("NormalPickup",snapshot());proof.putInt("Quantity",q);event("NORMAL_PICK0");
                    if(id.equals("F01")||id.equals("F03")){
                        var live=MinecraftApi.listOfType(SettlementGrowthRetryContractGameTests.actualRow(data,villages.getFirst().buildingKey()), "Projects",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getCompoundOrEmpty("Jobs").getCompoundOrEmpty("FOOD");
                        var from=NaturalVillageEconomyData.storeId(villages.getFirst(),0);var record=data.application(p.read("Household", UUIDUtil.CODEC).orElseThrow());var before=ledger();var title=rights();
                        require(transfer(c,live,ContainerIds.named(live.getStringOr("Id", "")+":pick:0"),SubjectRef.household(record.read("Household", UUIDUtil.CODEC).orElseThrow()),from,NaturalPolityData.inventory(s,villages.getFirst(),0),ContainerIds.npcPersonal(c.getCharacterId()),c.getPersonalInventory(),q),"real FOOD exact original cached request confirmed after commit");
                        require(before.equals(ledger())&&title.equals(rights())&&live.getBooleanOr("Cargo", false)&&live.getIntOr("Quantity", 0)==q&&live.getIntOr("Cycle", 0)==0,"correct cached FOOD confirmation changes no goods, title or business fields");proof.put("CorrectFoodCachedReplay",snapshot());
                    }
                    if(id.equals("F02")||id.equals("F04")){
                        var live=MinecraftApi.listOfType(SettlementGrowthRetryContractGameTests.actualRow(data,villages.getFirst().buildingKey()), "Projects",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getCompoundOrEmpty("Jobs").getCompoundOrEmpty("FOOD");
                        var from=NaturalVillageEconomyData.storeId(villages.getFirst(),0);var record=data.application(p.read("Household", UUIDUtil.CODEC).orElseThrow());var before=ledger();var title=rights();txn=ContainerIds.named(live.getStringOr("Id", "")+":pick:0");
                        boolean returned=transfer(c,live,txn,SubjectRef.household(record.read("Household", UUIDUtil.CODEC).orElseThrow()),from,NaturalPolityData.inventory(s,villages.getFirst(),0),ContainerIds.npcPersonal(c.getCharacterId()),c.getPersonalInventory(),q+1);
                        require(!returned&&live.getBooleanOr("Review", false),"live FOOD rejects artificial changed quantity instead of cached success");require(live.getBooleanOr("Cargo", false)&&live.getIntOr("Quantity", 0)==q&&live.getIntOr("Cycle", 0)==0,"no Cargo/Cycle rollback or forged completion");require(before.equals(ledger())&&title.equals(rights()),"live rejected call moves no goods/title");data.setDirty();proof.put("AfterChangedRequest",snapshot());rejected=now;event("LIVE_FOOD_REVIEW");
                    }return;
                }
                if(now-start>=(controlled()?4000:12000))finish("UNSUPPORTED","normal pickup0 not reached by frozen preparation, no free resupply");return;
            }
            if(id.equals("F01")||id.equals("F03")){
                if(f.getIntOr("Cycle", 0)==1&&!f.getBooleanOr("Cargo", false)){
                    var receipt=TransferLedgerData.get(s).receipt(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());require(receipt!=null&&receipt.successful()&&receipt.lawful(),"normal actual drop0 receipt");var dest=(Container)l.getBlockEntity(SettlementDevelopmentLayout.pantry(villages.getFirst(),p.getIntOr("Lot", 0)));require(count(dest)==initialDest+q,"same food actually arrived at original future household");require(count(c.getPersonalInventory())==0,"original bag actually debited");proof.put("NormalDrop",snapshot());finish("PASS","normal FOOD pick0/drop0 actual transfer, Cycle1 and rightful destination");return;}
            }else{
                require(f.getBooleanOr("Review", false)&&f.getBooleanOr("Cargo", false)&&f.getIntOr("Cycle", 0)==0&&f.getIntOr("Quantity", 0)==q,"normal scheduler cannot convert refused request into business success");require(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow().equals(actual)&&f.getCompoundOrEmpty("Transfers").equals(firstFood.getCompoundOrEmpty("Transfers")),"original attempt and actualId remain; no replacement");require(count(c.getPersonalInventory())==q,"original carried food retained pending Review");
                if(now-rejected>=400){proof.put("NormalSchedulerAfterReview",snapshot());finish("PASS_SAFETY_ONLY","actual FOOD remains Review with original cargo/duty through400 normal ticks; not automatic recovery");return;}
            }
            if(now-start>=(controlled()?5998:17998))finish("UNSUPPORTED","frozen total exhausted without changing criteria");
        }
        void event(String e){Sagadyssey.LOGGER.info("CACHE_EVENT case={} event={} tick={} elapsed={}",id,e,l.getGameTime(),l.getGameTime()-start);}
        void fail(String why){if(done)return;done=true;proof.putString("Case",id);proof.putString("Result","FAIL");proof.putString("Reason",why);proof.putLong("Elapsed",l.getGameTime()-start);proof.put("Final",snapshot());write(l,"cache-"+id+"-proof.nbt",proof);Sagadyssey.LOGGER.error("CACHE_RESULT case={} result=FAIL elapsed={} why={}",id,l.getGameTime()-start,why);}
        void finish(String result,String why){if(done)return;done=true;proof.putString("Case",id);proof.putString("Result",result);proof.putString("Reason",why);proof.putLong("Elapsed",l.getGameTime()-start);proof.put("Final",snapshot());write(l,"cache-"+id+"-proof.nbt",proof);Sagadyssey.LOGGER.info("CACHE_RESULT case={} result={} elapsed={} why={}",id,result,l.getGameTime()-start,why);h.succeed();}
    }
}
