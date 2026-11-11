package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Controlled migration proof; supported generator fixtures, never relabeled natural discovery or human play. */
@GameTestHolder("sagadyssey_p3") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class ResidentContinuityP3GameTests {
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_p3:empty");
    private static CompoundTag previous;
    private static final Set<UUID> pausedAtSpawn=new HashSet<>();
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    @net.neoforged.bus.api.SubscribeEvent public static void originalSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(e.getLevel() instanceof ServerLevel l&&l.getServer() instanceof GameTestServer&&phase().startsWith("p3-")&&!phase().startsWith("p3-legacy-")&&!phase().endsWith("resume")&&e.getEntity() instanceof NpcBase n&&pausedAtSpawn.add(n.getUUID()))n.setNoAi(true);
    }
    private record F(ServerLevel l,NaturalVillageBuilding source,NaturalVillageBuilding target){
        NpcBase migrant(){return NaturalVillageR1GameTests.actor(l,source,2);}
        UUID person(){return NaturalVillageLifeData.person(source,2);}
        ResidentWorkMigrationData data(){return ResidentWorkMigrationData.get(l.getServer());}
        CompoundTag app(){return data().application(person());}
        Container pantry(){return box(l,target,NaturalVillageLayout.PANTRY);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;
        try{var s=e.getServer();var l=s.overworld();l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));previous=null;pausedAtSpawn.clear();
            if(!phase().startsWith("p3-")||!phase().endsWith("resume"))return;
            previous=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p3-evidence.nbt"),NbtAccounter.unlimitedHeap());
            var linked=links(new F(l,NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Source")),NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Destination"))));
            check(linked.equals(previous.getCompoundOrEmpty("Links")),"original linked application, household, life, zone and property contexts restored before ticks");
            Sagadyssey.LOGGER.info("P3_RESTORE linked household/life/workzone/property/application verified before ticks stage={}",previous.getStringOr("Stage", ""));
            var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
            NaturalVillageR1GameTests.visiting(l,NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Source")),true);NaturalVillageR1GameTests.visiting(l,NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Destination")),true);
        }catch(Exception ex){throw new IllegalStateException("P3 original whole-world checkpoint required",ex);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){String p=phase();if(!p.startsWith("p3-"))return List.of();return List.of(new TestFunction("p3",p.replace('-','_'),EMPTY.toString(),p.startsWith("p3-legacy-")?80000:48000,0,true,h->{
        if(p.startsWith("p3-legacy-")){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint 80000",h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}if(p.equals("p3-legacy-caravan"))HouseholdCaravanGameTests.run(h);else NaturalVillageBoundaryGameTests.cargo(h);}
        else if(p.endsWith("resume"))resume(h);else run(h);}));}
    private static Container box(ServerLevel l,NaturalVillageBuilding b,BlockPos relative){return (Container)l.getBlockEntity(NaturalVillageLayout.at(b,relative));}
    private static void check(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    private static int count(Container c,Item i){return SupplyFlowService.count(c,new ItemFingerprint(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString(),0));}
    private static int axeDamage(NpcBase n){for(int i=0;i<n.getPersonalInventory().getContainerSize();i++)if(n.getPersonalInventory().getItem(i).is(Items.IRON_AXE))return n.getPersonalInventory().getItem(i).getDamageValue();return -1;}
    private static CompoundTag links(F f){var s=f.l().getServer();var t=new CompoundTag();t.put("Application",ResidentWorkMigrationData.get(s).save(new CompoundTag(),s.registryAccess()));
        var houses=HouseholdData.get(s);t.put("SourceHome",houses.byId(NaturalVillageLifeData.home(f.source())).save());t.put("TargetHome",houses.byId(NaturalVillageLifeData.home(f.target())).save());
        var life=NpcLifeData.get(s);t.put("Life",life.record(f.person()).save());t.put("Zone",life.workZone(f.person()).save());
        var rights=RightsStateData.get(s);t.put("SourcePrivate",rights.context(NaturalVillageEconomyData.storeId(f.source(),2)).save());t.put("Estate",rights.context(NaturalVillageEconomyData.storeId(f.target(),2)).save());return t;}
    private static void audit(F f,boolean done){var s=f.l().getServer();var n=f.migrant();var h=HouseholdData.get(s);var source=h.byId(NaturalVillageLifeData.home(f.source()));var target=h.byId(NaturalVillageLifeData.home(f.target()));
        check(n!=null&&n.getUUID().equals(NaturalVillageLifeData.body(f.source(),2))&&n.getCharacterId().equals(f.person()),"same original body and person");
        check(source.members().size()==(done?2:3)&&target.members().size()==(done?4:3),"source and destination member counts reconcile");
        check(target.members().stream().filter(m->CharacterRegistryData.get(s).byId(m.characterId()).alive()).count()==(done?3:2),"living target count excludes retained deceased historical member");
        check(h.households().stream().flatMap(x->x.members().stream()).filter(m->m.characterId().equals(f.person())).count()==1,"never dual household membership");
        check(count(box(f.l(),f.source(),NaturalVillageLayout.WOOD),Items.COBBLESTONE)==7&&count(box(f.l(),f.target(),NaturalVillageLayout.WOOD),Items.COBBLESTONE)==11&&count(n.getPersonalInventory(),Items.COBBLESTONE)==3,"personal/source/estate real stocks not inherited, copied or consumed");
        var rights=RightsStateData.get(s);check(rights.context(NaturalVillageEconomyData.storeId(f.source(),2)).defaultBeneficialOwner().equals(SubjectRef.character(f.person())),"source warehouse ownership retained");
        check(rights.context(NaturalVillageEconomyData.storeId(f.target(),2)).defaultBeneficialOwner().equals(SubjectRef.character(NaturalVillageLifeData.person(f.target(),2))),"predecessor estate ownership retained");
        check(!CharacterRegistryData.get(s).byId(NaturalVillageLifeData.person(f.target(),2)).alive(),"predecessor stays dead");
        var goods=f.data().deathGoods(NaturalVillageLifeData.person(f.target(),2));check(goods!=null&&goods.getStringOr("State", "").equals("DROPPED"),"predecessor personal goods have a real recorded disposition");
        if(f.l().getGameTime()-goods.getLongOr("At", 0L)<5000)for(var entry:MinecraftApi.listOfType(goods, "Drops",Tag.TAG_COMPOUND)){var drop=(CompoundTag)entry;check(f.l().getEntity(drop.read("Entity", UUIDUtil.CODEC).orElseThrow()) instanceof net.minecraft.world.entity.item.ItemEntity item&&ItemStack.isSameItemSameComponents(item.getItem(),MinecraftApi.parseItemOptional(s.registryAccess(),drop.getCompoundOrEmpty("Stack"))),"same predecessor-owned physical item drop persists and is not inherited");}
        if(done){check(NpcWorkContext.from(n).workplaceSettlementId().equals(f.target().settlementId())&&n.getHomeSettlementId().equals(f.target().settlementId()),"only target employment and residence active");
            var r=NpcLifeData.get(s).record(f.person());check(r.originSettlementId().equals(f.source().settlementId())&&r.institutionWorkplaceSettlementId().equals(f.target().settlementId())&&r.privateStoragePosition()==null&&r.bedPosition().equals(ResidentWorkMigrationData.bed(f.target())),"origin retained, fallback job and actual bed replaced, no estate pointer");
            check(!com.jgeted.sagadyssey.social.politics.NaturalPolityData.population(s,f.source()).contains(f.person())&&com.jgeted.sagadyssey.social.politics.NaturalPolityData.population(s,f.target()).contains(f.person()),"effective source/destination population agrees");
        }
    }
    private static boolean worked(F f){var d=FieldworkData.get(f.l().getServer());long at=f.app().getLongOr("Completed", 0L);var receipts=d.receiptsFor(f.migrant().getUUID()).stream().filter(r->r.committedAt()>=at&&r.action().operation()==FieldworkAction.Operation.CHOP).toList();
        int logs=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(x->x.fingerprint().equals("minecraft:oak_log#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
        boolean lawful=false;
        for(var entry:MinecraftApi.listOfType(d.save(new CompoundTag(),f.l().getServer().registryAccess()), "Deliveries",Tag.TAG_COMPOUND)){var delivery=(CompoundTag)entry;
            if(!delivery.read("NpcId", UUIDUtil.CODEC).orElseThrow().equals(f.migrant().getUUID())||!delivery.getStringOr("Fingerprint", "").equals("minecraft:oak_log#0")||delivery.getLongOr("CommittedAt", 0L)<at)continue;
            var receipt=TransferLedgerData.get(f.l().getServer()).receipt(delivery.read("TransferId", UUIDUtil.CODEC).orElseThrow());
            lawful=receipt!=null&&receipt.successful()&&receipt.lawful()&&receipt.finalRevisions().containsKey(NaturalVillageEconomyData.storeId(f.target(),1));
        }
        return lawful&&logs>0&&axeDamage(f.migrant())>f.app().getIntOr("InitialAxeDamage", 0)&&count(f.pantry(),Items.OAK_LOG)>0&&d.pendingCargo(f.migrant().getUUID()).getOrDefault("minecraft:oak_log#0",0)==0;
    }
    private static CompoundTag accounts(F f){var s=f.l().getServer();var out=new CompoundTag();var stores=new ListTag();var bodies=new ListTag();
        for(var b:List.of(f.source(),f.target())){for(int i=0;i<NaturalVillageLayout.stores(b).size();i++){var t=new CompoundTag();t.store("Settlement", UUIDUtil.CODEC,b.settlementId());t.store("Container", UUIDUtil.CODEC,NaturalVillageEconomyData.storeId(b,i));t.putInt("Index",i);t.put("Items",NaturalVillageEconomyData.items(box(f.l(),b,NaturalVillageLayout.stores(b).get(i)),s.registryAccess()));stores.add(t);}
            for(int i=0;i<3;i++){var t=new CompoundTag();var person=NaturalVillageLifeData.person(b,i);t.store("Person", UUIDUtil.CODEC,person);t.store("Body", UUIDUtil.CODEC,NaturalVillageLifeData.body(b,i));t.putBoolean("Alive",CharacterRegistryData.get(s).byId(person).alive());var n=NaturalVillageR1GameTests.actor(f.l(),b,i);if(n!=null){t.putLong("Position",n.blockPosition().asLong());t.put("Personal",NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));t.put("Cargo",NaturalVillageEconomyData.items(n.getTransportInventory(),s.registryAccess()));}bodies.add(t);}}
        out.put("Stores",stores);out.put("Bodies",bodies);var a=ResidentWorkMigrationData.obligations(s,f.person(),NaturalVillageLifeData.body(f.source(),2));var d=ResidentWorkMigrationData.obligations(s,NaturalVillageLifeData.person(f.target(),2),NaturalVillageLifeData.body(f.target(),2));out.putString("ApplicantObligations",a==null?"CLEAR":a);out.putString("PredecessorObligations",d==null?"CLEAR":d);return out;
    }
    private static void save(F f,String stage){try{var s=f.l().getServer();var t=new CompoundTag();t.putString("Stage",stage);t.put("Source",NaturalVillageLifeData.get(s).village(f.source().buildingKey()));t.put("Destination",NaturalVillageLifeData.get(s).village(f.target().buildingKey()));t.put("Links",links(f));t.put("Accounts",accounts(f));t.putLong("Time",f.l().getGameTime());t.put("Migrant",MinecraftApi.saveEntity(f.migrant()));t.putInt("AxeDamage",axeDamage(f.migrant()));t.putInt("PantryLogs",count(f.pantry(),Items.OAK_LOG));t.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),s.registryAccess()));
        var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(t,world.resolve("p3-evidence.nbt"));s.saveEverything(true,true,true);
        var dst=world.resolveSibling("world-p3-snapshot");check(Files.notExists(dst),"snapshot never overwritten");
        try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var out=dst.resolve(world.relativize(p));Files.createDirectories(out.getParent());Files.copy(p,out);}}
        Sagadyssey.LOGGER.info("P3_SNAPSHOT stage={} person={} application={} sourceMembers={} targetMembers={} pantryLogs={} axeDamage={}",stage,f.person(),f.app().read("Id", UUIDUtil.CODEC).orElseThrow(),HouseholdData.get(s).byId(NaturalVillageLifeData.home(f.source())).members().size(),HouseholdData.get(s).byId(NaturalVillageLifeData.home(f.target())).members().size(),count(f.pantry(),Items.OAK_LOG),axeDamage(f.migrant()));
    }catch(Exception ex){throw new IllegalStateException("P3 associated world snapshot",ex);}}
    private static void run(GameTestHelper h){var f=new F(h.getLevel(),NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.WOOD_V2),NaturalVillageR1GameTests.fixture(h,128,NaturalVillageLayout.WOOD_V2));
        boolean[] setup={false},altered={false},restored={false};long[] began={0},blockedAt={0};CompoundTag[] unloaded={null};ItemStack[] held={ItemStack.EMPTY};net.minecraft.world.level.block.state.BlockState[] bed={null};
        try{f.l().getServer().getCommands().getDispatcher().execute("tick sprint 48000",f.l().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}
        h.onEachTick(()->{if(h.getTick()<120)return;var s=f.l().getServer();
            if(!setup[0]){check(f.migrant()!=null&&NaturalVillageR1GameTests.actor(f.l(),f.target(),2)!=null,"original actors initialized");
                for(int i=0;i<3;i++){NaturalVillageR1GameTests.actor(f.l(),f.source(),i).setNoAi(i==1);NaturalVillageR1GameTests.actor(f.l(),f.target(),i).setNoAi(true);}
                for(var b:List.of(f.source(),f.target())){var pantry=box(f.l(),b,NaturalVillageLayout.PANTRY);pantry.clearContent();pantry.setItem(0,new ItemStack(Items.CARROT,8));}
                box(f.l(),f.source(),NaturalVillageLayout.WOOD).clearContent();box(f.l(),f.source(),NaturalVillageLayout.WOOD).setItem(0,new ItemStack(Items.COBBLESTONE,7));box(f.l(),f.target(),NaturalVillageLayout.WOOD).clearContent();box(f.l(),f.target(),NaturalVillageLayout.WOOD).setItem(0,new ItemStack(Items.COBBLESTONE,11));f.migrant().getPersonalInventory().setItem(5,new ItemStack(Items.COBBLESTONE,3));
                var old=NaturalVillageR1GameTests.actor(f.l(),f.target(),2);
                if(phase().equals("p3-vacancy-unload")){unloaded[0]=MinecraftApi.saveEntity(old);old.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);}else {MinecraftApi.kill(old);if(phase().equals("p3-duplicate")){var before=f.data().deathGoods(old.getCharacterId());net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(old,old.damageSources().generic()));check(before.equals(f.data().deathGoods(old.getCharacterId())),"duplicate death keeps same real item disposition, no new drop or replacement");}}
                if(phase().equals("p3-food")){held[0]=f.pantry().getItem(0).copy();f.pantry().clearContent();}
                if(phase().equals("p3-food-rights")){var id=NaturalVillageEconomyData.storeId(f.target(),1);RightsStateData.get(s).putClaim(new AssetClaim(ContainerIds.named("p3-food-dispute"),id,0,new ItemFingerprint("minecraft:carrot",0),8,SubjectRef.character(f.person()),SubjectRef.household(NaturalVillageLifeData.home(f.target())),Set.of(),Set.of(),"declared disputed food negative",0));}
                if(phase().equals("p3-tool")){for(int i=0;i<f.migrant().getPersonalInventory().getContainerSize();i++)if(f.migrant().getPersonalInventory().getItem(i).is(Items.IRON_AXE)){held[0]=f.migrant().getPersonalInventory().removeItemNoUpdate(i);break;}}
                if(phase().equals("p3-retain"))NaturalVillageR1GameTests.actor(f.l(),f.source(),0).setNoAi(true);
                if(phase().equals("p3-obligation")){var id=ContainerIds.npcPersonal(f.person());var right=RightsStateData.get(s);var principal=SubjectRef.character(f.person());right.ensureContext(id,"npc:"+f.person(),principal,principal,AccessPolicy.PRIVATE);check(right.reserve(new AssetReservation(UUID.randomUUID(),id,new ItemFingerprint("minecraft:cobblestone",0),1,principal,"p3-old-obligation","pre-existing private reservation",f.l().getGameTime(),f.l().getGameTime()+2000),3,f.l().getGameTime()),"declared original reservation");}
                check(f.data().request(s,f.person(),f.source(),f.target()).equals("WAITING"),"formal explicit application");began[0]=f.l().getGameTime();setup[0]=true;
                if(phase().equals("p3-before-save")){save(f,"WAITING");h.succeed();}return;
            }
            var app=f.app();String state=app.getStringOr("State", "");long elapsed=f.l().getGameTime()-began[0];
            if(Set.of("p3-food","p3-food-rights","p3-tool","p3-retain","p3-obligation","p3-vacancy-unload").contains(phase())&&!restored[0]){
                check(state.equals("WAITING")&&HouseholdData.get(s).forMember(f.person()).id().equals(NaturalVillageLifeData.home(f.source())),"insufficient conditions keep original responsibilities and membership: "+app);
                if(elapsed<400)return;
                Sagadyssey.LOGGER.info("P3_BLOCK phase={} state={} reason={}",phase(),state,app.getStringOr("Reason", ""));
                if(phase().equals("p3-food"))f.pantry().setItem(0,held[0]);if(phase().equals("p3-tool"))f.migrant().getPersonalInventory().setItem(0,held[0]);if(phase().equals("p3-retain"))NaturalVillageR1GameTests.actor(f.l(),f.source(),0).setNoAi(false);if(phase().equals("p3-obligation"))RightsStateData.get(s).releaseReservations("p3-old-obligation");
                if(phase().equals("p3-food-rights"))RightsStateData.get(s).removeClaim(ContainerIds.named("p3-food-dispute"));
                if(phase().equals("p3-vacancy-unload")){var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),f.l());MinecraftApi.loadEntity(n, unloaded[0]);f.l().addFreshEntity(n);check(n.getCharacterId().equals(NaturalVillageLifeData.person(f.target(),2))&&CharacterRegistryData.get(s).byId(n.getCharacterId()).alive(),"same original absent worker restored alive");check(!f.app().contains("Departed")&&HouseholdData.get(s).forMember(f.person()).id().equals(NaturalVillageLifeData.home(f.source())),"no false vacancy or departure across temporary unload");save(f,"OCCUPIED_UNLOAD");Sagadyssey.LOGGER.info("P3_ACCEPT occupied original temporarily unloaded, no death or migration; existing region handoff remains authoritative");h.succeed();return;}
                restored[0]=true;
            }
            if(phase().equals("p3-timeout")){if(state.equals("WALKING")&&!altered[0]){f.l().setBlockAndUpdate(ResidentWorkMigrationData.bed(f.target()),Blocks.AIR.defaultBlockState());altered[0]=true;}if(elapsed>=24020){check(state.equals("REVIEW"),"fixed timeout preserves review, not fake arrival");audit(f,false);save(f,"REVIEW");Sagadyssey.LOGGER.info("P3_ACCEPT timeout REVIEW identity and property retained");h.succeed();}return;}
            if(phase().equals("p3-destination")&&state.equals("WALKING")&&!altered[0]){bed[0]=f.l().getBlockState(ResidentWorkMigrationData.bed(f.target()));f.l().setBlockAndUpdate(ResidentWorkMigrationData.bed(f.target()),Blocks.AIR.defaultBlockState());blockedAt[0]=f.l().getGameTime();altered[0]=true;return;}
            if(phase().equals("p3-destination")&&altered[0]&&!restored[0]){check(!state.equals("DONE"),"missing bed never commits");if(f.l().getGameTime()-blockedAt[0]<400)return;f.l().setBlockAndUpdate(ResidentWorkMigrationData.bed(f.target()),bed[0]);restored[0]=true;}
            if(phase().equals("p3-unload")&&state.equals("WALKING")&&!altered[0]){unloaded[0]=MinecraftApi.saveEntity(f.migrant());f.migrant().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);blockedAt[0]=f.l().getGameTime();altered[0]=true;return;}
            if(phase().equals("p3-unload")&&altered[0]&&!restored[0]){check(f.migrant()==null&&CharacterRegistryData.get(s).byId(f.person()).alive()&&!state.equals("DONE"),"unloaded migrant neither dead, copied nor committed");if(f.l().getGameTime()-blockedAt[0]<400)return;var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),f.l());MinecraftApi.loadEntity(n, unloaded[0]);f.l().addFreshEntity(n);restored[0]=true;return;}
            if(phase().equals("p3-walking-save")&&state.equals("WALKING")&&f.l().getGameTime()-app.getLongOr("Departed", 0L)>=40){audit(f,false);save(f,"WALKING");h.succeed();return;}
            if(phase().equals("p3-duplicate")){var id=app.read("Id", UUIDUtil.CODEC).orElseThrow();f.data().request(s,f.person(),f.source(),f.target());check(f.app().read("Id", UUIDUtil.CODEC).orElseThrow().equals(id)&&f.data().applications().size()==1,"one durable application across repeated processing");}
            if(elapsed%1000==0)Sagadyssey.LOGGER.info("P3_PROGRESS phase={} ticks={} state={} reason={} pos={} work={}",phase(),elapsed,state,app.getStringOr("Reason", ""),f.migrant()==null?"unloaded":f.migrant().blockPosition(),f.migrant()==null?"unloaded":f.migrant().getWorkActivityDetail());
            if(state.equals("DONE")){audit(f,true);if(worked(f)){save(f,"WORKED");Sagadyssey.LOGGER.info("P3_ACCEPT phase={} ticks={} samePerson={} sourceMembers=2 targetLiving=3 targetRecords=4 actualChopDeposit=true ownAxeDamage={} pantryLogs={}",phase(),elapsed,f.person(),axeDamage(f.migrant()),count(f.pantry(),Items.OAK_LOG));h.succeed();}}
        });
    }
    private static void resume(GameTestHelper h){check(previous!=null,"whole linked checkpoint");var f=new F(h.getLevel(),NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Source")),NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Destination")));
        try{f.l().getServer().getCommands().getDispatcher().execute("tick sprint 24000",f.l().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}
        h.onEachTick(()->{if(h.getTick()<100)return;var n=f.migrant();check(n!=null&&n.getCharacterId().equals(f.person()),"original body loaded, never reassigned by test");if(!f.app().getStringOr("State", "").equals("DONE"))return;audit(f,true);
            if(previous.getStringOr("Stage", "").equals("WORKED")){check(axeDamage(n)>=previous.getIntOr("AxeDamage", 0)&&count(f.pantry(),Items.OAK_LOG)>=previous.getIntOr("PantryLogs", 0),"completed work and inventory not rolled back");}
            else if(!worked(f))return;
            save(f,"RESTORED");Sagadyssey.LOGGER.info("P3_ACCEPT independent world resume from={} sameApplication={} sourceMembers=2 targetLiving=3 targetRecords=4 realLabor=true",previous.getStringOr("Stage", ""),f.app().read("Id", UUIDUtil.CODEC).orElseThrow());h.succeed();
        });
    }
}
