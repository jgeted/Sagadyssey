package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Evidence for the existing standing barter, real custody and normal life; no test deal entry. */
@GameTestHolder("sagadyssey_p6") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class CharterDiplomacyP6GameTests {
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_p6:empty");
    private static final Set<UUID> paused=new HashSet<>();
    private static final Set<UUID> loadedBodies=new HashSet<>();
    private static boolean warehousesRestored;
    private static CompoundTag restored;
    private static Case active;
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    private static void check(boolean v,String why){if(!v)throw new IllegalStateException(why);}
    @net.neoforged.bus.api.SubscribeEvent public static void spawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!(e.getLevel() instanceof ServerLevel l)||!(l.getServer() instanceof GameTestServer)||!phase().startsWith("p6-")||!(e.getEntity() instanceof NpcBase n))return;
        if(!phase().endsWith("resume")){if(paused.add(n.getUUID()))n.setNoAi(true);return;}
        if(loadedBodies.contains(n.getUUID()))return;
        try{var original=restored!=null?restored:NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("p6-evidence.nbt"),NbtAccounter.unlimitedHeap());
            var t=MinecraftApi.listOfType(original, "Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.read("UUID", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID())).findFirst().orElseThrow();
            check(n.getCharacterId().equals(NaturalVillageLifeData.person(NaturalVillageLifeData.decode(original.getCompoundOrEmpty(t.read("SettlementProof", UUIDUtil.CODEC).orElseThrow().equals(original.getCompoundOrEmpty("Trace").read("BuyerSettlement", UUIDUtil.CODEC).orElseThrow())?"Buyer":"Seller")),t.getIntOr("OriginalIndex", 0))),"same character at original body load");
            check(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.getServer().registryAccess()).equals(MinecraftApi.listOfType(t, "PersonalProof",Tag.TAG_COMPOUND))&&NaturalVillageEconomyData.items(n.getTransportInventory(),l.getServer().registryAccess()).equals(MinecraftApi.listOfType(t, "CargoProof",Tag.TAG_COMPOUND))&&n.isNoAi()==t.getBooleanOr("NoAI", false),"original physical bags/cargo and AI state exactly at entity load, before work");
            loadedBodies.add(n.getUUID());Sagadyssey.LOGGER.info("P6_BODY_LOAD original={} samePersonal=true sameCargo=true sameAI=true beforeWork=true",n.getUUID());
        }catch(Exception x){throw new IllegalStateException("P6 original entity load proof required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer)||!phase().startsWith("p6-"))return;
        try{var s=e.getServer();var l=s.overworld();paused.clear();restored=null;active=null;
            l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
            l.getGameRules().set(GameRules.ADVANCE_TIME, true,s);
            l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if(phase().endsWith("resume")){
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p6-evidence.nbt"),NbtAccounter.unlimitedHeap());
                var actual=links(l);NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("p6-restored-links.nbt"));
                Sagadyssey.LOGGER.info("P6_RESTORE_DIFFERENCES fields={}",actual.keySet().stream().filter(k->!actual.get(k).equals(restored.getCompoundOrEmpty("Links").get(k))).toList());
                var expected=restored.getCompoundOrEmpty("Links").copy();
                // The existing region loader explicitly resets these two observation flags.
                // Compare that exact documented load result; preserve Phase, stocks and every other field.
                for(var entry:MinecraftApi.listOfType(expected.getCompoundOrEmpty("Economy"), "Regions",Tag.TAG_COMPOUND)){var row=(CompoundTag)entry;row.putBoolean("ObservedAway",true);row.putBoolean("ReturnRequested",false);}
                check(comparable(actual).equals(comparable(expected)),"all associated ledgers and exact existing loader flags must match before first tick");
                var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
                corridor(l,NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Buyer")),NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Seller")));
                for(var entry:MinecraftApi.listOfType(restored, "Stores",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;var c=l.getBlockEntity(BlockPos.of(t.getLongOr("Position", 0L)));check(c instanceof Container&&NaturalVillageEconomyData.items((Container)c,s.registryAccess()).equals(MinecraftApi.listOfType(t, "Items",Tag.TAG_COMPOUND)),"original physical warehouse before first tick");}
                warehousesRestored=true;Sagadyssey.LOGGER.info("P6_STORES_LOAD beforeTicks=true sameStores=true");
                Sagadyssey.LOGGER.info("P6_RESTORE beforeTicks=true linkedWorld=true stage={} originalTrade={}",restored.getStringOr("Stage", ""),restored.getCompoundOrEmpty("Trace").read("Deal", UUIDUtil.CODEC).orElseThrow());
            }
        }catch(Exception x){throw new IllegalStateException("P6 coherent original world required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void update(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(active==null||!phase().startsWith("p6-"))return;var c=active;
        try{c.tick();}catch(RuntimeException failure){active=null;c.h.runAtTickTime(c.h.getTick()+1,()->{throw failure;});}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!phase().startsWith("p6-"))return List.of();
        return List.of(new TestFunction("p5",phase().replace('-','_'),EMPTY.toString(),64000,0,true,h->{active=new Case(h);h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint 64000");}));}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static int corridor(ServerLevel l,NaturalVillageBuilding a,NaturalVillageBuilding b){
        check(a.dimension().equals(b.dimension())&&a.buildingOrigin().distSqr(b.buildingOrigin())<=192*192,"frozen bounded same-dimension route");
        int minX=Math.min(a.buildingOrigin().getX(),b.buildingOrigin().getX())-32,maxX=Math.max(a.buildingOrigin().getX(),b.buildingOrigin().getX())+32;
        int minZ=Math.min(a.buildingOrigin().getZ(),b.buildingOrigin().getZ())-32,maxZ=Math.max(a.buildingOrigin().getZ(),b.buildingOrigin().getZ())+32;
        int n=0;for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++){check(++n<=160,"fixed loaded corridor budget");l.setChunkForced(x,z,true);l.getChunk(x,z);}
        NaturalVillageR1GameTests.visiting(l,a,true);NaturalVillageR1GameTests.visiting(l,b,true);return n;
    }
    private static CompoundTag links(ServerLevel l){var s=l.getServer();var t=new CompoundTag();
        t.put("Polity",NaturalPolityData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Office",PoliticalStateData.get(s).save(new CompoundTag(),s.registryAccess()));
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
        for(var entry:MinecraftApi.listOfType(copy.getCompoundOrEmpty("Transfers"), "Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)entry;var sorted=new TreeMap<UUID,CompoundTag>();
            for(var item:MinecraftApi.listOfType(receipt, "Revisions",Tag.TAG_COMPOUND)){var revision=(CompoundTag)item;check(revision.read("Container", UUIDUtil.CODEC).isPresent()&&sorted.putIfAbsent(revision.read("Container", UUIDUtil.CODEC).orElseThrow(),revision)==null,"unique revision map key");}
            var list=new ListTag();sorted.values().forEach(list::add);receipt.put("Revisions",list);
        }
        var knowledge=new TreeMap<String,CompoundTag>();
        for(var entry:MinecraftApi.listOfType(copy.getCompoundOrEmpty("Supply"), "Known",Tag.TAG_COMPOUND)){
            var t=(CompoundTag)entry;var actor=SubjectRef.load(t.getCompoundOrEmpty("Actor"));check(t.read("Point", UUIDUtil.CODEC).isPresent(),"valid knowledge endpoint");
            check(knowledge.putIfAbsent(actor.type()+":"+actor.id()+":"+t.read("Point", UUIDUtil.CODEC).orElseThrow(),t)==null,"no duplicate actor/point knowledge");
        }
        var known=new ListTag();knowledge.values().forEach(known::add);copy.getCompoundOrEmpty("Supply").put("Known",known);return copy;
    }
    private static TransferRequest originalRequest(CompoundTag receipt){
        var x=receipt.getCompoundOrEmpty("RequestIdentity");var legs=new ArrayList<TransferLeg>();var revisions=new ArrayList<ExpectedRevision>();var authorities=new HashMap<UUID,SubjectRef>();
        for(var entry:MinecraftApi.listOfType(x, "Legs",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;legs.add(new TransferLeg(t.read("Source", UUIDUtil.CODEC).orElseThrow(),t.read("Destination", UUIDUtil.CODEC).orElseThrow(),ItemFingerprint.load(t.getCompoundOrEmpty("Item")),t.getIntOr("Quantity", 0)));}
        for(var entry:MinecraftApi.listOfType(x, "ExpectedRevisions",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;revisions.add(new ExpectedRevision(t.read("Container", UUIDUtil.CODEC).orElseThrow(),t.getLongOr("Revision", 0L)));}
        for(var entry:MinecraftApi.listOfType(x, "SourceAuthorities",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;authorities.put(t.read("Container", UUIDUtil.CODEC).orElseThrow(),SubjectRef.load(t.getCompoundOrEmpty("Actor")));}
        var request=new TransferRequest(receipt.read("Id", UUIDUtil.CODEC).orElseThrow(),SubjectRef.load(x.getCompoundOrEmpty("Actor")),TransferIntent.valueOf(x.getStringOr("Intent", "")),legs,revisions,x.getStringOr("Authority", ""),receipt.getLongOr("CompletedAt", 0L),authorities);
        check(request.identity().equals(x),"reconstructed original payload identity is exact");return request;
    }
    private static final class Case {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding seller,buyer;
        final Set<String> meals=new HashSet<>();final CompoundTag trace=new CompoundTag();final ListTag events=new ListTag();final List<BlockPos> walls=new ArrayList<>();
        boolean setup=false,restoredBodies=false;String seen="";
        Case(GameTestHelper helper){h=helper;l=h.getLevel();
            seller=restored==null?NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2):NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Seller"));
            buyer=restored==null?NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2):NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Buyer"));
            corridor(l,seller,buyer);
            if(restored!=null){setup=true;trace.merge(restored.getCompoundOrEmpty("Trace"));events.addAll(MinecraftApi.listOfType(restored, "Events",Tag.TAG_COMPOUND));for(var m:MinecraftApi.listOfType(restored, "Meals",Tag.TAG_STRING))meals.add(m.asString().orElseThrow());}
        }
        NpcBase npc(NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        NpcBase courier(){return npc(buyer,1);}
        UUID id(NaturalVillageBuilding b,int i){return NaturalVillageEconomyData.storeId(b,i);}
        Container store(NaturalVillageBuilding b,int i){var p=NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i));check(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&!NaturalVillageEconomyData.protects(l.getServer(),b.dimension(),p),"loaded near live store required");var c=l.getBlockEntity(p);check(c instanceof Container,"actual template container required");return (Container)c;}
        SubjectRef home(NaturalVillageBuilding b){return SubjectRef.household(NaturalVillageLifeData.home(b));}
        void transfer(SubjectRef actor,TransferIntent intent,UUID from,Container source,UUID to,Container target,Item item,int n){var s=l.getServer();var rights=RightsStateData.get(s);long now=l.getGameTime();
            var req=new TransferRequest(UUID.randomUUID(),actor,intent,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),n)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"P6 declared finite allocation / negative-condition restoration",now);
            var result=new TransferService(rights,TransferLedgerData.get(s)).submit(req,Map.of(from,new TransferEndpoint(from,source),to,new TransferEndpoint(to,target)),now);check(result.successful()&&result.lawful(),"lawful recorded condition allocation: "+result.reason());
        }
        void farmerToPantry(TransferIntent intent){var n=npc(seller,0);transfer(SubjectRef.character(n.getCharacterId()),intent,ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),id(seller,1),store(seller,1),Items.CARROT,16);}
        CompoundTag deal(){var ds=SettlementExchangeData.get(l.getServer()).deals();check(ds.size()<=2,"two bounded real deals, no repeated creation");return ds.isEmpty()?null:ds.getLast();}
        SupplyFlowData.Route route(CompoundTag t,String key){return t.read(key, UUIDUtil.CODEC).isPresent()?SupplyFlowData.get(l.getServer()).route(t.read(key, UUIDUtil.CODEC).orElseThrow()):null;}
        int command(NaturalVillageBuilding site,int index,String operation,NaturalVillageBuilding self,NaturalVillageBuilding peer){
            var n=npc(site,index);String cmd="saga politics "+operation+" "+self.settlementId()+(peer==null?"":" "+peer.settlementId());
            return execute(l.getServer().createCommandSourceStack().withEntity(n).withLevel(l).withPosition(n.position()).withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL).withSuppressedOutput(),cmd);
        }
        int execute(net.minecraft.commands.CommandSourceStack source,String command){try{return l.getServer().getCommands().getDispatcher().execute(command,source);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException denied){return 0;}}
        CompoundTag rule(NaturalVillageBuilding b){return NaturalPolityData.get(l.getServer()).site(b.settlementId()).getCompoundOrEmpty("ReserveRule");}
        CompoundTag agreement(){return CharterTradeGovernance.pact(l.getServer(),buyer.settlementId(),seller.settlementId());}
        int step(){return trace.getIntOr("Step", 0);}void step(int value){trace.putInt("Step",value);}
        List<CompoundTag> deals(){return SettlementExchangeData.get(l.getServer()).deals();}
        boolean manual(){return phase().equals("p6-manual");}
        void init(){var s=l.getServer();check(!seller.settlementId().equals(buyer.settlementId())&&!home(seller).equals(home(buyer)),"independent settlements and household titles");
            for(var b:List.of(seller,buyer)){var h=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));check(h!=null&&b.settlementId().equals(h.settlementId())&&NaturalPolityData.get(s).authorized(s,b,npc(b,1).getCharacterId()),"original current public officer and separate household");}
            check(deal()==null,"no transaction before initial configuration");farmerToPantry(TransferIntent.GIFT);
            npc(seller,0).getPersonalInventory().setItem(2,new ItemStack(Items.CARROT,4));
            for(var b:List.of(seller,buyer))store(b,3).setItem(2,new ItemStack(Items.CARROT,8));
            if(phase().equals("p6-public-title")){var donor=npc(seller,0);transfer(SubjectRef.character(donor.getCharacterId()),TransferIntent.LOAN,ContainerIds.npcPersonal(donor.getCharacterId()),donor.getPersonalInventory(),id(seller,3),store(seller,3),Items.CARROT,2);trace.store("PrivateOwner", UUIDUtil.CODEC,donor.getCharacterId());}
            int logs=phase().equals("p6-no-stock")?2:4;store(buyer,2).setItem(1,new ItemStack(Items.OAK_LOG,logs));
            NpcLifeData.get(s).setHunger(courier().getCharacterId(),850,l.getGameTime());NpcLifeData.get(s).setHunger(npc(seller,1).getCharacterId(),400,l.getGameTime());
            if(phase().equals("p6-public-held"))check(RightsStateData.get(s).reserve(new AssetReservation(ContainerIds.named("p6-prior-public-obligation"),id(seller,3),new ItemFingerprint("minecraft:carrot",0),8,SubjectRef.institution(seller.settlementId()),"p6:prior-public-allocation","initial independent eight-food public obligation",l.getGameTime(),l.getGameTime()+96000),8,l.getGameTime()),"actual original public reserve belongs to prior obligation");
            check(command(seller,1,"reserve enable",seller,null)==1&&command(buyer,1,"reserve enable",buyer,null)==1,"real identity-bound product entry adopts rules, not decisions");
            check(rule(seller).getStringOr("State", "").equals("PENDING")&&rule(buyer).getStringOr("State", "").equals("PENDING"),"test did not preconfigure policy decisions");
            trace.putLong("InitialAt",l.getGameTime());trace.putInt("InitialHunger",850);trace.putInt("InitialCarrots",52);trace.putInt("InitialLogs",logs);trace.putInt("CorridorChunks",corridor(l,seller,buyer));trace.store("Courier", UUIDUtil.CODEC,courier().getCharacterId());trace.store("CourierBody", UUIDUtil.CODEC,courier().getUUID());trace.store("BuyerSettlement", UUIDUtil.CODEC,buyer.settlementId());trace.store("SellerSettlement", UUIDUtil.CODEC,seller.settlementId());trace.putLong("InitialCourierPosition",courier().blockPosition().asLong());step(0);courier().setNoAi(false);setup=true;balance();event("INITIAL_CONFIGURATION_ONLY");
        }
        int physical(Item item){int total=0;for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++)total+=count(store(b,i),item);for(int i=0;i<3;i++){var n=npc(b,i);total+=count(n.getPersonalInventory(),item)+count(n.getTransportInventory(),item);}}return total;}
        void balance(){check(physical(Items.CARROT)+meals.size()==52,"carrots = all actual stores/bags/cargo + actual receipted meals, stock="+physical(Items.CARROT)+" meals="+meals.size());check(physical(Items.OAK_LOG)==trace.getIntOr("InitialLogs", 0),"finite logs unchanged, no duplicate or lost goods");}
        CompoundTag physical(){var out=new CompoundTag();var stores=new ListTag();var bodies=new ListTag();var s=l.getServer();
            for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++){var t=new CompoundTag();t.store("Settlement", UUIDUtil.CODEC,b.settlementId());t.putInt("Index",i);t.store("Id", UUIDUtil.CODEC,id(b,i));t.putLong("Position",NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i)).asLong());t.put("Items",NaturalVillageEconomyData.items(store(b,i),s.registryAccess()));var context=RightsStateData.get(s).context(id(b,i));if(context!=null)t.put("DefaultOwner",context.defaultBeneficialOwner().save());else t.putString("Registration","unused empty arrival storage has no household context yet");stores.add(t);}
                for(int i=0;i<3;i++){var n=npc(b,i);var t=MinecraftApi.saveEntity(n);t.store("SettlementProof", UUIDUtil.CODEC,b.settlementId());t.putInt("OriginalIndex",i);t.put("PersonalProof",NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));t.put("CargoProof",NaturalVillageEconomyData.items(n.getTransportInventory(),s.registryAccess()));bodies.add(t);}}
            out.put("Stores",stores);out.put("Bodies",bodies);return out;
        }
        void event(String state){var t=new CompoundTag();t.putString("State",state);t.putLong("At",l.getGameTime());var d=deal();if(d!=null)t.put("Deal",d);t.put("Polity",NaturalPolityData.get(l.getServer()).save(new CompoundTag(),l.getServer().registryAccess()));t.put("Physical",physical());t.put("Rights",RightsStateData.get(l.getServer()).save(new CompoundTag(),l.getServer().registryAccess()));t.putInt("Meals",meals.size());events.add(t);Sagadyssey.LOGGER.info("P6_EVENT state={} at={} courier={} position={} carrots={} logs={} meals={} deal={}",state,l.getGameTime(),courier().getCharacterId(),courier().blockPosition(),physical(Items.CARROT),physical(Items.OAK_LOG),meals.size(),d);}
        void snapshot(String state){try{balance();var s=l.getServer();var out=physical();out.putString("Stage",state);out.put("Buyer",NaturalVillageLifeData.get(s).village(buyer.buildingKey()));out.put("Seller",NaturalVillageLifeData.get(s).village(seller.buildingKey()));out.put("Links",links(l));out.put("Trace",trace.copy());out.put("Events",events.copy());out.putLong("Time",l.getGameTime());var ms=new ListTag();meals.stream().sorted().forEach(m->ms.add(StringTag.valueOf(m)));out.put("Meals",ms);
            var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(out,world.resolve("p6-evidence.nbt"));s.saveEverything(true,true,true);
            var dst=world.resolveSibling("world-p6-snapshot");check(Files.notExists(dst),"never overwrite prior checkpoint");try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var to=dst.resolve(world.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);}}
            Sagadyssey.LOGGER.info("P6_CHECKPOINT state={} linkedWorld=true trade={} trace={}",state,deal(),trace);
        }catch(Exception e){throw new IllegalStateException("P6 complete associated world snapshot",e);}}
        void verifyBodies(){for(var b:List.of(seller,buyer))for(int i=0;i<3;i++){var n=npc(b,i);check(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"same original body/person restored");}
            if(restoredBodies||restored==null)return;
            check(warehousesRestored&&loadedBodies.size()==6,"every warehouse checked before first tick and all six original bodies/bags/cargo checked at load before work");
            restoredBodies=true;trace.putBoolean("OriginalPhysicalWorldRestored",true);Sagadyssey.LOGGER.info("P6_RESTORE_PHYSICAL samePersons=true sameStores=true sameCargo=true noReassignment=true");
        }
        void audit(boolean shortage){var s=l.getServer();int expected=shortage?1:2;check(deals().size()==expected,"exact bounded number of real trades");
            for(var t:deals()){check(t.getStringOr("State", "").equals("DONE")&&t.getIntOr("FoodOwed", 0)==0,"old and restored new obligations fully paid");for(String key:List.of("PayRoute","FoodRoute")){var r=route(t,key);var n=NeedRequestData.get(s).request(r.need());var o=LogisticsData.get(s).order(r.id());int q=key.equals("PayRoute")?2:4;check(r.stage()==SupplyFlowData.Stage.DONE&&r.carrierCharacter().equals(courier().getCharacterId())&&n.delivered()==q&&n.reserved()==0&&n.inTransit()==0&&o.pickedUpCount()==q&&o.deliveredCount()==q,"same carrier, actual exact four transfers per trade");check(RightsStateData.get(s).reservationsForToken(r.token(),l.getGameTime()).isEmpty(),"no orphan trade commitment");}}
            check(count(store(seller,1),Items.OAK_LOG)==2*expected&&count(store(buyer,2),Items.OAK_LOG)==0,"seller really received full consideration");
            check(count(store(buyer,1),Items.CARROT)+meals.size()==4*expected&&meals.size()>=1,"separate actual food delivery and subsequent real consumption");
            check(count(courier().getTransportInventory(),Items.CARROT)==0&&count(courier().getTransportInventory(),Items.OAK_LOG)==0,"original cargo emptied only by legal deliveries");
            check(count(store(seller,3),Items.CARROT)==8&&count(store(buyer,3),Items.CARROT)==8,"public reserve preserved or lawfully restored, never confiscated private goods");
            check(rule(seller).getStringOr("State", "").equals("OPEN")&&agreement().getStringOr("State", "").equals("ACTIVE"),"policy recovered and both current approvals active");
            check(trace.getBooleanOr("OldCompletedWhilePaused", false)&&trace.getBooleanOr("OneReapprovalDenied", false),"old duty continues despite new channel pause, one signature never enough");
            for(var n:NeedRequestData.get(s).requests()){check(n.reserved()==0&&n.inTransit()==0,"no orphan quantities");if(!n.status().terminal()&&n.status()!=NeedRequestStatus.DELIVERED)check(l.getGameTime()-n.key().windowStart()<=24000,"local demand remains within its original reconciliation window");}
            for(var r:SupplyFlowData.get(s).routes())check(r.closed(),"no unexplained old logistics backlog");
            for(var b:List.of(seller,buyer)){check(RightsStateData.get(s).context(id(b,1)).defaultBeneficialOwner().equals(home(b)),"private households remain distinct");check(RightsStateData.get(s).context(id(b,3)).defaultBeneficialOwner().equals(SubjectRef.institution(b.settlementId())),"public title remains own institution");}
            var before=agreement();int decisionCount=NaturalPolityData.get(s).decisions().size();
            check(command(buyer,1,"pact propose",buyer,seller)==1&&command(buyer,1,"pact approve",buyer,seller)==1&&command(seller,1,"pact approve",seller,buyer)==1,"idempotent formal proposal and approvals");
            check(agreement().equals(before)&&NaturalPolityData.get(s).decisions().size()==decisionCount,"duplicate decisions do not create a new epoch, signature or record");
            var ledger=SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess());SettlementExchangeData.get(s).tick(s);SettlementExchangeData.get(s).tick(s);check(SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess()).equals(ledger),"completed scheduler replays never create goods or a third deal");
            trace.putInt("FinalTrades",expected);trace.putInt("FinalDebt",0);trace.putInt("FinalHunger",NpcLifeData.get(s).needs(courier().getCharacterId()).hunger());trace.putInt("ReceiptedMeals",meals.size());trace.putLong("Elapsed",l.getGameTime()-trace.getLongOr("InitialAt", 0L));balance();
        }
        void success(String state){event(state);snapshot(state);Sagadyssey.LOGGER.info("P6_ACCEPT phase={} state={} trades={} meals={} policy={} pact={} step={} trace={}",phase(),state,deals().size(),meals.size(),rule(seller).getStringOr("State", ""),agreement(),step(),trace);active=null;h.succeed();}
        int privateLoan(){return RightsStateData.get(l.getServer()).claims().stream().filter(c->c.containerId().equals(id(seller,3))&&c.beneficialOwner().equals(SubjectRef.character(npc(seller,0).getCharacterId()))&&c.fingerprint().equals(new ItemFingerprint("minecraft:carrot",0))).mapToInt(AssetClaim::quantity).sum();}
        void tick(){
            if(!setup){check(h.getTick()<=1000,"frozen initialization deadline");if(h.getTick()<150)return;init();}
            if(courier()==null){check(h.getTick()<1000,"original entity must load, never respawn");return;}verifyBodies();var s=l.getServer();long now=l.getGameTime();check(now-trace.getLongOr("InitialAt", 0L)<=60000,"frozen total observation/fulfillment deadline");
            for(var b:List.of(seller,buyer))check(NaturalVillageEconomyData.get(s).row(b.buildingKey()).getStringOr("Phase", "").equals("NEAR"),"only finite loaded near authority");
            NaturalVillageR1GameTests.observe(l,buyer,meals);NaturalVillageR1GameTests.observe(l,seller,meals);balance();
            trace.putDouble("MaxTravelSquared",Math.max(trace.getDoubleOr("MaxTravelSquared", 0.0),courier().blockPosition().distSqr(BlockPos.of(trace.getLongOr("InitialCourierPosition", 0L)))));
            var d=deal();String observed=step()+":"+rule(seller).getStringOr("State", "")+":"+(agreement()==null?"NONE":agreement().getStringOr("State", ""))+":"+(d==null?"none":d.getStringOr("State", ""));
            if(!observed.equals(seen)){seen=observed;event(observed);}
            if(phase().equals("p6-public-title")){
                check(deals().isEmpty(),"private-title case does not fabricate a bilateral trade");
                if(!trace.getBooleanOr("PrivateReliefIssued", false)){
                    if(!rule(seller).getStringOr("State", "").equals("OPEN"))return;
                    check(count(store(seller,3),Items.CARROT)==10&&rule(seller).getIntOr("FreeCarrots", 0)==8&&privateLoan()==2,"private loan2 is not counted as institution-owned8");
                    check(command(buyer,1,"pact propose",buyer,seller)==1,"one legal proposal does not grant peer inventory access");
                    var diagnostic=new CompoundTag();diagnostic.put("BeforeRights",RightsStateData.get(s).save(new CompoundTag(),s.registryAccess()));diagnostic.put("BeforePhysical",physical());
                    check(command(seller,1,"reserve relief",seller,null)==1,"ordinary official relief uses actual eligible public goods");
                    diagnostic.put("AfterRights",RightsStateData.get(s).save(new CompoundTag(),s.registryAccess()));diagnostic.put("AfterPhysical",physical());diagnostic.putInt("PrivateLoanAfter",privateLoan());
                    try{NbtIo.writeCompressed(diagnostic,s.getWorldPath(LevelResource.ROOT).resolve("p6-private-title-diagnostic.nbt"));}catch(Exception e){throw new IllegalStateException(e);}
                    check(privateLoan()==2,"public relief must retain the original private loan2 and spend only institution-owned food");
                    check(count(store(seller,3),Items.CARROT)==8&&count(store(seller,1),Items.CARROT)==18,"exact institutional2 transferred, no loan appropriation");
                    trace.putBoolean("PrivateReliefIssued",true);trace.putInt("PrivateLoanRetained",2);trace.putInt("InstitutionReliefSpent",2);trace.putLong("PrivateReliefAt",now);event("PRIVATE_TITLE_PRESERVED");return;
                }
                if(now-trace.getLongOr("PrivateReliefAt", 0L)<20)return;
                check(rule(seller).getStringOr("State", "").equals("CLOSED")&&rule(seller).getIntOr("FreeCarrots", 0)==6&&agreement().getStringOr("State", "").equals("PAUSED")&&privateLoan()==2,"policy follows actual institutional6, not physical8 containing private2");
                success("PRIVATE_TITLE");return;
            }
            if(phase().equals("p6-public-held")){
                check(deals().isEmpty(),"promised public goods never counted as free");
                if(now-trace.getLongOr("InitialAt", 0L)<1200)return;
                if(!trace.getBooleanOr("HeldDonation", false)){
                    check(rule(seller).getStringOr("State", "").equals("CLOSED")&&rule(seller).getIntOr("FreeCarrots", 0)==0&&count(store(seller,3),Items.CARROT)==8,"physical8 but free0 under original public obligation");
                    check(command(buyer,1,"pact propose",buyer,seller)==1&&command(seller,1,"pact approve",seller,buyer)==0,"proposal cannot bypass unavailable public reserve");
                    check(command(seller,0,"reserve donate",seller,null)==1,"voluntary donor uses formal ordinary product entry");trace.putBoolean("HeldDonation",true);trace.putLong("HeldDonationAt",now);event("HELD_RESERVE_DONATION");return;
                }
                if(now-trace.getLongOr("HeldDonationAt", 0L)<200)return;
                check(rule(seller).getStringOr("State", "").equals("CLOSED")&&rule(seller).getIntOr("FreeCarrots", 0)==2&&count(store(seller,3),Items.CARROT)==10,"donation adds real2 but existing8 not erased");
                check(RightsStateData.get(s).reservedQuantity(id(seller,3),new ItemFingerprint("minecraft:carrot",0),null,now)==8,"prior obligation retained exactly");trace.putInt("PriorPublicObligation",8);trace.putInt("FreePublicCarrots",2);success("PUBLIC_HELD");return;
            }
            if(step()==0){
                check(deals().isEmpty(),"rules alone do not replace bilateral consent");
                if(!rule(seller).getStringOr("State", "").equals("OPEN")||!rule(buyer).getStringOr("State", "").equals("OPEN"))return;
                check(command(seller,0,"reserve enable",seller,null)==0&&command(buyer,1,"reserve enable",seller,null)==0,"unauthorized resident or other office cannot adopt this policy");
                check(execute(s.createCommandSourceStack().withSuppressedOutput(),"saga politics reserve enable "+seller.settlementId())==0,"console permission cannot spoof an entity approval");
                check(command(buyer,1,"pact propose",buyer,seller)==1,"own proposer product action");var pact=agreement();
                check(pact.read("SignedA", UUIDUtil.CODEC).isPresent()&&!pact.read("SignedB", UUIDUtil.CODEC).isPresent()&&pact.read("SignedA", UUIDUtil.CODEC).orElseThrow().equals(courier().getCharacterId()),"only own proposal signature, no automatic peer approval");
                check(command(buyer,1,"pact propose",buyer,seller)==1&&agreement().equals(pact),"duplicate proposal is exact no-op");
                check(command(buyer,1,"pact approve",seller,buyer)==0&&command(seller,2,"pact approve",seller,buyer)==0,"cannot spoof peer or use mere membership");
                trace.putLong("ProposedAt",now);trace.putBoolean("AuthorityRejections",true);step(1);event("ONE_INITIAL_SIGNATURE");return;
            }
            if(step()==1){check(deals().isEmpty()&&agreement().getStringOr("State", "").equals("PENDING"),"single initial approval blocks real new proposal");if(now-trace.getLongOr("ProposedAt", 0L)<600)return;
                check(NaturalPolityData.get(s).site(buyer.settlementId()).getCompoundOrEmpty("LastTradeRefusal").getLongOr("At", 0L)>=trace.getLongOr("ProposedAt", 0L),"normal business entry recorded refusal");
                check(command(seller,1,"pact approve",seller,buyer)==1&&agreement().getStringOr("State", "").equals("ACTIVE"),"separate peer actor completes acceptance");trace.putBoolean("InitialBilateralAccepted",true);step(2);event("BILATERAL_ACTIVE");return;
            }
            d=deal();if(d==null)return;trace.store("Deal", UUIDUtil.CODEC,d.read("Id", UUIDUtil.CODEC).orElseThrow());if(!trace.read("FirstDeal", UUIDUtil.CODEC).isPresent())trace.store("FirstDeal", UUIDUtil.CODEC,deals().getFirst().read("Id", UUIDUtil.CODEC).orElseThrow());
            var first=deals().getFirst();var pay=route(first,"PayRoute");var food=route(first,"FoodRoute");
            if(step()==2&&food!=null&&food.stage()==SupplyFlowData.Stage.CARRYING){
                check(pay.stage()==SupplyFlowData.Stage.DONE&&count(courier().getTransportInventory(),Items.CARROT)==4&&first.getIntOr("FoodOwed", 0)==4,"real original debt4 and four food in actual cargo after logs paid");
                if(!trace.getBooleanOr("RestrictionTriggered", false)){
                    if(manual()){check(command(buyer,1,"pact pause",buyer,seller)==1,"one side explicitly withdraws own consent");var paused=agreement();check(command(buyer,1,"pact pause",buyer,seller)==1&&agreement().equals(paused),"duplicate pause never creates another epoch");}
                    else{check(count(store(seller,3),Items.CARROT)==8&&command(seller,1,"reserve relief",seller,null)==1,"existing actual relief consumes two public food");check(count(store(seller,3),Items.CARROT)==6&&command(seller,1,"reserve relief",seller,null)==1&&count(store(seller,3),Items.CARROT)==6,"repeat relief keeps old receipt, not another issue");}
                    trace.putBoolean("RestrictionTriggered",true);trace.putLong("RestrictionTriggeredAt",now);event("REAL_PARTIAL_TRADE_AND_RESTRICTION");
                }
                if(!agreement().getStringOr("State", "").equals("PAUSED")||!manual()&&!rule(seller).getStringOr("State", "").equals("CLOSED"))return;
                check(first.getIntOr("FoodOwed", 0)==4&&NeedRequestData.get(s).request(food.need()).inTransit()==4,"policy pause retained all old custody/debt facts");trace.putLong("PausedAt",now);step(3);
                if(phase().equals("p6-pause-save")){snapshot("PAUSED_CARRY");active=null;h.succeed();return;}event("OLD_CARGO_SURVIVES_PAUSE");
            }
            if(step()==3){check(agreement().getStringOr("State", "").equals("PAUSED"),"new channel remains paused while old normal route continues");if(!first.getStringOr("State", "").equals("DONE"))return;
                check(first.getIntOr("FoodOwed", 0)==0&&count(courier().getTransportInventory(),Items.CARROT)==0,"old debt really settled, not deleted at policy change");trace.putBoolean("OldCompletedWhilePaused",true);trace.putLong("FirstCompletedAt",first.getLongOr("Completed", 0L));step(4);event("OLD_TRADE_DONE_WHILE_CHANNEL_PAUSED");
            }
            if(step()==4){check(deals().size()==1,"no fresh promise through paused channel");if(now<trace.getLongOr("FirstCompletedAt", 0L)+24000)return;
                if(!trace.contains("NewRefusalSince"))trace.putLong("NewRefusalSince",now);if(now-trace.getLongOr("NewRefusalSince", 0L)<600)return;
                var refusal=NaturalPolityData.get(s).site(buyer.settlementId()).getCompoundOrEmpty("LastTradeRefusal");check(refusal.getLongOr("At", 0L)>=trace.getLongOr("NewRefusalSince", 0L),"otherwise eligible real new trade refused at normal entry after old cooldown");trace.putString("RestrictionRefusal",refusal.getStringOr("Reason", ""));
                if(!manual()){
                    check(command(seller,1,"reserve relief",seller,null)==0&&count(store(seller,3),Items.CARROT)==6,"next-day new public issue rejected by real governance decision");
                    check(command(seller,0,"reserve donate",seller,null)==1&&count(store(seller,3),Items.CARROT)==8&&count(npc(seller,0).getPersonalInventory(),Items.CARROT)==2,"own donor voluntarily transfers finite two, not confiscation");
                    check(command(seller,0,"reserve donate",seller,null)==1&&count(store(seller,3),Items.CARROT)==8&&count(npc(seller,0).getPersonalInventory(),Items.CARROT)==2,"duplicate donation uses original decision/receipt");trace.putBoolean("VoluntaryDonation",true);
                }
                trace.putLong("ResourceRestoreAt",now);step(5);event("RESTORATION_INPUTS");
            }
            if(step()==5){check(deals().size()==1,"restored resources do not substitute required approvals");if(!rule(seller).getStringOr("State", "").equals("OPEN"))return;
                check(manual()||now-trace.getLongOr("ResourceRestoreAt", 0L)>=200,"recovery requires original fixed continuous window");
                check(command(seller,1,"pact approve",seller,buyer)==1&&!agreement().getStringOr("State", "").equals("ACTIVE"),"first reapproval cannot replace other side");trace.putLong("OneReapprovalAt",now);step(6);event("ONE_REAPPROVAL");return;
            }
            if(step()==6){check(deals().size()==1&&!agreement().getStringOr("State", "").equals("ACTIVE"),"one-signature epoch remains blocked for real new trades");if(now-trace.getLongOr("OneReapprovalAt", 0L)<400)return;
                trace.putBoolean("OneReapprovalDenied",true);step(7);if(phase().equals("p6-approval-save")){snapshot("ONE_APPROVAL");active=null;h.succeed();return;}
            }
            if(step()==7){check(command(buyer,1,"pact approve",buyer,seller)==1&&agreement().getStringOr("State", "").equals("ACTIVE"),"other side independently reapproves current epoch");trace.putLong("ReopenedAt",now);step(8);event("BOTH_REAPPROVED");}
            if(step()==8){
                if(phase().equals("p6-no-stock")){check(deals().size()==1&&agreement().getStringOr("State", "").equals("ACTIVE")&&count(store(buyer,2),Items.OAK_LOG)==0,"active agreement does not create unavailable consideration");if(now-trace.getLongOr("ReopenedAt", 0L)<1200)return;
                    check(NaturalPolityData.get(s).site(buyer.settlementId()).getCompoundOrEmpty("LastTradeRefusal").getStringOr("Reason", "").contains("两块原木"),"independent source/rights/availability reason retained");audit(true);success("NO_STOCK");return;
                }
                if(deals().size()!=2||!deals().getLast().getStringOr("State", "").equals("DONE"))return;
                trace.store("SecondDeal", UUIDUtil.CODEC,deals().getLast().read("Id", UUIDUtil.CODEC).orElseThrow());check(!trace.read("FirstDeal", UUIDUtil.CODEC).orElseThrow().equals(trace.read("SecondDeal", UUIDUtil.CODEC).orElseThrow()),"new lawful transaction has its own identity");trace.putLong("BothDoneAt",now);step(9);event("RESTORED_REAL_TRADE_DONE");
            }
            if(step()==9){if(now-trace.getLongOr("BothDoneAt", 0L)<400)return;audit(false);success("DONE");return;}
            if(now%2000==0)Sagadyssey.LOGGER.info("P6_PROGRESS phase={} elapsed={} step={} policy={} pact={} deals={} meals={}",phase(),now-trace.getLongOr("InitialAt", 0L),step(),rule(seller),agreement(),deals().stream().map(x->x.getStringOr("State", "")).toList(),meals.size());
        }
    }
}
