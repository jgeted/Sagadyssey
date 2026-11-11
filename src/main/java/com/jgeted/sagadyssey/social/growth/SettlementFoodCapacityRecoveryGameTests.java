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
public final class SettlementFoodCapacityRecoveryGameTests {
    static CompoundTag restored;
    static String phase(){return System.getProperty("sagadyssey.development.phase","");}
    static boolean enabled(){return phase().matches("counter-foodcap-n0[1-7]");}
    static String id(){return phase().substring("counter-foodcap-".length()).toUpperCase(Locale.ROOT);}
    static boolean food(){return !phase().endsWith("n05")&&!phase().endsWith("n06");}
    static boolean controlled(){return true;}
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
            Sagadyssey.LOGGER.info("FOODCAP_RESTORE case={} allOriginalLedgers=true beforeFirstTick=true savedTime={}",id(),saved.getLongOr("Time", 0L));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!enabled()||!food()||restored==null||!(e.getEntity() instanceof NpcBase n)||!(e.getLevel() instanceof ServerLevel l))return;
        var saved=MinecraftApi.listOfType(restored, "Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.read("Body", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID())).findFirst().orElseThrow();
        require(saved.read("Person", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()),"same original identity");require(saved.get("Personal").equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess())),"same personal items at load");require(saved.get("Cargo").equals(NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess())),"same original cargo at load");
        Sagadyssey.LOGGER.info("FOODCAP_BODY case={} originalPerson={} originalBody={} actualItemsMatched=true",id(),n.getCharacterId(),n.getUUID());
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!enabled())return List.of();Sagadyssey.LOGGER.info("FOODCAP_SELECTED case={} expected=1",id());return List.of(new TestFunction("food-capacity",id().toLowerCase(Locale.ROOT),"sagadyssey_development:empty",8000,0,true,h->new Run(h).start()));}
    static void write(ServerLevel l,String name,CompoundTag t){SettlementGrowthRetryContractGameTests.write(l,name,t);}

    static final class Run {
        final GameTestHelper h;final ServerLevel l;final String id=id();final List<NaturalVillageBuilding> villages;final CompoundTag proof=new CompoundTag();final ListTag timeline=new ListTag();
        final SimpleContainer source=new SimpleContainer(1),dest=new SimpleContainer(1),other=new SimpleContainer(1);
        NpcBase c;CompoundTag job=new CompoundTag(),first;UUID txn,failed;SubjectRef actor;UUID sid,did;long start,initialized=-1,rejected=-1,restoredAt=-1,picked=-1;int q,initialPantry;boolean done,freed;int stage;
        Run(GameTestHelper h){this.h=h;l=h.getLevel();villages=food()?MinecraftApi.listOfType(restored, "Villages",Tag.TAG_COMPOUND).stream().map(x->NaturalVillageLifeData.decode((CompoundTag)x)).toList():List.of(NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V3));}
        void start(){start=l.getGameTime();var s=l.getServer();s.setDifficulty(Difficulty.NORMAL,true);l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
            if(food()){SettlementDevelopmentGameTests.releaseObservation(l,villages);SettlementDevelopmentGameTests.observeRoute(l,villages);for(var x:MinecraftApi.listOfType(restored.getCompoundOrEmpty("Metadata"), "TemporaryBarrier",Tag.TAG_COMPOUND)){var t=(CompoundTag)x;var at=BlockPos.of(t.getLongOr("Position", 0L));require(l.getBlockState(at).is(Blocks.GLASS),"declared test barrier only");l.setBlock(at,NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompoundOrEmpty("Before")),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}}
            l.setDayTime(6000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,s);s.tickRateManager().requestGameToSprint(8000);
            h.onEachTick(()->{if(done)return;try{tick();}catch(GameTestAssertException x){finish("FAIL",x.getMessage(),false);throw x;}catch(Exception x){finish("FAIL","HARNESS_ERROR "+x,false);h.fail("HARNESS_ERROR "+x);}});
        }
        boolean ready(){var d=NaturalVillageLifeData.existing(l.getServer());return d!=null&&villages.stream().allMatch(b->{var r=d.village(b.buildingKey());return r!=null&&r.getBooleanOr("Ready", false)&&NaturalVillageR1GameTests.actor(l,b,0)!=null&&NaturalVillageR1GameTests.actor(l,b,1)!=null&&NaturalVillageR1GameTests.actor(l,b,2)!=null;});}
        int count(Container box,Item item){return box==null?0:SettlementDevelopmentGameTests.count(box,item);}
        boolean move(CompoundTag r,String key,SubjectRef who,UUID from,Container src,UUID to,Container dst,Item item,int amount){return SettlementGrowthData.get(l.getServer()).transfer(c,r,ContainerIds.named(key),who,TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),amount)),Map.of(from,src,to,dst));}
        CompoundTag liveFood(){var d=SettlementDevelopmentData.get(l.getServer());return MinecraftApi.listOfType(SettlementGrowthRetryContractGameTests.actualRow(d,villages.getFirst().buildingKey()), "Projects",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getCompoundOrEmpty("Jobs").getCompoundOrEmpty("FOOD");}
        CompoundTag project(){return SettlementGrowthRetryContractGameTests.firstProject(SettlementDevelopmentData.get(l.getServer()).site(villages.getFirst().buildingKey()));}
        Container pantry(){return (Container)l.getBlockEntity(SettlementDevelopmentLayout.pantry(villages.getFirst(),project().getIntOr("Lot", 0)));}
        CompoundTag snapshot(){var t=SettlementGrowthRetryContractGameTests.snapshot(l,villages);t.put("ComponentJob",job.copy());t.put("ComponentSource",NaturalVillageEconomyData.items(source,l.registryAccess()));t.put("ComponentDestination",NaturalVillageEconomyData.items(dest,l.registryAccess()));t.put("ComponentOther",NaturalVillageEconomyData.items(other,l.registryAccess()));
            if(food()&&initialized>=0&&pantry()!=null){t.put("FuturePantryItems",NaturalVillageEconomyData.items(pantry(),l.registryAccess()));t.store("FuturePantryHousehold", UUIDUtil.CODEC,project().read("Household", UUIDUtil.CODEC).orElseThrow());}return t;}
        void event(String e){var t=new CompoundTag();t.putString("Event",e);t.putLong("Tick",l.getGameTime());t.putLong("Elapsed",l.getGameTime()-start);if(c!=null){t.store("Person", UUIDUtil.CODEC,c.getCharacterId());t.store("Body", UUIDUtil.CODEC,c.getUUID());t.putString("Command",c.getCommand().name());var schedule=com.jgeted.sagadyssey.social.life.NpcLifeData.get(l.getServer()).schedule(c.getCharacterId());if(schedule!=null)t.put("ScheduleReadOnly",schedule.save());}timeline.add(t);Sagadyssey.LOGGER.info("FOODCAP_EVENT case={} event={} tick={} elapsed={}",id,e,l.getGameTime(),l.getGameTime()-start);}
        void init(){var b=villages.getFirst();var s=l.getServer();c=NaturalVillageR1GameTests.actor(l,b,1);var rights=RightsStateData.get(s);initialized=l.getGameTime();
            if(food()){
                var raw=NaturalPolityData.inventory(s,b,0);var rawId=NaturalVillageEconomyData.storeId(b,0);var farmer=NaturalVillageR1GameTests.actor(l,b,0);require(rights.context(rawId).defaultBeneficialOwner().equals(SubjectRef.character(farmer.getCharacterId())),"RAW remains original private farmer owner");require(raw.getItem(26).isEmpty(),"empty declared initial slot");proof.put("OriginalRaw",NaturalVillageEconomyData.items(raw,l.registryAccess()));int added=id.equals("N07")?24:29;raw.setItem(26,new ItemStack(Items.CARROT,added));rights.bumpContext(rawId);proof.putInt("ExternalInitialCarrots",added);
                require(count(c.getPersonalInventory(),Items.CARROT)==0&&count(c.getPersonalInventory(),Items.COBBLESTONE)==0,"original courier initial bag not replaced");for(int i=0;i<c.getPersonalInventory().getContainerSize();i++)require(c.getPersonalInventory().getItem(i).isEmpty(),"only empty original bag may prepare capacity input");
                var pub=NaturalPolityData.inventory(s,b,3);var pubId=NaturalVillageEconomyData.storeId(b,3);UUID bag=ContainerIds.npcPersonal(c.getCharacterId());actor=SubjectRef.character(c.getCharacterId());var aux=new CompoundTag();require(move(aux,"foodcap:"+id+":initial-stones",SubjectRef.institution(b.settlementId()),pubId,pub,bag,c.getPersonalInventory(),Items.COBBLESTONE,36),"formal initial public36 gift");proof.put("InitialStoneTransfer",aux);
                for(int i=0;i<c.getPersonalInventory().getContainerSize();i++){rights.removeClaimsForSlot(bag,i);c.getPersonalInventory().setItem(i,new ItemStack(Items.COBBLESTONE));}rights.bumpContext(bag);require(c.getPersonalInventory().getContainerSize()==36,"declared36 original capacity slots");initialPantry=count(pantry(),Items.CARROT);
                proof.putString("CapacitySetup","original public36 formally gifted then split1 per empty bag slot during initial setup; public ownership changes by lawful GIFT; no generated stone");
            }else{
                for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);n.setNoAi(true);n.setCommand(NpcCommand.STAY);}
                var farmer=NaturalVillageR1GameTests.actor(l,b,0);sid=ContainerIds.named("foodcap:"+id+":source");did=ContainerIds.named("foodcap:"+id+":dest");actor=SubjectRef.household(NaturalVillageLifeData.home(b));source.setItem(0,new ItemStack(Items.CARROT,30));if(id.equals("N05"))dest.setItem(0,new ItemStack(Items.COBBLESTONE));
                rights.ensureContext(sid,"foodcapSource",SubjectRef.character(farmer.getCharacterId()),SubjectRef.character(farmer.getCharacterId()),AccessPolicy.PRIVATE);rights.ensureContext(did,"foodcapDest",actor,actor,AccessPolicy.PRIVATE);
                if(id.equals("N05"))grant();job.putString("Id","foodcap:"+id);if(id.equals("N06"))job.putString("Kind","FOOD");job.store("Person", UUIDUtil.CODEC,c.getCharacterId());job.store("Body", UUIDUtil.CODEC,c.getUUID());txn=ContainerIds.named("foodcap:"+id+":pick:0");
            }
            proof.put("Initial",snapshot());event("INITIAL_FROZEN");
        }
        void grant(){RightsStateData.get(l.getServer()).grant(new RightGrant(ContainerIds.named("foodcap:"+id+":grant"),sid,actor,Set.of(AssetRight.TRANSFER),"declared lawful restoration",l.getGameTime(),null,true));}
        void release(){var b=villages.getFirst();var rights=RightsStateData.get(l.getServer());UUID bag=ContainerIds.npcPersonal(c.getCharacterId());var aux=new CompoundTag();require(count(c.getPersonalInventory(),Items.COBBLESTONE)==36,"same original36 remain before lawful return");require(move(aux,"foodcap:"+id+":return-stones",actor,bag,c.getPersonalInventory(),NaturalVillageEconomyData.storeId(b,3),NaturalPolityData.inventory(l.getServer(),b,3),Items.COBBLESTONE,36),"same36 returned through real GIFT");proof.put("ReturnStoneTransfer",aux);require(count(c.getPersonalInventory(),Items.COBBLESTONE)==0,"capacity physically restored");
            if(id.equals("N07")){var farmer=NaturalVillageR1GameTests.actor(l,b,0);var one=new CompoundTag();require(move(one,"foodcap:N07:original-one-carrot",SubjectRef.character(farmer.getCharacterId()),ContainerIds.npcPersonal(farmer.getCharacterId()),farmer.getPersonalInventory(),NaturalVillageEconomyData.storeId(b,0),NaturalPolityData.inventory(l.getServer(),b,0),Items.CARROT,1),"one original private carrot lawfully moved, no resupply");proof.put("OriginalCarrotMove",one);require(count(farmer.getPersonalInventory(),Items.CARROT)>=12,"seed protection retained");}
            if(id.equals("N03"))c.setCommand(NpcCommand.STAY);
            if(id.equals("N04")){var ld=TransferLedgerData.get(l.getServer());var t=ld.save(new CompoundTag(),l.registryAccess());var rows=MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND);boolean removed=false;for(int i=rows.size()-1;i>=0;i--)if(rows.getCompoundOrEmpty(i).read("Id", UUIDUtil.CODEC).orElseThrow().equals(failed)){rows.remove(i);removed=true;}require(removed,"only existing target failed receipt removed");l.getDataStorage().set(TransferLedgerData.FACTORY.type(TransferLedgerData.DATA_NAME),TransferLedgerData.FACTORY.deserializer().apply(t,l.registryAccess()));proof.putString("FaultInjection","remove sole confirmed failed receipt in independent negative test copy; not naturally occurring fault");}
            freed=true;if(!id.equals("N03"))restoredAt=l.getGameTime();proof.put("CapacityRestored",snapshot());event("CAPACITY_RESTORED");
        }
        void tick(){long now=l.getGameTime();if(!ready()){if(now-start>=1600)finish("UNSUPPORTED","initialization1600 within total",true);return;}if(initialized<0){init();return;}if(!food()){component(now);return;}
            var f=liveFood();for(var b:villages)for(int i=0;i<3;i++)require(CharacterRegistryData.get(l.getServer()).byId(NaturalVillageLifeData.person(b,i)).alive(),"same9 original residents alive");
            if(rejected<0){
                if(f.read("LastTransfer", UUIDUtil.CODEC).isPresent()){var receipt=TransferLedgerData.get(l.getServer()).receipt(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());if(receipt!=null&&receipt.status()==TransferReceipt.Status.CAPACITY_EXCEEDED){require(!receipt.successful()&&receipt.lawful()&&receipt.finalRevisions().isEmpty()&&!f.getBooleanOr("Cargo", false),"authoritative no-effect actual capacity rejection");failed=receipt.transferId();rejected=now;txn=ContainerIds.named(f.getStringOr("Id", "")+":pick:0");first=f.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).copy();q=MinecraftApi.listOfType(TransferLedgerData.get(l.getServer()).requestIdentity(failed), "Legs",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getIntOr("Quantity", 0);require(q==(id.equals("N07")?8:9),"frozen initial failed amount");proof.putInt("FailedQuantity",q);proof.store("FailedActualId", UUIDUtil.CODEC,failed);proof.putLong("FailedAt",rejected);proof.put("FirstRejection",snapshot());event("FIRST_NO_EFFECT_CAPACITY_REJECTION");return;}}
                require(!f.getBooleanOr("Cargo", false),"target failure path must precede pickup");if(now-initialized>=2400)finish("UNSUPPORTED","no target capacity rejection within frozen preparation",true);return;
            }
            if(!freed){require(f.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).equals(first),"full bag cannot resubmit even after generic1200 boundary");if(now>=rejected+(id.equals("N02")?1601:1))release();return;}
            if(id.equals("N03")&&restoredAt<0){require(!f.getBooleanOr("Cargo", false),"explicit STAY prevents work");if(now>=rejected+401){c.setCommand(NpcCommand.WORK);restoredAt=now;event("WORK_RESTORED");}return;}
            if(id.equals("N04")){require(!f.getBooleanOr("Cargo", false)&&f.getIntOr("Cycle", 0)==0,"missing proof cannot create cargo or complete cycle");require(f.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).equals(first),"no blind new id after evidence loss");if(now-restoredAt>=400){require(f.getBooleanOr("Review", false),"missing authoritative evidence enters existing Review");finish("PASS_SAFETY_ONLY","Review preserves original attempt and material; not automatic recovery",true);}return;}
            if(picked<0){
                if(f.getBooleanOr("Cargo", false)){picked=now;q=f.getIntOr("Quantity", 0);var rec=TransferLedgerData.get(l.getServer()).receipt(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());require(rec!=null&&rec.successful()&&rec.lawful()&&q==9&&count(c.getPersonalInventory(),Items.CARROT)==9,"normal original pickup physical9 lawful receipt");require(now-restoredAt<=600,"actual pickup exceeds conservative600 elapsed/qualified upper bound");require(f.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).getIntOr("Attempt", 0)==2,"exactly one new attempt after proven no-effect failure");require(TransferLedgerData.get(l.getServer()).receipt(failed)!=null,"original failed receipt retained");proof.putLong("RestoredAt",restoredAt);proof.putLong("PickedAt",picked);proof.putLong("ConservativeQualifiedUpperBound",picked-restoredAt);proof.put("ActualPickup",snapshot());event("NORMAL_ACTUAL_PICKUP");return;}
                if(now-restoredAt>600){proof.put("First601State",snapshot());require(false,"no real pickup within600: includes all elapsed ticks, no backoff/poll deductions; diagnose external readiness separately");}return;
            }
            if(f.getIntOr("Cycle", 0)==1&&!f.getBooleanOr("Cargo", false)){require(now-picked<=2400,"separate delivery2400 deadline");var rec=TransferLedgerData.get(l.getServer()).receipt(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());require(rec!=null&&rec.successful()&&rec.lawful(),"normal drop actual receipt");require(count(pantry(),Items.CARROT)==initialPantry+q&&count(c.getPersonalInventory(),Items.CARROT)==0,"same9 physically arrived and bag debited");var identity=TransferLedgerData.get(l.getServer()).requestIdentity(f.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());proof.put("DropRequestIdentity",identity);proof.putLong("DeliveryElapsed",now-picked);proof.put("ActualDelivery",snapshot());event("NORMAL_ACTUAL_DELIVERY");finish("PASS","original FOOD capacity recovery: actual9 pick within600, actual9 delivery within separate2400; original actors and ledgers",true);return;}
            require(now-picked<=2400,"normal delivery did not finish frozen2400");
        }
        void component(long now){
            if(stage==0){boolean ok=SettlementGrowthData.get(l.getServer()).transfer(c,job,txn,actor,TransferIntent.GIFT,List.of(new TransferLeg(sid,did,ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),Map.of(sid,source,did,dest));require(!ok,"frozen component failure");failed=job.read("LastTransfer", UUIDUtil.CODEC).orElseThrow();first=job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).copy();var rec=TransferLedgerData.get(l.getServer()).receipt(failed);require(rec.status()==(id.equals("N05")?TransferReceipt.Status.CAPACITY_EXCEEDED:TransferReceipt.Status.RIGHTS_DENIED),"actual original component failed type");rejected=now;proof.put("FirstRejection",snapshot());stage=1;event("COMPONENT_FAILURE");return;}
            if(stage==1){if(id.equals("N05")){require(dest.getItem(0).is(Items.COBBLESTONE),"original component stone");other.setItem(0,dest.removeItemNoUpdate(0));RightsStateData.get(l.getServer()).bumpContext(did);}else grant();stage=2;restoredAt=now;event("COMPONENT_CONDITION_RESTORED");}
            boolean ok=SettlementGrowthData.get(l.getServer()).transfer(c,job,txn,actor,TransferIntent.GIFT,List.of(new TransferLeg(sid,did,ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),Map.of(sid,source,did,dest));
            if(now<rejected+1200){require(!ok&&job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).equals(first),"other failure retains original1200 no early submission");return;}
            require(ok&&count(source,Items.CARROT)==27&&count(dest,Items.CARROT)==3&&job.getCompoundOrEmpty("Transfers").getCompoundOrEmpty(txn.toString()).getIntOr("Attempt", 0)==2,"one lawful retry at original1200");proof.putLong("OriginalBackoffElapsed",now-rejected);finish("PASS_COMPONENT","other failure1200 unchanged; component direct call, not normal scheduler",true);
        }
        void finish(String result,String why,boolean success){if(done)return;done=true;proof.putString("Case",id);proof.putString("Result",result);proof.putString("Reason",why);proof.putLong("Elapsed",l.getGameTime()-start);proof.put("Timeline",timeline);proof.put("Final",snapshot());write(l,"foodcap-"+id+"-proof.nbt",proof);Sagadyssey.LOGGER.info("FOODCAP_RESULT case={} result={} elapsed={} why={}",id,result,l.getGameTime()-start,why);if(success)h.succeed();}
    }
}
