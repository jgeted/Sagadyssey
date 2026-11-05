package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Evidence for the existing standing barter, real custody and normal life; no test deal entry. */
@GameTestHolder("sagadyssey_p5") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class BoundedExchangeP5GameTests {
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_p5:empty");
    private static final Set<UUID> paused=new HashSet<>();
    private static final Set<UUID> loadedBodies=new HashSet<>();
    private static boolean warehousesRestored;
    private static CompoundTag restored;
    private static Case active;
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    private static void check(boolean v,String why){if(!v)throw new IllegalStateException(why);}
    @net.neoforged.bus.api.SubscribeEvent public static void spawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!(e.getLevel() instanceof ServerLevel l)||!(l.getServer() instanceof GameTestServer)||!phase().startsWith("p5-")||!(e.getEntity() instanceof NpcBase n))return;
        if(!phase().endsWith("resume")){if(paused.add(n.getUUID()))n.setNoAi(true);return;}
        if(loadedBodies.contains(n.getUUID()))return;
        try{var original=restored!=null?restored:NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("p5-evidence.nbt"),NbtAccounter.unlimitedHeap());
            var t=original.getList("Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("UUID").equals(n.getUUID())).findFirst().orElseThrow();
            check(n.getCharacterId().equals(NaturalVillageLifeData.person(NaturalVillageLifeData.decode(original.getCompound(t.getUUID("SettlementProof").equals(original.getCompound("Trace").getUUID("BuyerSettlement"))?"Buyer":"Seller")),t.getInt("OriginalIndex"))),"same character at original body load");
            check(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.getServer().registryAccess()).equals(t.getList("PersonalProof",Tag.TAG_COMPOUND))&&NaturalVillageEconomyData.items(n.getTransportInventory(),l.getServer().registryAccess()).equals(t.getList("CargoProof",Tag.TAG_COMPOUND))&&n.isNoAi()==t.getBoolean("NoAI"),"original physical bags/cargo and AI state exactly at entity load, before work");
            loadedBodies.add(n.getUUID());Sagadyssey.LOGGER.info("P5_BODY_LOAD original={} samePersonal=true sameCargo=true sameAI=true beforeWork=true",n.getUUID());
        }catch(Exception x){throw new IllegalStateException("P5 original entity load proof required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer)||!phase().startsWith("p5-"))return;
        try{var s=e.getServer();var l=s.overworld();paused.clear();restored=null;active=null;
            l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,s);
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,s);
            l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if(phase().endsWith("resume")){
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p5-evidence.nbt"),NbtAccounter.unlimitedHeap());
                var actual=links(l);NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("p5-restored-links.nbt"));
                Sagadyssey.LOGGER.info("P5_RESTORE_DIFFERENCES fields={}",actual.getAllKeys().stream().filter(k->!actual.get(k).equals(restored.getCompound("Links").get(k))).toList());
                var expected=restored.getCompound("Links").copy();
                // The existing region loader explicitly resets these two observation flags.
                // Compare that exact documented load result; preserve Phase, stocks and every other field.
                for(var entry:expected.getCompound("Economy").getList("Regions",Tag.TAG_COMPOUND)){var row=(CompoundTag)entry;row.putBoolean("ObservedAway",true);row.putBoolean("ReturnRequested",false);}
                check(comparable(actual).equals(comparable(expected)),"all associated ledgers and exact existing loader flags must match before first tick");
                var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLong("Time")+1);s.getWorldData().overworldData().setDayTime(saved.getLong("DayTime")+1);
                corridor(l,NaturalVillageLifeData.decode(restored.getCompound("Buyer")),NaturalVillageLifeData.decode(restored.getCompound("Seller")));
                for(var entry:restored.getList("Stores",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;var c=l.getBlockEntity(BlockPos.of(t.getLong("Position")));check(c instanceof Container&&NaturalVillageEconomyData.items((Container)c,s.registryAccess()).equals(t.getList("Items",Tag.TAG_COMPOUND)),"original physical warehouse before first tick");}
                warehousesRestored=true;Sagadyssey.LOGGER.info("P5_STORES_LOAD beforeTicks=true sameStores=true");
                Sagadyssey.LOGGER.info("P5_RESTORE beforeTicks=true linkedWorld=true stage={} originalTrade={}",restored.getString("Stage"),restored.getCompound("Trace").getUUID("Deal"));
            }
        }catch(Exception x){throw new IllegalStateException("P5 coherent original world required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void update(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(active==null||!phase().startsWith("p5-"))return;var c=active;
        try{c.tick();}catch(RuntimeException failure){active=null;c.h.runAtTickTime(c.h.getTick()+1,()->{throw failure;});}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!phase().startsWith("p5-"))return List.of();
        return List.of(new TestFunction("p5",phase().replace('-','_'),EMPTY.toString(),32000,0,true,h->{active=new Case(h);h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint 32000");}));}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static int corridor(ServerLevel l,NaturalVillageBuilding a,NaturalVillageBuilding b){
        check(a.dimension().equals(b.dimension())&&a.buildingOrigin().distSqr(b.buildingOrigin())<=192*192,"frozen bounded same-dimension route");
        int minX=Math.min(a.buildingOrigin().getX(),b.buildingOrigin().getX())-32,maxX=Math.max(a.buildingOrigin().getX(),b.buildingOrigin().getX())+32;
        int minZ=Math.min(a.buildingOrigin().getZ(),b.buildingOrigin().getZ())-32,maxZ=Math.max(a.buildingOrigin().getZ(),b.buildingOrigin().getZ())+32;
        int n=0;for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++){check(++n<=160,"fixed loaded corridor budget");l.setChunkForced(x,z,true);l.getChunk(x,z);}
        NaturalVillageR1GameTests.visiting(l,a,true);NaturalVillageR1GameTests.visiting(l,b,true);return n;
    }
    private static CompoundTag links(ServerLevel l){var s=l.getServer();var t=new CompoundTag();
        t.put("Exchange",SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("NaturalLife",NaturalVillageLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Economy",NaturalVillageEconomyData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Brain",NpcBrainData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Activities",LifeActivityData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Lod",NpcLodData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Life",NpcLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Households",HouseholdData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Supply",SupplyFlowData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Logistics",LogisticsData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Buildings",SettlementRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Characters",CharacterRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Fieldwork",com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));
        return t;
    }
    /** Compare the exact entries of the two existing unordered maps/sets, never their iteration order. */
    private static CompoundTag comparable(CompoundTag source){var copy=source.copy();
        for(var entry:copy.getCompound("Transfers").getList("Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)entry;var sorted=new TreeMap<UUID,CompoundTag>();
            for(var item:receipt.getList("Revisions",Tag.TAG_COMPOUND)){var revision=(CompoundTag)item;check(revision.hasUUID("Container")&&sorted.putIfAbsent(revision.getUUID("Container"),revision)==null,"unique revision map key");}
            var list=new ListTag();sorted.values().forEach(list::add);receipt.put("Revisions",list);
        }
        var knowledge=new TreeMap<String,CompoundTag>();
        for(var entry:copy.getCompound("Supply").getList("Known",Tag.TAG_COMPOUND)){
            var t=(CompoundTag)entry;var actor=SubjectRef.load(t.getCompound("Actor"));check(t.hasUUID("Point"),"valid knowledge endpoint");
            check(knowledge.putIfAbsent(actor.type()+":"+actor.id()+":"+t.getUUID("Point"),t)==null,"no duplicate actor/point knowledge");
        }
        var known=new ListTag();knowledge.values().forEach(known::add);copy.getCompound("Supply").put("Known",known);return copy;
    }
    private static TransferRequest originalRequest(CompoundTag receipt){
        var x=receipt.getCompound("RequestIdentity");var legs=new ArrayList<TransferLeg>();var revisions=new ArrayList<ExpectedRevision>();var authorities=new HashMap<UUID,SubjectRef>();
        for(var entry:x.getList("Legs",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;legs.add(new TransferLeg(t.getUUID("Source"),t.getUUID("Destination"),ItemFingerprint.load(t.getCompound("Item")),t.getInt("Quantity")));}
        for(var entry:x.getList("ExpectedRevisions",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;revisions.add(new ExpectedRevision(t.getUUID("Container"),t.getLong("Revision")));}
        for(var entry:x.getList("SourceAuthorities",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;authorities.put(t.getUUID("Container"),SubjectRef.load(t.getCompound("Actor")));}
        var request=new TransferRequest(receipt.getUUID("Id"),SubjectRef.load(x.getCompound("Actor")),TransferIntent.valueOf(x.getString("Intent")),legs,revisions,x.getString("Authority"),receipt.getLong("CompletedAt"),authorities);
        check(request.identity().equals(x),"reconstructed original payload identity is exact");return request;
    }
    private static final class Case {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding seller,buyer;
        final Set<String> meals=new HashSet<>();final CompoundTag trace=new CompoundTag();final ListTag events=new ListTag();final List<BlockPos> walls=new ArrayList<>();
        boolean setup=false,restoredBodies=false;long blocked=-1,doneAt=-1;String seen="";RightGrant originalGrant;
        Case(GameTestHelper helper){h=helper;l=h.getLevel();
            seller=restored==null?NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2):NaturalVillageLifeData.decode(restored.getCompound("Seller"));
            buyer=restored==null?NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2):NaturalVillageLifeData.decode(restored.getCompound("Buyer"));
            corridor(l,seller,buyer);
            if(restored!=null){setup=true;trace.merge(restored.getCompound("Trace"));events.addAll(restored.getList("Events",Tag.TAG_COMPOUND));for(var m:restored.getList("Meals",Tag.TAG_STRING))meals.add(m.getAsString());}
        }
        NpcBase npc(NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        NpcBase courier(){return npc(buyer,1);}
        UUID id(NaturalVillageBuilding b,int i){return NaturalVillageEconomyData.storeId(b,i);}
        Container store(NaturalVillageBuilding b,int i){var p=NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i));check(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&!NaturalVillageEconomyData.protects(l.getServer(),b.dimension(),p),"loaded near live store required");var c=l.getBlockEntity(p);check(c instanceof Container,"actual template container required");return (Container)c;}
        SubjectRef home(NaturalVillageBuilding b){return SubjectRef.household(NaturalVillageLifeData.home(b));}
        void transfer(SubjectRef actor,TransferIntent intent,UUID from,Container source,UUID to,Container target,Item item,int n){var s=l.getServer();var rights=RightsStateData.get(s);long now=l.getGameTime();
            var req=new TransferRequest(UUID.randomUUID(),actor,intent,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),n)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"P5 declared finite allocation / negative-condition restoration",now);
            var result=new TransferService(rights,TransferLedgerData.get(s)).submit(req,Map.of(from,new TransferEndpoint(from,source),to,new TransferEndpoint(to,target)),now);check(result.successful()&&result.lawful(),"lawful recorded condition allocation: "+result.reason());
        }
        void farmerToPantry(TransferIntent intent){var n=npc(seller,0);transfer(SubjectRef.character(n.getCharacterId()),intent,ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),id(seller,1),store(seller,1),Items.CARROT,16);}
        CompoundTag deal(){var ds=SettlementExchangeData.get(l.getServer()).deals();check(ds.size()<=1,"one bounded deal, no repeated creation");return ds.isEmpty()?null:ds.getFirst();}
        SupplyFlowData.Route route(CompoundTag t,String key){return t.hasUUID(key)?SupplyFlowData.get(l.getServer()).route(t.getUUID(key)):null;}
        void init(){var s=l.getServer();check(!seller.settlementId().equals(buyer.settlementId())&&!home(seller).equals(home(buyer)),"independent settlements and households");
            for(var b:List.of(seller,buyer)){var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));check(home!=null&&home.settlementId().equals(b.settlementId()),"real settlement membership");for(int i=0;i<3;i++)check(npc(b,i)!=null&&npc(b,i).getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"six original identities");}
            check(deal()==null,"no trade before declared setup");store(buyer,2).setItem(1,new ItemStack(Items.OAK_LOG,2));
            if(!phase().equals("p5-no-stock"))farmerToPantry(phase().equals("p5-seller-rights")?TransferIntent.LOAN:TransferIntent.GIFT);
            var rights=RightsStateData.get(s);originalGrant=rights.grantsFor(id(buyer,2),home(buyer),l.getGameTime()).stream().filter(g->g.rights().contains(AssetRight.TRANSFER)).findFirst().orElseThrow();
            if(phase().equals("p5-rights"))rights.revoke(originalGrant.id());
            if(phase().equals("p5-reserved"))check(rights.reserve(new AssetReservation(ContainerIds.named("p5-existing-food-obligation"),id(seller,1),ItemFingerprint.of(new ItemStack(Items.CARROT)),16,home(seller),"p5:independent-existing-obligation","declared prior obligation",l.getGameTime(),l.getGameTime()+24000),16,l.getGameTime()),"prior obligation really reserved");
            NpcLifeData.get(s).setHunger(courier().getCharacterId(),850,l.getGameTime());
            check(LifePlanProvider.chooseUrgent(courier(),l.getGameTime()).isEmpty(),"hungry courier has no immediately usable food before trade");
            trace.putLong("InitialAt",l.getGameTime());trace.putInt("InitialHunger",850);trace.putInt("InitialCarrots",32);trace.putInt("InitialLogs",2);trace.putInt("CorridorChunks",corridor(l,seller,buyer));trace.putUUID("Courier",courier().getCharacterId());trace.putUUID("CourierBody",courier().getUUID());trace.putUUID("BuyerSettlement",buyer.settlementId());trace.putUUID("SellerSettlement",seller.settlementId());trace.putBoolean("InitiallyBlockedLife",true);
            trace.putLong("InitialCourierPosition",courier().blockPosition().asLong());courier().setNoAi(false);setup=true;balance();event("INITIAL");
        }
        int physical(Item item){int total=0;for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++)total+=count(store(b,i),item);for(int i=0;i<3;i++){var n=npc(b,i);total+=count(n.getPersonalInventory(),item)+count(n.getTransportInventory(),item);}}return total;}
        void balance(){check(physical(Items.CARROT)+meals.size()==32,"carrots = all actual stores/bags/cargo + actual receipted meals, stock="+physical(Items.CARROT)+" meals="+meals.size());check(physical(Items.OAK_LOG)==2,"exact two finite logs, no duplicate or lost goods");}
        CompoundTag physical(){var out=new CompoundTag();var stores=new ListTag();var bodies=new ListTag();var s=l.getServer();
            for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++){var t=new CompoundTag();t.putUUID("Settlement",b.settlementId());t.putInt("Index",i);t.putUUID("Id",id(b,i));t.putLong("Position",NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i)).asLong());t.put("Items",NaturalVillageEconomyData.items(store(b,i),s.registryAccess()));var context=RightsStateData.get(s).context(id(b,i));if(context!=null)t.put("DefaultOwner",context.defaultBeneficialOwner().save());else t.putString("Registration","unused empty arrival storage has no household context yet");stores.add(t);}
                for(int i=0;i<3;i++){var n=npc(b,i);var t=n.saveWithoutId(new CompoundTag());t.putUUID("SettlementProof",b.settlementId());t.putInt("OriginalIndex",i);t.put("PersonalProof",NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));t.put("CargoProof",NaturalVillageEconomyData.items(n.getTransportInventory(),s.registryAccess()));bodies.add(t);}}
            out.put("Stores",stores);out.put("Bodies",bodies);return out;
        }
        void event(String state){var t=new CompoundTag();t.putString("State",state);t.putLong("At",l.getGameTime());var d=deal();if(d!=null)t.put("Deal",d);t.put("Physical",physical());t.put("Rights",RightsStateData.get(l.getServer()).save(new CompoundTag(),l.getServer().registryAccess()));t.putInt("Meals",meals.size());events.add(t);Sagadyssey.LOGGER.info("P5_EVENT state={} at={} courier={} position={} carrots={} logs={} meals={} deal={}",state,l.getGameTime(),courier().getCharacterId(),courier().blockPosition(),physical(Items.CARROT),physical(Items.OAK_LOG),meals.size(),d);}
        void snapshot(String state){try{balance();var s=l.getServer();var out=physical();out.putString("Stage",state);out.put("Buyer",NaturalVillageLifeData.get(s).village(buyer.buildingKey()));out.put("Seller",NaturalVillageLifeData.get(s).village(seller.buildingKey()));out.put("Links",links(l));out.put("Trace",trace.copy());out.put("Events",events.copy());out.putLong("Time",l.getGameTime());var ms=new ListTag();meals.stream().sorted().forEach(m->ms.add(StringTag.valueOf(m)));out.put("Meals",ms);
            var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(out,world.resolve("p5-evidence.nbt"));s.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            var dst=world.resolveSibling("world-p5-snapshot");check(Files.notExists(dst),"never overwrite prior checkpoint");try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var to=dst.resolve(world.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);}}
            Sagadyssey.LOGGER.info("P5_CHECKPOINT state={} linkedWorld=true trade={} trace={}",state,deal(),trace);
        }catch(Exception e){throw new IllegalStateException("P5 complete associated world snapshot",e);}}
        void verifyBodies(){for(var b:List.of(seller,buyer))for(int i=0;i<3;i++){var n=npc(b,i);check(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"same original body/person restored");}
            if(restoredBodies||restored==null)return;
            check(warehousesRestored&&loadedBodies.size()==6,"every warehouse checked before first tick and all six original bodies/bags/cargo checked at load before work");
            restoredBodies=true;trace.putBoolean("OriginalPhysicalWorldRestored",true);Sagadyssey.LOGGER.info("P5_RESTORE_PHYSICAL samePersons=true sameStores=true sameCargo=true noReassignment=true");
        }
        void finalAudit(){var s=l.getServer();var t=deal();check(t!=null&&t.getString("State").equals("DONE")&&t.getInt("FoodOwed")==0,"one fully paid original exchange");
            for(String field:List.of("PayRoute","FoodRoute")){var r=route(t,field);int q=field.equals("PayRoute")?2:4;var n=NeedRequestData.get(s).request(r.need());var o=LogisticsData.get(s).order(r.id());check(r.stage()==SupplyFlowData.Stage.DONE&&r.carrierCharacter().equals(courier().getCharacterId())&&r.carrierEntity().equals(courier().getUUID()),"same original courier completed both legs");check(n.delivered()==q&&n.reserved()==0&&n.inTransit()==0&&o.deliveredCount()==q&&o.pickedUpCount()==q,"actual needs/logistics reconcile exact delivered quantities");check(RightsStateData.get(s).reservationsForToken(r.token(),l.getGameTime()).isEmpty(),"no orphan route reservation");}
            check(count(store(seller,1),Items.OAK_LOG)==2&&count(store(seller,1),Items.CARROT)==12,"seller physically received2 logs and paid4 carrots");
            check(count(store(buyer,2),Items.OAK_LOG)==0&&count(courier().getTransportInventory(),Items.OAK_LOG)==0&&count(courier().getTransportInventory(),Items.CARROT)==0,"source debited and cargo empty");
            check(count(store(buyer,1),Items.CARROT)+meals.size()==4&&meals.size()>=1&&NpcLifeData.get(s).needs(courier().getCharacterId()).hunger()<850,"delivered four carrots, then actual normal meal and hunger recovery");
            for(var b:List.of(seller,buyer)){var c=RightsStateData.get(s).context(id(b,1));check(c.defaultBeneficialOwner().equals(home(b)),"distinct recipient household title");for(int slot=0;slot<store(b,1).getContainerSize();slot++){var stack=store(b,1).getItem(slot);if(stack.isEmpty())continue;var claim=RightsStateData.get(s).claimAt(id(b,1),slot,ItemFingerprint.of(stack));check(claim==null||claim.beneficialOwner().equals(home(b)),"delivered stock recipient has lawful title");}}
            check(trace.getBoolean("PayCarried")&&trace.getBoolean("FoodCarried")&&trace.getDouble("MaxTravelSquared")>=48*48,"actual custody and bounded physical movement, no teleport");
            check(SupplyFlowData.get(s).routes().stream().noneMatch(r->!r.closed()),"no leftover transport obligation");
            for(var n:NeedRequestData.get(s).requests()){check(n.reserved()==0&&n.inTransit()==0,"no unfinished reservation/in-transit double count");if(!n.status().terminal()&&n.status()!=NeedRequestStatus.DELIVERED)check(l.getGameTime()-n.createdAt()<=24000,"only explicit young unmatched local food needs, no hidden aged backlog");}
            check(physical(Items.CARROT)+meals.size()==32,"independent exact food equation");
            var before=SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess());SettlementExchangeData.get(s).tick(s);SettlementExchangeData.get(s).tick(s);check(SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess()).equals(before),"settlement replay does not repay or add another contract");
            // Re-submit the exact original payloads, not fabricated completion acknowledgements.
            var receipts=TransferLedgerData.get(s).save(new CompoundTag(),s.registryAccess()).getList("Receipts",Tag.TAG_COMPOUND);
            int replays=0;for(var entry:receipts){var rec=(CompoundTag)entry;if(!rec.contains("RequestIdentity"))continue;var request=originalRequest(rec);if(!request.authority().startsWith("need-route:"))continue;var previous=TransferLedgerData.get(s).receipt(request.transferId());check(new TransferService(RightsStateData.get(s),TransferLedgerData.get(s)).submit(request,Map.of(),l.getGameTime()).equals(previous),"exact transfer replay reuses original receipt without endpoints or stock mutation");if(previous.successful())replays++;}
            check(replays==4,"two physical pickups and two deliveries replay exactly once");balance();trace.putInt("ExactTransferReplays",replays);trace.putInt("ReceiptedMeals",meals.size());trace.putInt("FinalHunger",NpcLifeData.get(s).needs(courier().getCharacterId()).hunger());trace.putInt("FinalDebt",0);trace.putInt("ActiveTradeRoutes",0);trace.putLong("Elapsed",l.getGameTime()-trace.getLong("InitialAt"));
        }
        void tick(){
            if(!setup){check(h.getTick()<=1000,"initialization frozen deadline");if(h.getTick()<150)return;init();}
            if(courier()==null){check(h.getTick()<1000,"original body must reload, never respawn");return;}verifyBodies();var s=l.getServer();long now=l.getGameTime();check(now-trace.getLong("InitialAt")<=24000,"frozen normal fulfillment deadline");
            for(var b:List.of(seller,buyer))check(NaturalVillageEconomyData.get(s).row(b.buildingKey()).getString("Phase").equals("NEAR"),"P5 only loaded-near authority, no remote handoff");
            NaturalVillageR1GameTests.observe(l,buyer,meals);NaturalVillageR1GameTests.observe(l,seller,meals);balance();
            double distance=courier().blockPosition().distSqr(BlockPos.of(trace.getLong("InitialCourierPosition")));trace.putDouble("MaxTravelSquared",Math.max(trace.getDouble("MaxTravelSquared"),distance));
            if(!trace.getBoolean("ConditionRestored")&&Set.of("p5-no-stock","p5-reserved","p5-rights","p5-seller-rights").contains(phase())){
                check(deal()==null,"unavailable or unauthorized stock must not be promised");if(now-trace.getLong("InitialAt")<2400)return;
                if(phase().equals("p5-no-stock"))farmerToPantry(TransferIntent.GIFT);
                if(phase().equals("p5-reserved"))RightsStateData.get(s).releaseReservations("p5:independent-existing-obligation");
                if(phase().equals("p5-rights"))RightsStateData.get(s).grant(originalGrant);
                if(phase().equals("p5-seller-rights")){var n=npc(seller,0);transfer(SubjectRef.character(n.getCharacterId()),TransferIntent.RETURN,id(seller,1),store(seller,1),ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),Items.CARROT,16);farmerToPantry(TransferIntent.GIFT);}
                trace.putBoolean("ConditionRestored",true);trace.putLong("UnavailableStockObserved",now-trace.getLong("InitialAt"));event("CONDITION_RESTORED");
            }
            var t=deal();if(t==null)return;trace.putUUID("Deal",t.getUUID("Id"));check(!t.getString("State").equals("REVIEW"),"original obligation needs review: "+t.getString("Reason"));
            var pay=route(t,"PayRoute");var food=route(t,"FoodRoute");String state=t.getString("State")+":"+(pay==null?"none":pay.stage())+":"+(food==null?"none":food.stage());
            if(!state.equals(seen)){seen=state;event(state);}
            if(pay!=null&&pay.stage()==SupplyFlowData.Stage.CARRYING){check(count(courier().getTransportInventory(),Items.OAK_LOG)==2,"two actual prepaid logs in original cargo");trace.putBoolean("PayCarried",true);
                if(phase().equals("p5-pay-save")){snapshot("PAY_CARRYING");active=null;h.succeed();return;}
                if(phase().equals("p5-paid-shortage")&&!trace.getBoolean("PaidShortageStarted")){var n=npc(seller,0);transfer(home(seller),TransferIntent.GIFT,id(seller,1),store(seller,1),ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),Items.CARROT,16);trace.putBoolean("PaidShortageStarted",true);event("SELLER_FOOD_WITHDRAWN_LAWFULLY");}
            }
            if(phase().equals("p5-paid-shortage")&&pay!=null&&pay.stage()==SupplyFlowData.Stage.DONE&&!trace.getBoolean("PaidShortageRestored")){
                if(!trace.contains("PayObservedDoneAt"))trace.putLong("PayObservedDoneAt",now);
                if(t.getInt("FoodOwed")!=4){check(now-trace.getLong("PayObservedDoneAt")<=20,"normal twenty-tick exchange coordinator must observe actual payment");return;}
                check(t.getInt("FoodOwed")==4&&food==null&&count(store(seller,1),Items.OAK_LOG)==2,"prepaid side delivered, four-food debt retained, no fictitious route/refund");
                if(blocked<0){blocked=now;event("PAID_FOOD_OWED_WAIT");}if(now-blocked<2400)return;farmerToPantry(TransferIntent.GIFT);trace.putBoolean("PaidShortageRestored",true);trace.putLong("PaidShortageWait",now-blocked);event("PAID_FOOD_RESTORED");
            }
            if(food!=null&&food.stage()==SupplyFlowData.Stage.CARRYING){check(pay.stage()==SupplyFlowData.Stage.DONE&&count(courier().getTransportInventory(),Items.CARROT)==4&&t.getInt("FoodOwed")==4,"one side paid, original four-food physical cargo and debt");trace.putBoolean("FoodCarried",true);
                if(phase().equals("p5-food-save")){snapshot("FOOD_CARRYING_PAID");active=null;h.succeed();return;}
                if(phase().equals("p5-obstruction")&&!trace.getBoolean("RoadRestored")){
                    if(blocked<0){blocked=now;var c=courier().blockPosition();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=3;y++)if(Math.abs(x)==2||Math.abs(z)==2||y==3){var p=c.offset(x,y,z);if(l.getBlockState(p).isAir()){walls.add(p);l.setBlockAndUpdate(p,Blocks.BEDROCK.defaultBlockState());}}trace.putInt("HungerAtObstruction",NpcLifeData.get(s).needs(courier().getCharacterId()).hunger());event("PHYSICAL_ROAD_BLOCKED");}
                    check(count(store(buyer,1),Items.CARROT)==0&&NeedRequestData.get(s).request(food.need()).delivered()==0,"blocked cargo cannot be fictitiously delivered");
                    if(now-blocked<2000)return;for(var p:walls)l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());check(NpcLifeData.get(s).needs(courier().getCharacterId()).hunger()>trace.getInt("HungerAtObstruction"),"real hunger persisted during obstruction");trace.putBoolean("RoadRestored",true);trace.putLong("ObstructionWait",now-blocked);event("PHYSICAL_ROAD_RESTORED");
                }
            }
            if(t.getString("State").equals("DONE")&&meals.size()>=1){if(doneAt<0){doneAt=now;event("REAL_LIFE_RECOVERED");}if(now-doneAt<400)return;
                finalAudit();event("FINAL_AUDIT");snapshot("DONE");Sagadyssey.LOGGER.info("P5_ACCEPT phase={} paidLogs=2 deliveredCarrots=4 realMeals={} hunger={} elapsed={} originalCourier={} originalDeal={} debt=0",phase(),meals.size(),trace.getInt("FinalHunger"),trace.getLong("Elapsed"),courier().getCharacterId(),t.getUUID("Id"));active=null;h.succeed();
            }
            if(now%1000==0)Sagadyssey.LOGGER.info("P5_PROGRESS phase={} elapsed={} state={} courier={} position={} needs={} plan={}",phase(),now-trace.getLong("InitialAt"),state,courier().getCharacterId(),courier().blockPosition(),NpcLifeData.get(s).needs(courier().getCharacterId()),NpcBrainRuntime.data(s).active(courier().getUUID()));
        }
    }
}
