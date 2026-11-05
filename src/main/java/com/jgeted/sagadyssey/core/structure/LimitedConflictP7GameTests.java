package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.combat.*;
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
@GameTestHolder("sagadyssey_p7") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class LimitedConflictP7GameTests {
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_p7:empty");
    private static final Set<UUID> paused=new HashSet<>();
    private static final Set<UUID> loadedBodies=new HashSet<>();
    private static boolean warehousesRestored;
    private static CompoundTag restored;
    private static Case active;
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    private static void check(boolean v,String why){if(!v)throw new IllegalStateException(why);}
    @net.neoforged.bus.api.SubscribeEvent public static void spawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!(e.getLevel() instanceof ServerLevel l)||!(l.getServer() instanceof GameTestServer)||!phase().startsWith("p7-")||!(e.getEntity() instanceof NpcBase n))return;
        if(!phase().endsWith("resume")){if(paused.add(n.getUUID()))n.setNoAi(true);return;}
        if(loadedBodies.contains(n.getUUID()))return;
        try{var original=restored!=null?restored:NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("p7-evidence.nbt"),NbtAccounter.unlimitedHeap());
            var t=original.getList("Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("UUID").equals(n.getUUID())).findFirst().orElseThrow();
            check(n.getCharacterId().equals(NaturalVillageLifeData.person(NaturalVillageLifeData.decode(original.getCompound(t.getUUID("SettlementProof").equals(original.getCompound("Trace").getUUID("BuyerSettlement"))?"Buyer":"Seller")),t.getInt("OriginalIndex"))),"same character at original body load");
            check(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.getServer().registryAccess()).equals(t.getList("PersonalProof",Tag.TAG_COMPOUND))&&NaturalVillageEconomyData.items(n.getTransportInventory(),l.getServer().registryAccess()).equals(t.getList("CargoProof",Tag.TAG_COMPOUND))&&n.isNoAi()==t.getBoolean("NoAI")&&n.getMainHandItem().saveOptional(l.getServer().registryAccess()).equals(t.get("HandProof")),"original physical bags/cargo and AI state exactly at entity load, before work");
            loadedBodies.add(n.getUUID());Sagadyssey.LOGGER.info("P7_BODY_LOAD original={} samePersonal=true sameCargo=true sameAI=true beforeWork=true",n.getUUID());
        }catch(Exception x){throw new IllegalStateException("P7 original entity load proof required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer)||!phase().startsWith("p7-"))return;
        try{var s=e.getServer();var l=s.overworld();paused.clear();restored=null;active=null;
            l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,s);
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,s);
            l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if(phase().endsWith("resume")){
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p7-evidence.nbt"),NbtAccounter.unlimitedHeap());
                var actual=links(l);NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("p7-restored-links.nbt"));
                Sagadyssey.LOGGER.info("P7_RESTORE_DIFFERENCES fields={}",actual.getAllKeys().stream().filter(k->!actual.get(k).equals(restored.getCompound("Links").get(k))).toList());
                var expected=restored.getCompound("Links").copy();
                // The existing region loader explicitly resets these two observation flags.
                // Compare that exact documented load result; preserve Phase, stocks and every other field.
                for(var entry:expected.getCompound("Economy").getList("Regions",Tag.TAG_COMPOUND)){var row=(CompoundTag)entry;row.putBoolean("ObservedAway",true);row.putBoolean("ReturnRequested",false);}
                check(comparable(actual).equals(comparable(expected)),"all associated ledgers and exact existing loader flags must match before first tick");
                var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLong("Time")+1);s.getWorldData().overworldData().setDayTime(saved.getLong("DayTime")+1);
                corridor(l,NaturalVillageLifeData.decode(restored.getCompound("Buyer")),NaturalVillageLifeData.decode(restored.getCompound("Seller")));
                for(var entry:restored.getList("Stores",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;var c=l.getBlockEntity(BlockPos.of(t.getLong("Position")));check(c instanceof Container&&NaturalVillageEconomyData.items((Container)c,s.registryAccess()).equals(t.getList("Items",Tag.TAG_COMPOUND)),"original physical warehouse before first tick");}
                warehousesRestored=true;Sagadyssey.LOGGER.info("P7_STORES_LOAD beforeTicks=true sameStores=true");
                Sagadyssey.LOGGER.info("P7_RESTORE beforeTicks=true linkedWorld=true stage={} originalTrade={}",restored.getString("Stage"),restored.getCompound("Trace").getUUID("Deal"));
            }
        }catch(Exception x){throw new IllegalStateException("P7 coherent original world required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void update(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(active==null||!phase().startsWith("p7-"))return;var c=active;
        try{c.tick();}catch(RuntimeException failure){active=null;c.h.runAtTickTime(c.h.getTick()+1,()->{throw failure;});}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!phase().startsWith("p7-"))return List.of();
        return List.of(new TestFunction("p5",phase().replace('-','_'),EMPTY.toString(),68000,0,true,h->{active=new Case(h);h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint 68000");}));}
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
        t.put("Conflict",NaturalConflictData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Encounters",CombatEncounterData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Injuries",InjuryData.get(s).save(new CompoundTag(),s.registryAccess()));
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
        final Set<String> meals=new HashSet<>();final CompoundTag trace=new CompoundTag();final ListTag events=new ListTag();
        boolean setup=false,restoredBodies=false;String seen="";
        Case(GameTestHelper helper){h=helper;l=h.getLevel();seller=restored==null?NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2):NaturalVillageLifeData.decode(restored.getCompound("Seller"));buyer=restored==null?NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2):NaturalVillageLifeData.decode(restored.getCompound("Buyer"));corridor(l,seller,buyer);if(restored!=null){setup=true;trace.merge(restored.getCompound("Trace"));events.addAll(restored.getList("Events",Tag.TAG_COMPOUND));for(var m:restored.getList("Meals",Tag.TAG_STRING))meals.add(m.getAsString());}}
        NpcBase npc(NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);} NpcBase courier(){return npc(buyer,1);}
        UUID id(NaturalVillageBuilding b,int i){return NaturalVillageEconomyData.storeId(b,i);} SubjectRef home(NaturalVillageBuilding b){return SubjectRef.household(NaturalVillageLifeData.home(b));}
        Container store(NaturalVillageBuilding b,int i){var p=NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i));check(!NaturalVillageEconomyData.protects(l.getServer(),b.dimension(),p),"near authority only");check(l.getBlockEntity(p) instanceof Container,"real original store");return (Container)l.getBlockEntity(p);}
        CompoundTag deal(){var ds=SettlementExchangeData.get(l.getServer()).deals();check(ds.size()<=1,"one real old deal");return ds.isEmpty()?null:ds.getFirst();}
        CompoundTag war(){var ds=NaturalConflictData.get(l.getServer()).disputes();check(ds.size()<=1,"one finite original encounter");return ds.isEmpty()?null:ds.getFirst();}
        CompoundTag consent(){return LimitedConflictGovernance.record(l.getServer(),trace.getUUID("Deal"));}
        int step(){return trace.getInt("Step");}void step(int x){trace.putInt("Step",x);}
        int execute(net.minecraft.commands.CommandSourceStack source,String command){try{return l.getServer().getCommands().getDispatcher().execute(command,source);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException denied){return 0;}}
        int command(NaturalVillageBuilding actor,int index,String action,NaturalVillageBuilding self,NaturalVillageBuilding peer){var n=npc(actor,index);return execute(l.getServer().createCommandSourceStack().withEntity(n).withLevel(l).withPosition(n.position()).withPermission(0).withSuppressedOutput(),"saga politics "+action+" "+self.settlementId()+(peer==null?"":" "+peer.settlementId())+(action.startsWith("conflict ")?" "+trace.getUUID("Deal"):""));}
        void transfer(SubjectRef actor,TransferIntent intent,UUID from,Container source,UUID to,Container target,int n){var s=l.getServer();var r=RightsStateData.get(s);var req=new TransferRequest(UUID.randomUUID(),actor,intent,List.of(new TransferLeg(from,to,new ItemFingerprint("minecraft:carrot",0),n)),List.of(new ExpectedRevision(from,r.context(from).revision()),new ExpectedRevision(to,r.context(to).revision())),"P7 declared bounded preparatory actual allocation, no debt/damage/peace writes",l.getGameTime());var receipt=new TransferService(r,TransferLedgerData.get(s)).submit(req,Map.of(from,new TransferEndpoint(from,source),to,new TransferEndpoint(to,target)),l.getGameTime());check(receipt.successful()&&receipt.lawful(),"original lawful transfer: "+receipt.reason());}
        void init(){var s=l.getServer();for(var b:List.of(seller,buyer)){check(NaturalPolityData.get(s).authorized(s,b,npc(b,1).getCharacterId()),"original current office authority");store(b,3).setItem(2,new ItemStack(Items.CARROT,8));var n=npc(b,2);for(var p:BlockPos.betweenClosed(n.getWorkZoneMin(),n.getWorkZoneMax()))if(l.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)||l.getBlockState(p).is(net.minecraft.tags.BlockTags.LEAVES))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);check(command(b,1,"reserve enable",b,null)==1,"formal initial voluntary P6 enrollment");}
            transfer(SubjectRef.character(npc(seller,0).getCharacterId()),TransferIntent.GIFT,ContainerIds.npcPersonal(npc(seller,0).getCharacterId()),npc(seller,0).getPersonalInventory(),id(seller,1),store(seller,1),16);
            transfer(SubjectRef.character(npc(buyer,0).getCharacterId()),phase().equals("p7-loan-rations")?TransferIntent.LOAN:TransferIntent.GIFT,ContainerIds.npcPersonal(npc(buyer,0).getCharacterId()),npc(buyer,0).getPersonalInventory(),id(buyer,1),store(buyer,1),2);
            store(buyer,2).setItem(1,new ItemStack(Items.OAK_LOG,4));trace.putLong("InitialAt",l.getGameTime());trace.putInt("InitialCarrots",48);trace.putInt("InitialLogs",4);trace.putInt("CorridorChunks",corridor(l,seller,buyer));trace.putUUID("BuyerSettlement",buyer.settlementId());trace.putUUID("SellerSettlement",seller.settlementId());trace.putUUID("Courier",courier().getCharacterId());trace.putUUID("CourierBody",courier().getUUID());courier().setNoAi(false);setup=true;event("INITIAL_CONFIGURATION");}
        int physical(Item item){int total=0;for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++)total+=count(store(b,i),item);for(int i=0;i<3;i++){var n=npc(b,i);total+=count(n.getPersonalInventory(),item)+count(n.getTransportInventory(),item);}}return total;}
        int consumed(){var t=war();return t==null?0:t.getInt("ConsumedCarrots");}
        void balance(){check(physical(Items.CARROT)+meals.size()+consumed()==48,"48 = actual stock + normal meals + actual action rations; stock="+physical(Items.CARROT)+" meals="+meals.size()+" rations="+consumed());check(physical(Items.OAK_LOG)==4,"no war loot/free goods; four original trade logs remain");}
        CompoundTag physical(){var out=new CompoundTag();var stores=new ListTag();var bodies=new ListTag();var s=l.getServer();for(var b:List.of(seller,buyer)){for(int i=0;i<5;i++){var t=new CompoundTag();t.putUUID("Settlement",b.settlementId());t.putInt("Index",i);t.putUUID("Id",id(b,i));t.putLong("Position",NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i)).asLong());t.put("Items",NaturalVillageEconomyData.items(store(b,i),s.registryAccess()));stores.add(t);}for(int i=0;i<3;i++){var n=npc(b,i);var t=n.saveWithoutId(new CompoundTag());t.putUUID("SettlementProof",b.settlementId());t.putInt("OriginalIndex",i);t.put("PersonalProof",NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));t.put("CargoProof",NaturalVillageEconomyData.items(n.getTransportInventory(),s.registryAccess()));t.put("HandProof",n.getMainHandItem().saveOptional(s.registryAccess()));bodies.add(t);}}out.put("Stores",stores);out.put("Bodies",bodies);return out;}
        void event(String label){var t=new CompoundTag();t.putString("State",label);t.putLong("At",l.getGameTime());t.put("Links",links(l));t.put("Physical",physical());events.add(t);Sagadyssey.LOGGER.info("P7_EVENT label={} time={} step={} war={} consent={} trace={}",label,l.getGameTime(),step(),war(),trace.hasUUID("Deal")?consent():null,trace);}
        void snapshot(String state){try{balance();var s=l.getServer();var out=physical();out.putString("Stage",state);out.put("Buyer",NaturalVillageLifeData.get(s).village(buyer.buildingKey()));out.put("Seller",NaturalVillageLifeData.get(s).village(seller.buildingKey()));out.put("Links",links(l));out.put("Trace",trace.copy());out.put("Events",events.copy());out.putLong("Time",l.getGameTime());var ms=new ListTag();meals.stream().sorted().forEach(m->ms.add(StringTag.valueOf(m)));out.put("Meals",ms);var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(out,world.resolve("p7-evidence.nbt"));s.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();var dst=world.resolveSibling("world-p7-snapshot");check(Files.notExists(dst),"never overwrite checkpoint");try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var to=dst.resolve(world.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);}}Sagadyssey.LOGGER.info("P7_CHECKPOINT state={} linkedWorld=true trace={}",state,trace);}catch(Exception e){throw new IllegalStateException("P7 full associated world",e);}}
        void success(String state){event(state);snapshot(state);Sagadyssey.LOGGER.info("P7_ACCEPT phase={} state={} trace={}",phase(),state,trace);active=null;h.succeed();}
        void bodies(){for(var b:List.of(seller,buyer))for(int i=0;i<3;i++)check(npc(b,i)!=null&&npc(b,i).getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"original six living body/person identities, no replacement");if(restored==null||restoredBodies)return;check(warehousesRestored&&loadedBodies.size()==6,"all actual original persons loaded and before-tick warehouses verified");restoredBodies=true;trace.putBoolean("OriginalPhysicalWorldRestored",true);Sagadyssey.LOGGER.info("P7_RESTORE_PHYSICAL samePersons=true sameStores=true sameCargo=true sameHands=true noReassignment=true");}
        void auditDebt(){var s=l.getServer();var d=deal();check(d!=null&&d.getInt("FoodOwed")==4&&d.getString("State").equals("PAY"),"old actual unpaid food4 remains, not cleared by war or peace");var route=SupplyFlowData.get(s).route(d.getUUID("PayRoute"));check(route.stage()==SupplyFlowData.Stage.DONE&&NeedRequestData.get(s).request(route.need()).delivered()==2&&count(store(seller,1),Items.OAK_LOG)==2,"actual original prepayment still received by seller");check(!d.hasUUID("FoodRoute")&&count(store(seller,1),Items.CARROT)<4,"owed food blocked by real insufficient source, not fictional delivery");check(CharterTradeGovernance.pact(s,buyer.settlementId(),seller.settlementId()).getString("State").equals("PAUSED"),"peace cannot restore P6 new trade approval");trace.putLong("DebtAge",l.getGameTime()-d.getLong("Created"));trace.putString("DebtReason","卖方原生活户仍欠4食物，原木2已付；本源实物不足4，旧义务保留，不强征私人14或公共8；由卖方依法补给后原P5渠道续接");}
        void barrier(){var origin=buyer.buildingOrigin();for(int x=-2;x<=25;x++)for(int z=-2;z<=25;z++)if(x==-2||x==25||z==-2||z==25)for(int y=1;y<=5;y++)l.setBlock(origin.offset(x,y,z),Blocks.BEDROCK.defaultBlockState(),3);}
        void tick(){var s=l.getServer();long now=l.getGameTime();if(!setup){check(h.getTick()<=1600,"frozen initialization bound");if(h.getTick()<150)return;init();}bodies();check(now-trace.getLong("InitialAt")<=64000,"frozen total deadline");NaturalVillageR1GameTests.observe(l,buyer,meals);NaturalVillageR1GameTests.observe(l,seller,meals);balance();for(var b:List.of(seller,buyer))check(NaturalVillageEconomyData.get(s).row(b.buildingKey()).getString("Phase").equals("NEAR"),"loaded near only");
            if(now%4000==0)Sagadyssey.LOGGER.info("P7_PROGRESS phase={} time={} step={} trace={} war={} actors={}",phase(),now,step(),trace,war(),List.of(npc(buyer,2).getWorkActivity(),npc(seller,2).getWorkActivity()));
            if(step()==0){if(!NaturalPolityData.get(s).site(seller.settlementId()).getCompound("ReserveRule").getString("State").equals("OPEN"))return;check(command(buyer,1,"pact propose",buyer,seller)==1&&command(seller,1,"pact approve",seller,buyer)==1,"existing independently approved P6 actual trade");step(1);}
            if(step()==1){var d=deal();if(d==null||!d.hasUUID("PayRoute"))return;var r=SupplyFlowData.get(s).route(d.getUUID("PayRoute"));if(r.stage()!=SupplyFlowData.Stage.CARRYING)return;trace.putUUID("Deal",d.getUUID("Id"));transfer(home(seller),TransferIntent.GIFT,id(seller,1),store(seller,1),ContainerIds.npcPersonal(npc(seller,0).getCharacterId()),npc(seller,0).getPersonalInventory(),phase().equals("p7-no-rations")?16:14);trace.putBoolean("PreparatoryEmergencyAllocation",true);step(2);event("ACTUAL_LOGS_IN_TRANSIT_PREPARATORY_FOOD_ALLOCATION");}
            if(step()==2){if(deal().getInt("FoodOwed")!=4)return;courier().setNoAi(true);NpcLifeData.get(s).setHunger(courier().getCharacterId(),950,now);NpcLifeData.get(s).setHunger(npc(buyer,0).getCharacterId(),950,now);check(command(buyer,1,"pact pause",buyer,seller)==1,"independent new-trade suspension");for(var b:List.of(seller,buyer))npc(b,2).setNoAi(false);
                check(command(buyer,0,"conflict prepare",buyer,seller)==0&&command(buyer,1,"conflict prepare",seller,buyer)==0&&execute(s.createCommandSourceStack().withSuppressedOutput(),"saga politics conflict prepare "+buyer.settlementId()+" "+seller.settlementId()+" "+trace.getUUID("Deal"))==0,"ordinary resident, spoofed peer and console cannot authorize war");
                int left=command(buyer,1,"conflict prepare",buyer,seller),right=command(seller,1,"conflict prepare",seller,buyer);boolean negative=phase().equals("p7-no-rations")||phase().equals("p7-loan-rations");check(negative?(left==0||right==0):(left==1&&right==1),"independent current representatives prepare only legal rations");trace.putBoolean("AuthorityRejections",true);trace.putLong("ObservationAt",now);if(negative){step(20);event("LEGAL_RATION_REFUSAL");return;}
                var before=consent();check(command(buyer,1,"conflict prepare",buyer,seller)==1&&command(seller,1,"conflict prepare",seller,buyer)==1&&consent().equals(before),"repeat preparation adds no reservation/approval");if(phase().equals("p7-blocked"))barrier();step(3);event("BOTH_PREPARED_NO_DECLARATION");}
            if(step()==20){check(war()==null&&consumed()==0,"missing/foreign ration never creates an army or free food");if(now-trace.getLong("ObservationAt")<1000)return;var c=consent();for(var b:List.of(seller,buyer))if(c.hasUUID(b==buyer?"PreparedA":"PreparedB"))check(command(b,1,"conflict cancel",b,b==buyer?seller:buyer)==1,"formal cancellation releases only own unused mandate");check(RightsStateData.get(s).reservationsForToken("dispute-rations:"+trace.getUUID("Deal"),now).isEmpty(),"unused preparations not orphaned");auditDebt();success("RATION_REFUSED");return;}
            if(step()==3){check(war()==null,"automatic old trigger cannot bypass undeclared new mandate");if(now-deal().getLong("Created")<24000||Math.floorMod(l.getDayTime(),24000)<12500)return;boolean sleeping=true;for(var b:List.of(buyer,seller)){var n=npc(b,2);var p=NpcBrainData.get(s).active(n.getUUID()).orElse(null);sleeping&=n.isSleeping()&&p!=null&&p.currentStep().params().getOrDefault("operation","").equals("SLEEP");}if(!sleeping)return;
                for(var b:List.of(buyer,seller)){String side=b==buyer?"A":"B";var n=npc(b,2);trace.putUUID("OldSleep"+side,NpcBrainData.get(s).active(n.getUUID()).orElseThrow().id());trace.putInt("FatigueBefore"+side,NpcLifeData.get(s).record(n.getCharacterId()).fatigue());trace.putLong("Bed"+side,NpcLifeData.get(s).record(n.getCharacterId()).bedPosition().asLong());trace.putLong("ArmyStartPosition"+side,n.blockPosition().asLong());}
                check(command(seller,1,"conflict declare",seller,buyer)==0&&command(buyer,0,"conflict declare",buyer,seller)==0,"defender cannot impersonate injured creditor or member office");check(command(buyer,1,"conflict declare",buyer,seller)==1&&war()!=null&&war().getString("State").equals("ACTIVE"),"real overdue obligation through legitimate source product entry");
                check(consumed()==4&&count(store(seller,1),Items.CARROT)==0&&count(store(buyer,1),Items.CARROT)==0,"actual two legal rations per side deducted");check(!npc(buyer,2).isSleeping()&&!npc(seller,2).isSleeping(),"real sleep interrupted by normal mobilization cancellation");var beforeRepeat=war();var consentBeforeRepeat=consent();check(command(buyer,1,"conflict declare",buyer,seller)==1&&war().equals(beforeRepeat)&&consent().equals(consentBeforeRepeat)&&consumed()==4,"duplicate real declaration adds no army, consumption or decision");trace.putLong("MobilizedAt",now);trace.putBoolean("SleepInterrupted",true);step(4);event("REAL_ACTIVE_CONFLICT");if(phase().equals("p7-active-save")){snapshot("ACTIVE");active=null;h.succeed();return;}}
            if(step()==4){var t=war();for(var b:List.of(buyer,seller)){String side=b==buyer?"A":"B";trace.putDouble("Travel"+side,Math.max(trace.getDouble("Travel"+side),npc(b,2).blockPosition().distSqr(BlockPos.of(trace.getLong("ArmyStartPosition"+side)))));}
                if(phase().equals("p7-withdraw")){check(command(buyer,1,"conflict withdraw",buyer,seller)==1,"current own representative can withdraw own action");check(!war().getBoolean("Returning")&&!consent().getBoolean("Truce")&&consumed()==4,"withdrawal not bilateral acceptance, no fictitious return/refund");auditDebt();success("WITHDRAWN");return;}
                if(phase().equals("p7-blocked")){if(now-t.getLong("Started")<1200)return;check(t.getString("State").equals("ACTIVE")&&CombatEncounterData.get(s).encounter(t.getUUID("Encounter")).events().isEmpty(),"real initial barrier prevents hostile contact; no fabricated injury/success");trace.putBoolean("BlockedSafeExit",true);step(5);}
                else {if(!t.getString("State").equals("CEASEFIRE"))return;var e=CombatEncounterData.get(s).encounter(t.getUUID("Encounter"));check(t.getInt("Wounds")+t.getInt("Deaths")>0&&e.status()==EncounterStatus.ENDED,"original normal NPC attack caused real registered injury, not a numeric war win");check(trace.getDouble("TravelA")>9||trace.getDouble("TravelB")>9,"original soldier actually moved");trace.putInt("RealWounds",t.getInt("Wounds"));trace.putInt("RealDeaths",t.getInt("Deaths"));step(5);event("SAFETY_STOP_NOT_BILATERAL_AGREEMENT");}}
            if(step()==5){if(now-war().getLong("Started")<1200)return;check(!consent().getBoolean("Truce"),"operational stop not forged bilateral agreement");check(command(buyer,0,"conflict peace-propose",buyer,seller)==0&&command(buyer,1,"conflict peace-accept",seller,buyer)==0&&command(buyer,1,"conflict peace-accept",buyer,seller)==0,"wrong person, forged peer and self-accept without peer proposal denied");check(command(buyer,1,"conflict peace-propose",buyer,seller)==1,"only own real formal proposal");check(consent().hasUUID("PeaceA")&&!consent().hasUUID("PeaceB")&&!consent().getBoolean("Truce"),"single side is not a truce");trace.putLong("OnePeaceAt",now);step(6);event("ONE_PEACE_SIGNATURE");if(phase().equals("p7-truce-save")){snapshot("ONE_PEACE");active=null;h.succeed();return;}}
            if(step()==6){if(now-trace.getLong("OnePeaceAt")<200)return;check(!consent().getBoolean("Truce"),"one signature cannot bind both parties");check(command(seller,1,"conflict peace-accept",seller,buyer)==1&&consent().getBoolean("Truce"),"independent current peer formally accepts; ordinary stop clears hostile targets");var prior=consent();check(command(seller,1,"conflict peace-accept",seller,buyer)==1&&command(buyer,1,"conflict peace-propose",buyer,seller)==1&&consent().equals(prior),"repeated signatures exact no-op");check(command(buyer,1,"conflict declare",buyer,seller)==0,"unresolved old cause cannot immediately rearm under formal truce");trace.putLong("TruceAt",now);step(7);event("BILATERAL_TRUCE");}
            if(step()==7){check(consent().getBoolean("Truce")&&war().getString("State").equals("CEASEFIRE"),"stable formal truce persists");for(var b:List.of(buyer,seller))check(npc(b,2).getTarget()==null&&npc(b,2).getHostileTargetCandidate()==null,"no old queued hostile target or ongoing chase after treaty");if(war().getBoolean("Returning"))return;if(phase().equals("p7-blocked")){auditDebt();if(now-trace.getLong("TruceAt")>=2400)success("BLOCKED_EXIT");return;}
                for(var b:List.of(buyer,seller)){String side=b==buyer?"A":"B";var n=npc(b,2);var p=NpcBrainData.get(s).active(n.getUUID()).orElse(null);if(p!=null&&p.currentStep().params().getOrDefault("operation","").equals("SLEEP")&&n.isSleeping()&&!p.id().equals(trace.getUUID("OldSleep"+side))){if(!trace.hasUUID("ResumedSleep"+side)){trace.putUUID("ResumedSleep"+side,p.id());trace.putLong("ResumedSleepAt"+side,now);trace.putInt("FatigueResumed"+side,NpcLifeData.get(s).record(n.getCharacterId()).fatigue());event("REAL_SLEEP_RESUMED_"+side);}}if(trace.hasUUID("ResumedSleep"+side)&&"DONE".equals(LifeActivityData.get(s).settlement(trace.getUUID("ResumedSleep"+side)+":2"))){check(NpcLifeData.get(s).record(n.getCharacterId()).bedPosition().asLong()==trace.getLong("Bed"+side)&&NpcLifeData.get(s).record(n.getCharacterId()).fatigue()<trace.getInt("FatigueResumed"+side),"same original private bed, normal real rest reduces fatigue");trace.putBoolean("LifeRestored",true);trace.putString("RestoredSide",side);trace.putLong("LifeRestoredAt",now);step(8);event("NORMAL_SLEEP_DONE_AFTER_TRUCE");break;}}}
            if(step()==8){if(now-trace.getLong("TruceAt")<2400||phase().equals("p7-done-resume")&&restored!=null&&now-restored.getLong("Time")<2400)return;var hypothetical=deal().copy();hypothetical.putUUID("Id",ContainerIds.named("p7-read-only-new-obligation-admission-probe"));check(!LimitedConflictGovernance.admitted(s,hypothetical,courier().getCharacterId()),"a new obligation ID cannot bypass the same pair truce; read-only admission probe, never a created deal");trace.putBoolean("NewObligationRejected",true);auditDebt();check(trace.getBoolean("LifeRestored")&&trace.getBoolean("SleepInterrupted")&&consumed()==4&&trace.getInt("RealWounds")+trace.getInt("RealDeaths")>0,"full frozen conflict-truce-life restoration chain");check(command(buyer,1,"conflict declare",buyer,seller)==0,"old shortage still cannot restart combat after observation");trace.putLong("StableTruceTicks",now-trace.getLong("TruceAt"));trace.putLong("Elapsed",now-trace.getLong("InitialAt"));success("DONE");}
        }
    }
}
