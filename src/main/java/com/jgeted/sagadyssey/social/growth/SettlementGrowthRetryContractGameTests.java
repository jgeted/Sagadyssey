package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Isolated diagnostics only. No production hooks or persistence format changes. */
@GameTestHolder("sagadyssey_development") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementGrowthRetryContractGameTests {
    static CompoundTag restored;
    static String phase(){return System.getProperty("sagadyssey.development.phase","");}
    static String caseId(){return phase().replace("counter-v11-","").toUpperCase(Locale.ROOT);}
    static boolean p2(){return Set.of("T05","T06","T08","T09","G03").contains(caseId());}
    static CompoundTag linked(ServerLevel l){
        var s=l.getServer();var t=LodContinuityP8GameTests.associatedState(l);
        t.put("Development",SettlementDevelopmentData.get(s).save(new CompoundTag(),s.registryAccess()));
        var c=SettlementCoordinatorData.existing(s);t.put("Coordination",c==null?new CompoundTag():c.save(new CompoundTag(),s.registryAccess()));
        var g=SettlementGrowthData.existing(s);t.put("Growth",g==null?new CompoundTag():g.save(new CompoundTag(),s.registryAccess()));
        var sf=SettlementSafetyData.existing(s);t.put("Safety",sf==null?new CompoundTag():sf.save(new CompoundTag(),s.registryAccess()));
        t.put("Batches",ProductionBatchData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Orders",ProductionOrderData.get(s).save(new CompoundTag(),s.registryAccess()));return t;
    }
    static void require(boolean x,String m){if(!x)throw new IllegalStateException(m);}
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!phase().startsWith("counter-v11-")||!p2()||!(e.getServer() instanceof GameTestServer))return;
        try{
            var s=e.getServer();var l=s.overworld();restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("development-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            var actual=linked(l);var expected=restored.getCompound("Links").copy();
            for(var x:expected.getCompound("Economy").getList("Regions",Tag.TAG_COMPOUND)){var row=(CompoundTag)x;row.putBoolean("ObservedAway",true);row.putBoolean("ReturnRequested",false);}
            write(l,"v11-before-first-tick.nbt",actual);
            require(LodContinuityP8GameTests.comparableState(expected).equals(LodContinuityP8GameTests.comparableState(actual)),"exact original associated ledgers before first tick");
            var data=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
            require(data.getLong("Time")==restored.getLong("SavedTime"),"full original checkpoint clock");
            l.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE,data.getCompound("GameRules"))),s);
            s.getWorldData().overworldData().setGameTime(data.getLong("Time")+1);s.getWorldData().overworldData().setDayTime(data.getLong("DayTime")+1);
            Sagadyssey.LOGGER.info("V11_RESTORE case={} associatedLedgers=true time={} noWallCatchup=true",caseId(),data.getLong("Time"));
        }catch(Exception x){throw new IllegalStateException("V11 full original checkpoint required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!phase().startsWith("counter-v11-")||!p2()||restored==null||!(e.getEntity() instanceof NpcBase n)||!(e.getLevel() instanceof ServerLevel l))return;
        var saved=restored.getList("Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("Body").equals(n.getUUID())).findFirst().orElseThrow();
        require(saved.getUUID("Person").equals(n.getCharacterId()),"original person/body at load");
        require(saved.get("Personal").equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess())),"original personal stock");
        require(saved.get("Cargo").equals(NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess())),"original cargo stock");
        Sagadyssey.LOGGER.info("V11_BODY case={} person={} body={} originalGoods=true",caseId(),n.getCharacterId(),n.getUUID());
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!phase().matches("counter-v11-t0[1-9]"))return List.of();
        Sagadyssey.LOGGER.info("V11_SELECTED case={} expected=1",caseId());
        return List.of(new TestFunction("v11-retry",caseId().toLowerCase(Locale.ROOT),"sagadyssey_development:empty",p2()?17000:5000,0,true,h->new Run(h).start()));
    }
    static void write(ServerLevel l,String name,CompoundTag t){try{NbtIo.writeCompressed(t,l.getServer().getWorldPath(LevelResource.ROOT).resolve(name));}catch(Exception x){throw new IllegalStateException(x);}}
    static CompoundTag bodies(ServerLevel l,List<NaturalVillageBuilding> vs){var t=new CompoundTag();for(var b:vs)for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);if(n==null)continue;var q=new CompoundTag();n.saveWithoutId(q);t.put(n.getUUID().toString(),q);}return t;}
    static CompoundTag snapshot(ServerLevel l,List<NaturalVillageBuilding> vs){var t=linked(l);t.put("ActualBodies",bodies(l,vs));t.putLong("At",l.getGameTime());var stores=new ListTag();for(var b:vs)for(int i=0;i<NaturalVillageLayout.stores(b).size();i++){var c=NaturalPolityData.inventory(l.getServer(),b,i);if(c==null)continue;var q=new CompoundTag();q.putString("Building",b.buildingKey());q.putInt("Index",i);q.put("Items",NaturalVillageEconomyData.items(c,l.registryAccess()));stores.add(q);}t.put("WorldStores",stores);return t;}
    @SuppressWarnings("unchecked") static CompoundTag actualRow(SettlementDevelopmentData d,String key){try{var f=SettlementDevelopmentData.class.getDeclaredField("sites");f.setAccessible(true);return ((Map<String,CompoundTag>)f.get(d)).get(key);}catch(Exception x){throw new IllegalStateException(x);}}
    static Object load(Class<?> type,CompoundTag t,ServerLevel l){try{var m=type.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);m.setAccessible(true);return m.invoke(null,t,l.registryAccess());}catch(Exception x){throw new IllegalStateException(x);}}
    static CompoundTag firstProject(CompoundTag row){var ps=row.getList("Projects",Tag.TAG_COMPOUND);return ps.isEmpty()?null:ps.getCompound(0);}
    static CompoundTag food(CompoundTag row){var p=firstProject(row);return p==null?new CompoundTag():p.getCompound("Jobs").getCompound("FOOD");}
    static void copyField(CompoundTag from,CompoundTag to,String key){if(from.contains(key))to.put(key,from.get(key).copy());else to.remove(key);}
    static final class Run {
        final GameTestHelper h;final ServerLevel l;final String id=caseId();final List<NaturalVillageBuilding> vs;final CompoundTag proof=new CompoundTag();
        long initialized=-1,at=-1,failedAt=-1;boolean done,injected;int stage,q;NpcBase c,w;SubjectRef owner;UUID source,dest,extra,txn,originalReceipt;SimpleContainer sbox,dbox,ebox;CompoundTag job=new CompoundTag(),preJob,preLedger,preWorld;int sourceAfter,bagAfter,destAfter;long injectedAt;
        Run(GameTestHelper h){this.h=h;l=h.getLevel();vs=p2()?restored.getList("Villages",Tag.TAG_COMPOUND).stream().map(x->NaturalVillageLifeData.decode((CompoundTag)x)).toList():List.of(NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V3));}
        void start(){
            l.getServer().setDifficulty(Difficulty.NORMAL,true);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,l.getServer());
            if(p2()){SettlementDevelopmentGameTests.releaseObservation(l,vs);SettlementDevelopmentGameTests.observeRoute(l,vs);for(var x:restored.getCompound("Metadata").getList("TemporaryBarrier",Tag.TAG_COMPOUND)){var b=(CompoundTag)x;var pos=BlockPos.of(b.getLong("Position"));require(l.getBlockState(pos).is(Blocks.GLASS),"exact declared barrier");l.setBlock(pos,NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),b.getCompound("Before")),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}}
            else {l.setDayTime(6000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());}
            at=l.getGameTime();l.getServer().tickRateManager().requestGameToSprint(p2()?17000:5000);h.onEachTick(()->{if(done)return;try{tick();}catch(GameTestAssertException x){throw x;}catch(Exception x){finish("HARNESS_ERROR",x.toString(),false);}});
        }
        boolean ready(){var n=NaturalVillageLifeData.existing(l.getServer());return n!=null&&vs.stream().allMatch(b->{var v=n.village(b.buildingKey());return v!=null&&v.getBoolean("Ready")&&NaturalVillageR1GameTests.actor(l,b,0)!=null&&NaturalVillageR1GameTests.actor(l,b,1)!=null&&NaturalVillageR1GameTests.actor(l,b,2)!=null;});}
        void init(){
            c=NaturalVillageR1GameTests.actor(l,vs.getFirst(),1);w=NaturalVillageR1GameTests.actor(l,vs.getFirst(),2);initialized=l.getGameTime();proof.putLong("Initialized",initialized);proof.put("Initial",snapshot(l,vs));
            if(p2())return;
            var b=vs.getFirst();var rights=RightsStateData.get(l.getServer());
            for(int i=0;i<NaturalVillageLayout.stores(b).size();i++){var inv=NaturalPolityData.inventory(l.getServer(),b,i);if(inv!=null)clear(inv,NaturalVillageEconomyData.storeId(b,i),rights);}
            for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);n.setCommand(NpcCommand.STAY);n.setNoAi(true);n.setHealth(20);clear(n.getPersonalInventory(),ContainerIds.npcPersonal(n.getCharacterId()),rights);n.getTransportInventory().clearContent();}
            NaturalPolityData.inventory(l.getServer(),b,1).setItem(0,new ItemStack(Items.CARROT,64));var f=NaturalVillageR1GameTests.actor(l,b,0);f.getPersonalInventory().setItem(0,new ItemStack(Items.CARROT,12));f.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_HOE));
            source=ContainerIds.named("v11:"+id+":S");dest=ContainerIds.named("v11:"+id+":D");extra=ContainerIds.named("v11:"+id+":E");txn=ContainerIds.named("v11:"+id+":pick:0");owner=SubjectRef.household(NaturalVillageLifeData.home(b));sbox=new SimpleContainer(1);dbox=new SimpleContainer(1);ebox=new SimpleContainer(1);sbox.setItem(0,new ItemStack(Items.CARROT,30));
            rights.ensureContext(source,"component:"+id+":S",SubjectRef.character(f.getCharacterId()),SubjectRef.character(f.getCharacterId()),AccessPolicy.PRIVATE);rights.ensureContext(dest,"component:"+id+":D",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(extra,"component:"+id+":E",owner,owner,AccessPolicy.PRIVATE);
            for(var who:List.of(owner,SubjectRef.character(c.getCharacterId()),SubjectRef.character(w.getCharacterId())))rights.grant(new RightGrant(ContainerIds.named("v11:"+id+":grant:"+who),source,who,Set.of(AssetRight.TRANSFER),"explicit finite test setup",0,null,true));
            if(id.equals("T07"))dbox.setItem(0,new ItemStack(Items.COBBLESTONE));proof.put("ConfiguredInitial",snapshot(l,vs));event("CONFIGURED",job);
        }
        void clear(Container inv,UUID cid,RightsStateData r){for(int i=0;i<inv.getContainerSize();i++)r.removeClaimsForSlot(cid,i);inv.clearContent();}
        boolean transfer(NpcBase n,UUID from,Container fromInv,UUID to,Container toInv,int quantity,Item item,CompoundTag record,UUID tx,SubjectRef actor){return SettlementGrowthData.get(l.getServer()).transfer(n,record,tx,actor,TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),quantity)),Map.of(from,fromInv,to,toInv));}
        int count(Container inv){return SettlementDevelopmentGameTests.count(inv,Items.CARROT);}
        void tick() throws Exception {
            long now=l.getGameTime();if(!ready()){if(now-at>=1600)finish("UNSUPPORTED","initialization exceeded1600 included in total",false);return;}
            if(initialized<0){init();return;}if(p2()){p2tick(now);return;}
            var bag=ContainerIds.npcPersonal(c.getCharacterId());
            if(stage==0){boolean ok=transfer(c,source,sbox,id.equals("T07")?dest:bag,id.equals("T07")?dbox:c.getPersonalInventory(),3,Items.CARROT,job,txn,owner);proof.put("First",job.copy());
                if(id.equals("T07")){require(!ok,"actual capacity failure expected");var rec=TransferLedgerData.get(l.getServer()).receipt(job.getUUID("LastTransfer"));require(rec!=null&&rec.status()==TransferReceipt.Status.CAPACITY_EXCEEDED&&count(sbox)==30,"definite original no-effect capacity failure");failedAt=now;stage=1;return;}
                require(ok&&count(sbox)==27&&count(c.getPersonalInventory())==3,"original successful lawful3");originalReceipt=job.getUUID("LastTransfer");event("FIRST_SUCCESS",job);stage=1;return;
            }
            if(id.equals("T07")){
                if(stage==1){require(now==failedAt+1,"restore at exact next tick");var restore=new CompoundTag();require(transfer(c,dest,dbox,extra,ebox,1,Items.COBBLESTONE,restore,ContainerIds.named("v11:T07:restore"),owner),"lawful original stone return");proof.put("Restore",restore);stage=2;event("EXTERNAL_CAPACITY_RESTORED",job);return;}
                boolean ok=transfer(c,source,sbox,dest,dbox,3,Items.CARROT,job,txn,owner);
                if(now<failedAt+1200){if(ok||count(sbox)!=30){finish("FAIL","debit before original backoff boundary",true);return;}}
                else {if(!ok||count(sbox)!=27||count(dbox)!=3){finish("FAIL","definite failure did not retry once lawfully at1200",true);return;}finish("PASS","definite failure and original finite lawful recovery; component only",false);}return;
            }
            if(id.equals("T01")){
                if(stage==2){write(l,"v11-T01-component-roundtrip.nbt",job);job=NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("v11-T01-component-roundtrip.nbt"),NbtAccounter.unlimitedHeap());}boolean ok=transfer(c,source,sbox,bag,c.getPersonalInventory(),3,Items.CARROT,job,txn,owner);require(ok&&job.getUUID("LastTransfer").equals(originalReceipt)&&count(sbox)==27&&count(c.getPersonalInventory())==3,"same success idempotent");event(stage==1?"REPEAT_NEXT_TICK":"REPEAT_TAG_ROUNDTRIP",job);if(stage++==2)finish("PASS","same logical operation repeated twice, source27 originalC3",false);return;
            }
            var person=id.equals("T03")?w:c;UUID to=id.equals("T04")?extra:ContainerIds.npcPersonal(person.getCharacterId());Container inv=id.equals("T04")?ebox:person.getPersonalInventory();int quantity=id.equals("T02")?4:3;
            boolean returned=transfer(person,source,sbox,to,inv,quantity,Items.CARROT,job,txn,owner);proof.putBoolean("ChangedPayloadReturnedSuccess",returned);proof.putInt("RequestedQuantity",quantity);proof.putString("RequestedPerson",person.getCharacterId().toString());proof.putString("RequestedDestination",to.toString());event("CHANGED_PAYLOAD_RESULT",job);
            if(returned){finish("FAIL","successful cache acknowledged changed business payload; no second physical debit observed; direct component reachability only",true);return;}finish("PASS","changed logical child content rejected without second debit",false);
        }
        void event(String what,CompoundTag t){Sagadyssey.LOGGER.info("V11_EVENT case={} event={} tick={} state={}",id,what,l.getGameTime(),t);}
        void p2tick(long now){
            var d=SettlementDevelopmentData.get(l.getServer());var row=d.site(vs.getFirst().buildingKey());var p=firstProject(row);var f=food(row);
            if(!injected){
                boolean pick=!id.equals("T06");boolean boundary=preJob!=null&&preJob.getInt("Cycle")==0&&f.getInt("Cycle")== (pick?0:1)&&(pick?!preJob.getBoolean("Cargo")&&f.getBoolean("Cargo"):preJob.getBoolean("Cargo")&&!f.getBoolean("Cargo"));
                if(boundary&&f.hasUUID("LastTransfer")){
                    originalReceipt=f.getUUID("LastTransfer");var receipt=TransferLedgerData.get(l.getServer()).receipt(originalReceipt);require(receipt!=null&&receipt.successful()&&receipt.lawful(),"authentic original operation receipt");
                    var identity=TransferLedgerData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()).getList("Receipts",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("Id").equals(originalReceipt)).findFirst().orElseThrow();
                    proof.put("OriginalReceipt",identity.copy());proof.put("ParentBefore",preWorld);proof.put("ParentAfter",snapshot(l,vs));q=pick?f.getInt("Quantity"):preJob.getInt("Quantity");require(q>0,"real positive original q");
                    sourceAfter=count(NaturalPolityData.inventory(l.getServer(),vs.getFirst(),0));bagAfter=count(c.getPersonalInventory());var destination=(Container)l.getBlockEntity(SettlementDevelopmentLayout.pantry(vs.getFirst(),p.getInt("Lot")));require(destination!=null,"original destination exists");destAfter=count(destination);
                    var injection=preJob.copy();if(id.equals("T05")||id.equals("T06"))for(var key:List.of("Transfers","LastTransfer","TransferRetryAt"))copyField(f,injection,key);
                    injection.remove("Submitting");if(id.equals("T09"))injection.putBoolean("Submitting",true);
                    actualRow(d,vs.getFirst().buildingKey()).getList("Projects",Tag.TAG_COMPOUND).getCompound(0).getCompound("Jobs").put("FOOD",injection);d.setDirty();
                    if(id.equals("T09")){
                        var led=(TransferLedgerData)load(TransferLedgerData.class,preLedger,l);l.getDataStorage().set(TransferLedgerData.DATA_NAME,led);
                        var loaded=(SettlementDevelopmentData)load(SettlementDevelopmentData.class,d.save(new CompoundTag(),l.registryAccess()),l);l.getDataStorage().set(SettlementDevelopmentData.NAME,loaded);
                        var reviewed=food(loaded.site(vs.getFirst().buildingKey()));require(reviewed.getBoolean("Review")&&reviewed.getBoolean("Submitting")&&reviewed.getString("Reason").contains("提交中断"),"target FOOD Submitting load path must be actually exercised");proof.putBoolean("ArtificialSubmittingNotCaptured",true);proof.put("ExactLoaderReview",reviewed.copy());
                    }
                    proof.put("Injected",snapshot(l,vs));injected=true;injectedAt=now;event("INJECTED_ORIGINAL_"+(pick?"PICK0":"DROP0"),injection);return;
                }
                preJob=f.isEmpty()?null:f.copy();
                // Capture full before-state only near the actual original endpoint.
                // Per-tick copies of every unrelated ledger are unnecessary and distort run cost.
                var endpoint=id.equals("T06")&&p!=null?SettlementDevelopmentLayout.pantry(vs.getFirst(),p.getInt("Lot")):NaturalVillageLayout.at(vs.getFirst(),NaturalVillageLayout.stores(vs.getFirst()).get(0));
                if(f.hasUUID("Plan")&&c.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(endpoint))<36){preLedger=TransferLedgerData.get(l.getServer()).save(new CompoundTag(),l.registryAccess());preWorld=snapshot(l,vs);}
                if(now%1000==0)event("NORMAL_PREPARATION",f);
                if(now-at>=12000)finish("UNSUPPORTED","normal original Cycle0 operation not reached within frozen preparation; no injected fake success",false);return;
            }
            var current=food(SettlementDevelopmentData.get(l.getServer()).site(vs.getFirst().buildingKey()));
            if(current.hasUUID("LastTransfer")&&!current.getUUID("LastTransfer").equals(originalReceipt)){
                var receipt=TransferLedgerData.get(l.getServer()).receipt(current.getUUID("LastTransfer"));if(receipt!=null&&receipt.successful()&&count(NaturalPolityData.inventory(l.getServer(),vs.getFirst(),0))<sourceAfter){finish("FAIL","post-success uncertain original operation submitted new successful debit",true);return;}
            }
            boolean reentered=current.getLong("LastAttempt")>injectedAt&&current.hasUUID("Person")&&preJob.hasUUID("Person")&&current.getUUID("Person").equals(preJob.getUUID("Person"));
            if(id.equals("T09")&&now-injectedAt>=200){proof.putBoolean("TargetReviewPathExercised",true);proof.putString("AutomaticRecovery","NOT_PASSED_REVIEW_ONLY");finish("PASS_SAFETY_ONLY","artificial Submitting reached actual loader Review; no automatic recovery claim",false);return;}
            if(reentered&&(current.getBoolean("Cargo")||current.getInt("Cycle")>0)){
                proof.put("Reentered",current.copy());proof.putBoolean("OriginalBusinessEntryReentered",true);
                if(id.equals("T05")&&current.getBoolean("Cargo")&&current.getInt("Quantity")!=q){finish("FAIL","original successful pickup q differs from replay Cargo quantity",true);return;}
                if(id.equals("T06")&&current.getInt("Cycle")==1){finish("PASS","same original drop0 resumed once, original receipt retained; injected recovery only",false);return;}
                if(id.equals("T05")&&current.getInt("Quantity")==q){finish("PASS","same original pick0 state reconciled without observed second debit; injected recovery only",false);return;}
            }
            if(current.getBoolean("Review")){proof.putString("AutomaticRecovery","NOT_PASSED_REVIEW_ONLY");finish("PASS_SAFETY_ONLY","original uncertain operation safely Review, automatic recovery unproven",false);return;}
            if(now-injectedAt>=5000)finish("UNSUPPORTED","target original retry not demonstrably reached/reconciled within diagnostic budget",false);
        }
        void finish(String result,String why,boolean hard){if(done)return;done=true;proof.putString("Case",id);proof.putString("Result",result);proof.putString("Reason",why);proof.putBoolean("HardFailure",hard);proof.putLong("Finished",l.getGameTime());proof.putLong("Elapsed",l.getGameTime()-at);proof.put("Final",snapshot(l,vs));proof.put("ComponentJob",job);if(sbox!=null){proof.putInt("SourceCarrots",count(sbox));proof.putInt("DCarrots",count(dbox));proof.putInt("ECarrots",count(ebox));proof.put("ComponentE",NaturalVillageEconomyData.items(ebox,l.registryAccess()));}write(l,"v11-"+id+"-proof.nbt",proof);Sagadyssey.LOGGER.info("V11_RESULT case={} result={} hard={} elapsed={} why={}",id,result,hard,l.getGameTime()-at,why);if(result.equals("FAIL")||result.equals("HARNESS_ERROR"))h.fail(result+": "+why);else h.succeed();}
    }
}
