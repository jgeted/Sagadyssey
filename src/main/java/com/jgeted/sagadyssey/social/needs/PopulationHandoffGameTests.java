package com.jgeted.sagadyssey.social.needs;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in, one settlement per isolated world. No player camera or production shortcut. */
@GameTestHolder("sagadyssey_handoff")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class PopulationHandoffGameTests {
    private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:carrot",0);
    private static final BlockPos BASE=new BlockPos(384,80,384);
    private static final BlockPos SOURCE=BASE.offset(10,1,6), PANTRY=BASE.offset(22,1,6);
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_handoff:empty");
    private static final String MARKER="population-handoff.nbt";
    private static CompoundTag restored;
    private static String phase(){return System.getProperty("sagadyssey.handoff.phase","disabled");}
    private record Fixture(ServerLevel level,UUID settlement,UUID household,UUID aBody,UUID aPerson,
                           UUID bBody,UUID bPerson,UUID headBody,UUID headPerson,UUID need,UUID grant,UUID source) {
        MinecraftServer server(){return level.getServer();}
        NpcBase a(){return actor(aBody);} NpcBase b(){return actor(bBody);}
        NpcBase actor(UUID id){return level.getEntity(id) instanceof NpcBase n?n:null;}
        Container sourceBox(){return (Container)level.getBlockEntity(SOURCE);}
        Container pantry(){return (Container)level.getBlockEntity(PANTRY);}
        NeedRequest demand(){return NeedRequestData.get(server()).request(need);}
        List<SupplyFlowData.Route> routes(){return SupplyFlowData.get(server()).routes().stream().filter(r->r.need().equals(need)).toList();}
    }
    @SubscribeEvent public static void install(ServerStartedEvent event) {
        if(!(event.getServer() instanceof GameTestServer)||phase().equals("disabled"))return;
        var server=event.getServer();var l=server.overworld();restored=null;
        try {
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            if(phase().endsWith("resume")) {
                restored=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
                var world=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
                server.getWorldData().overworldData().setGameTime(Math.max(restored.getLongOr("Time", 0L),world.getCompoundOrEmpty("Data").getLongOr("Time", 0L))+1);
                // Validate linked ledgers before any normal NPC tick can advance them.
                require(restored.getCompoundOrEmpty("Need").equals(NeedRequestData.get(server).request(restored.read("NeedId", UUIDUtil.CODEC).orElseThrow()).save()),"whole-world demand restored exactly");
                require(restored.getCompoundOrEmpty("Flow").equals(SupplyFlowData.get(server).save(new CompoundTag(),server.registryAccess())),"whole-world routes and scopes restored exactly");
                require(restored.getCompoundOrEmpty("Rights").equals(RightsStateData.get(server).save(new CompoundTag(),server.registryAccess())),"whole-world rights and reservations restored exactly");
                require(restored.getCompoundOrEmpty("Characters").equals(CharacterRegistryData.get(server).save(new CompoundTag(),server.registryAccess())),"whole-world character identities restored exactly");
                require(restored.getCompoundOrEmpty("Households").equals(HouseholdData.get(server).save(new CompoundTag(),server.registryAccess())),"whole-world household membership restored exactly");
                require(restored.getCompoundOrEmpty("NpcBrain").equals(NpcBrainRuntime.data(server).save(new CompoundTag(),server.registryAccess())),"whole-world active work plans restored exactly");
                require(!CharacterRegistryData.get(server).byId(restored.read("APerson", UUIDUtil.CODEC).orElseThrow()).alive(),"original character stays dead across JVM");
                var proof=restored.copy();proof.putLong("RestoredAt",l.getGameTime());proof.putBoolean("LinkedLedgersVerifiedBeforeTick",true);
                NbtIo.writeCompressed(proof,server.getWorldPath(LevelResource.ROOT).resolve("handoff-restored-proof.nbt"));
            }
            force(l);l.setDayTime(1000);
            l.getGameRules().set(GameRules.ADVANCE_TIME, false,server);
            l.getGameRules().set(GameRules.SPAWN_MOBS, false,server);
        } catch(Exception x){throw new IllegalStateException("handoff independent-world recovery",x);}
    }
    private static void require(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private static void force(ServerLevel l){for(int x=23;x<=26;x++)for(int z=23;z<=25;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}}
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if(phase().equals("disabled"))return List.of();
        return List.of(new TestFunction("population_handoff","population_handoff_"+phase(),EMPTY.toString(),
                net.minecraft.world.level.block.Rotation.NONE,2400,0,true,PopulationHandoffGameTests::run));
    }
    private static NpcBase npc(ServerLevel l,BlockPos p,String name,NpcProfession profession,UUID settlement) {
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.snapTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);
        n.setNpcName(name);n.setProfession(profession);n.setHomeSettlementId(settlement);n.setPersistenceRequired();
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
        n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setCommand(NpcCommand.WORK);l.addFreshEntity(n);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);
        return n;
    }
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var s=l.getServer();force(l);
        for(var p:BlockPos.betweenClosed(BASE,BASE.offset(25,5,12)))l.setBlockAndUpdate(p,p.getY()==BASE.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        var registry=SettlementRegistryData.get(s);var village=registry.create(s,"handoff village",SettlementType.VILLAGE,l.dimension().identifier().toString(),BASE,48,"sagadyssey:wilderness",null,l.getGameTime());
        var a=npc(l,BASE.offset(2,1,6),"original courier",NpcProfession.COURIER,village.id());
        var b=npc(l,BASE.offset(2,1,8),"existing successor",NpcProfession.COURIER,village.id());
        var head=npc(l,BASE.offset(3,1,10),"household head",NpcProfession.NONE,village.id());
        // Setup gate only: let A acquire the first task; B is enabled before the death event.
        b.setNoAi(true);
        var people=List.of(a,b,head);var life=NpcLifeData.get(s);
        for(int i=0;i<people.size();i++) {
            var bed=BASE.offset(5+i*3,1,2);
            l.setBlockAndUpdate(bed,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));
            l.setBlockAndUpdate(bed.south(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD));
            life.registerInstitutional(people.get(i),l.dimension().identifier().toString(),village.id(),BASE,"handoff residence",bed,null,NpcWorkShift.DAY,l.getGameTime(),false);
        }
        var households=HouseholdData.get(s);var home=households.create("handoff household",l.dimension().identifier().toString(),village.id(),head.getCharacterId(),BASE,l.getGameTime());
        require(households.addMember(home.id(),a.getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"A joins real household");
        require(households.addMember(home.id(),b.getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"B joins real household");
        l.setBlockAndUpdate(SOURCE,Blocks.BARREL.defaultBlockState());l.setBlockAndUpdate(PANTRY,Blocks.BARREL.defaultBlockState());
        require(households.assignSharedStorage(home.id(),home.dimension(),BASE,PANTRY),"real pantry assignment");
        households.setProvisionPlan(new HouseholdProvisionPlan(home.id(),0,0,0));
        registry.registerScenarioBuilding(s,home.dimension(),SOURCE,"sagadyssey:handoff_source","warehouse","small",village.id(),l.getGameTime());
        registry.registerScenarioBuilding(s,home.dimension(),PANTRY,"sagadyssey:handoff_pantry","warehouse","small",village.id(),l.getGameTime());
        var principal=SubjectRef.household(home.id());var institution=SubjectRef.institution(village.id());
        UUID source=ContainerIds.block("handoff_source",home.dimension(),SOURCE),destination=ContainerIds.block("household_storage",home.dimension(),PANTRY),grant=UUID.randomUUID();
        RightsStateData.get(s).ensureContext(source,"handoff-source",institution,institution,AccessPolicy.PRIVATE);
        RightsStateData.get(s).ensureContext(destination,"handoff-pantry",principal,principal,AccessPolicy.HOUSEHOLD);
        RightsStateData.get(s).grant(new RightGrant(grant,source,principal,EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"explicit household supply grant",l.getGameTime(),null,true));
        ((Container)l.getBlockEntity(SOURCE)).setItem(0,new ItemStack(Items.CARROT,4));
        var flows=SupplyFlowData.get(s);flows.register(new FieldworkStorage(home.dimension(),SOURCE,source,institution,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"handoff source"));
        flows.register(new FieldworkStorage(home.dimension(),PANTRY,destination,principal,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"handoff pantry"));flows.reveal(principal,source);
        // Existing fixed household buffer policy, independent of death-related headcount changes.
        flows.policy(new SupplyFlowData.Policy("handoff-buffer",principal,destination,FOOD,4,"household"));
        SupplyFlowService.aggregate(s,l.getGameTime());SupplyFlowService.match(s,l.getGameTime());
        var need=NeedRequestData.get(s).requests().stream().filter(r->r.key().purpose().endsWith(":handoff-buffer")).findFirst().orElseThrow();
        var f=new Fixture(l,village.id(),home.id(),a.getUUID(),a.getCharacterId(),b.getUUID(),b.getCharacterId(),head.getUUID(),head.getCharacterId(),need.id(),grant,source);
        association(f);return f;
    }
    private static void association(Fixture f) {
        require(SettlementRegistryData.get(f.server()).allSettlements().size()==1,"one real settlement in this isolated world");
        require(CharacterRegistryData.get(f.server()).allCharacters().size()==3,"no new or replacement residents created");
        var homes=HouseholdData.get(f.server());var home=homes.byId(f.household());
        require(home!=null&&f.settlement().equals(home.settlementId()),"non-null household settlement");
        for(UUID id:List.of(f.aPerson(),f.bPerson(),f.headPerson())) {
            var life=NpcLifeData.get(f.server()).record(id);
            require(life!=null&&f.settlement().equals(life.originSettlementId())&&home.contains(id),"resident and household same settlement");
        }
        require(SettlementRegistryData.get(f.server()).buildings().stream().filter(b->b.settlementId().equals(f.settlement())&&(b.position().equals(SOURCE)||b.position().equals(PANTRY))).count()==2,"both real facilities registered to same village");
    }
    private static CompoundTag describe(Fixture f,UUID old,long start) {
        var t=new CompoundTag();t.putString("Phase",phase());t.putLong("Time",f.level().getGameTime());t.putLong("Started",start);
        t.store("Settlement", UUIDUtil.CODEC,f.settlement());t.store("Household", UUIDUtil.CODEC,f.household());t.store("ABody", UUIDUtil.CODEC,f.aBody());t.store("APerson", UUIDUtil.CODEC,f.aPerson());
        t.store("BBody", UUIDUtil.CODEC,f.bBody());t.store("BPerson", UUIDUtil.CODEC,f.bPerson());t.store("HeadBody", UUIDUtil.CODEC,f.headBody());t.store("HeadPerson", UUIDUtil.CODEC,f.headPerson());
        t.store("NeedId", UUIDUtil.CODEC,f.need());t.store("OldRoute", UUIDUtil.CODEC,old);t.store("Grant", UUIDUtil.CODEC,f.grant());t.store("SourceId", UUIDUtil.CODEC,f.source());
        t.put("Need",f.demand().save());t.put("Flow",SupplyFlowData.get(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.put("Rights",RightsStateData.get(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.putInt("SourceCount",SupplyFlowService.count(f.sourceBox(),FOOD));t.putInt("PantryCount",SupplyFlowService.count(f.pantry(),FOOD));
        t.put("Logistics",LogisticsData.get(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.put("Characters",CharacterRegistryData.get(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.put("Households",HouseholdData.get(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.put("NpcBrain",NpcBrainRuntime.data(f.server()).save(new CompoundTag(),f.server().registryAccess()));
        t.putInt("EffectiveCarrotBufferDeficit",Math.max(0,4-SupplyFlowService.count(f.pantry(),FOOD)));
        t.putLong("LivingRegisteredResidents",CharacterRegistryData.get(f.server()).allCharacters().stream().filter(c->c.alive()).count());
        if(f.b()!=null){t.putString("CandidateProfession",f.b().getProfession().name());t.putString("CandidateCommand",f.b().getCommand().name());t.putInt("CandidateCapacity",f.b().getTransportSlotLimit());t.putString("CandidateScheduleReason",NpcSchedulePlanner.current(f.b()).reason());}
        return t;
    }
    private static void record(Fixture f,UUID old,long start,String label) {
        try{NbtIo.writeCompressed(describe(f,old,start),f.server().getWorldPath(LevelResource.ROOT).resolve("handoff-"+label+".nbt"));}
        catch(Exception x){throw new IllegalStateException(x);}
    }
    private static void snapshot(Fixture f,UUID old,long start) {
        try {
            var server=f.server();var root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            NbtIo.writeCompressed(describe(f,old,start),root.resolve(MARKER));
            server.saveEverything(false,true,false);
            var destination=root.resolveSibling(root.getFileName()+"-handoff-snapshot");
            require(!Files.exists(destination),"snapshot must be new");Files.createDirectories(destination);
            try(var files=Files.walk(root)){for(var p:files.toList()) {
                var relative=root.relativize(p);if(p.getFileName().toString().equals("session.lock"))continue;
                var q=destination.resolve(relative);if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);
            }}
            Sagadyssey.LOGGER.info("HANDOFF_WHOLE_WORLD_SNAPSHOT phase={} path={} need={} old={}",phase(),destination,f.need(),old);
        } catch(Exception x){throw new IllegalStateException("whole linked world snapshot",x);}
    }
    private static Fixture restore(GameTestHelper h) {
        require(restored!=null,"independent JVM marker required");var t=restored;
        return new Fixture(h.getLevel(),t.read("Settlement", UUIDUtil.CODEC).orElseThrow(),t.read("Household", UUIDUtil.CODEC).orElseThrow(),t.read("ABody", UUIDUtil.CODEC).orElseThrow(),t.read("APerson", UUIDUtil.CODEC).orElseThrow(),t.read("BBody", UUIDUtil.CODEC).orElseThrow(),t.read("BPerson", UUIDUtil.CODEC).orElseThrow(),t.read("HeadBody", UUIDUtil.CODEC).orElseThrow(),t.read("HeadPerson", UUIDUtil.CODEC).orElseThrow(),t.read("NeedId", UUIDUtil.CODEC).orElseThrow(),t.read("Grant", UUIDUtil.CODEC).orElseThrow(),t.read("SourceId", UUIDUtil.CODEC).orElseThrow());
    }
    private static void preDeath(Fixture f,SupplyFlowData.Route r,long start) {
        require(r.stage()==SupplyFlowData.Stage.HELD&&f.aBody().equals(r.carrierEntity())&&f.aPerson().equals(r.carrierCharacter()),"A really owns the original live claim");
        require(r.pending()==null&&f.demand().reserved()==4&&f.demand().inTransit()==0&&f.demand().delivered()==0,"no pickup, pending transfer or delivered goods");
        require(SupplyFlowService.count(f.a().getTransportInventory(),FOOD)==0&&SupplyFlowService.count(f.sourceBox(),FOOD)==4,"original four goods still at source");
        require(RightsStateData.get(f.server()).reservationsForToken(r.token(),f.level().getGameTime()).stream().mapToInt(AssetReservation::quantity).sum()==4,"exact original reservation");
        require(f.aPerson().equals(LogisticsData.get(f.server()).order(r.id()).assignedNpcId()),"formal managed order assigned to A");
        require(LogisticsData.get(f.server()).order(r.id()).pickedUpCount()==0&&LogisticsData.get(f.server()).order(r.id()).deliveredCount()==0,"formal order confirms no pickup or delivery");
        require(NpcBrainRuntime.data(f.server()).active(f.aBody()).isPresent(),"A actually has the claimed work plan");
        record(f,r.id(),start,"before-death");
    }
    private static void dead(Fixture f,UUID old) {
        require(!CharacterRegistryData.get(f.server()).byId(f.aPerson()).alive(),"A remains dead");
        var r=SupplyFlowData.get(f.server()).route(old);require(r.stage()==SupplyFlowData.Stage.CANCELLED&&f.aBody().equals(r.carrierEntity()),"old route closed without rewriting identity");
        require(RightsStateData.get(f.server()).reservationsForToken(r.token(),f.level().getGameTime()).isEmpty(),"old reservation released");
        require(f.demand().requested()>=4,"effective fixed buffer deficit remains at least four after death");association(f);
    }
    private static void finish(GameTestHelper h,Fixture f,UUID old,long start,boolean original) {
        var id=original?f.aBody():f.bBody();var person=original?f.aPerson():f.bPerson();
        var completed=f.routes().stream().filter(r->r.stage()==SupplyFlowData.Stage.DONE).toList();if(completed.isEmpty())return;
        require(completed.size()==1&&id.equals(completed.getFirst().carrierEntity())&&person.equals(completed.getFirst().carrierCharacter()),"one real completion by the expected original body/person");
        require(f.demand().delivered()==4&&f.demand().inTransit()==0&&f.demand().reserved()==0,"four actual delivered, no outstanding duplicate");
        require(SupplyFlowService.count(f.sourceBox(),FOOD)==0&&SupplyFlowService.count(f.pantry(),FOOD)==4&&SupplyFlowService.count(f.actor(id).getTransportInventory(),FOOD)==0,"0 source + 0 cargo + 4 pantry before consumption");
        require(NpcLifeData.get(f.server()).needs(f.bPerson()).lastAteAt()<start&&NpcLifeData.get(f.server()).needs(f.headPerson()).lastAteAt()<start,"net increase observed before any meal");
        require(f.actor(id).getX()>PANTRY.getX()-4,"real travel to destination, no remote credit");
        if(!original)dead(f,old);
        record(f,old,start,"accepted");
        Sagadyssey.LOGGER.info("HANDOFF_ACCEPT phase={} elapsed={} settlement={} need={} old={} successorBody={} successorPerson={} source=0 cargo=0 pantry=4 delivered=4",phase(),f.level().getGameTime()-start,f.settlement(),f.need(),old,id,person);
        h.succeed();
    }
    private static void run(GameTestHelper h) {
        var f=phase().endsWith("resume")?restore(h):fixture(h);long start=f.level().getGameTime();
        long[] at={-1};UUID[] old={restored==null?null:restored.read("OldRoute", UUIDUtil.CODEC).orElseThrow()};boolean[] returned={false},changed={false};CompoundTag[] unloaded={null};
        if(restored!=null)at[0]=start;
        h.onEachTick(()->{
            long now=f.level().getGameTime();if(f.b()==null||f.actor(f.headBody())==null)return;
            if(phase().endsWith("resume")){association(f);finish(h,f,old[0],restored.getLongOr("Started", 0L),false);return;}
            if(old[0]==null) {
                var claim=f.routes().stream().filter(r->f.aBody().equals(r.carrierEntity())).findFirst().orElse(null);
                if(claim==null)return;preDeath(f,claim,start);old[0]=claim.id();at[0]=now;f.b().setNoAi(false);
                if(phase().equals("calibrate")){f.b().setCommand(NpcCommand.STAY);return;}
                if(phase().equals("unload")) {
                    unloaded[0]=com.jgeted.sagadyssey.core.MinecraftApi.saveEntity(f.a());
                    try{NbtIo.writeCompressed(unloaded[0],f.server().getWorldPath(LevelResource.ROOT).resolve("handoff-unloaded-body.nbt"));}catch(Exception x){throw new IllegalStateException(x);}
                    f.a().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);return;
                }
                if(phase().equals("no-candidate")||phase().equals("capacity"))f.b().setProfession(NpcProfession.NONE);
                if(phase().equals("conflict"))f.b().setCommand(NpcCommand.STAY);
                if(phase().equals("permission"))RightsStateData.get(f.server()).revoke(f.grant());
                com.jgeted.sagadyssey.core.MinecraftApi.kill(f.a());dead(f,old[0]);record(f,old[0],start,"released");
                if(phase().equals("pre-save")){snapshot(f,old[0],start);h.succeed();}
                return;
            }
            if(phase().equals("calibrate")){finish(h,f,old[0],start,true);return;}
            if(phase().equals("unload")) {
                require(CharacterRegistryData.get(f.server()).byId(f.aPerson()).alive(),"unloaded A remains alive");
                require(f.routes().size()==1&&f.aBody().equals(f.routes().getFirst().carrierEntity())&&f.routes().getFirst().stage()!=SupplyFlowData.Stage.CANCELLED&&f.routes().getFirst().stage()!=SupplyFlowData.Stage.REVIEW,"unload cannot recruit B or replace original claim");
                if(now-at[0]<200){require(f.a()==null,"original body genuinely unavailable");return;}
                if(!returned[0]){try{var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),f.level());com.jgeted.sagadyssey.core.MinecraftApi.loadEntity(n, NbtIo.readCompressed(f.server().getWorldPath(LevelResource.ROOT).resolve("handoff-unloaded-body.nbt"),NbtAccounter.unlimitedHeap()));f.level().addFreshEntity(n);require(n.getUUID().equals(f.aBody())&&n.getCharacterId().equals(f.aPerson()),"same unloaded body and character restored");returned[0]=true;}catch(Exception x){throw new IllegalStateException(x);}return;}
                finish(h,f,old[0],start,true);return;
            }
            dead(f,old[0]);
            if(phase().equals("duplicate")) {
                var a=f.a();if(a!=null&&now-at[0]<5)NeoForge.EVENT_BUS.post(new LivingDeathEvent(a,a.damageSources().generic()));
                SupplyFlowService.aggregate(f.server(),now);SupplyFlowService.match(f.server(),now);
            }
            if(Set.of("no-candidate","capacity","permission","conflict").contains(phase())&&!changed[0]) {
                require(f.demand().delivered()==0&&f.demand().inTransit()==0&&SupplyFlowService.count(f.sourceBox(),FOOD)==4&&SupplyFlowService.count(f.pantry(),FOOD)==0,"blocked conditions never consume or deliver illegally");
                require(f.routes().stream().noneMatch(r->f.bBody().equals(r.carrierEntity())),"ineligible B cannot be assigned");
                if(now-at[0]<240)return;
                record(f,old[0],start,"blocked-before-recovery");
                if(phase().equals("no-candidate")){record(f,old[0],start,"accepted-no-candidate");Sagadyssey.LOGGER.info("HANDOFF_ACCEPT phase=no-candidate reason=no-eligible-cargo-capacity stock=4 delivered=0 NPCsNotCreated=true");h.succeed();return;}
                if(phase().equals("capacity"))f.b().setProfession(NpcProfession.COURIER);
                if(phase().equals("conflict"))f.b().setCommand(NpcCommand.WORK);
                if(phase().equals("permission"))RightsStateData.get(f.server()).grant(new RightGrant(f.grant(),f.source(),SubjectRef.household(f.household()),EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"restored legal source grant",now,null,true));
                NpcSchedulePlanner.refresh(f.b(),NpcLifeData.get(f.server()),f.level());changed[0]=true;return;
            }
            if(phase().equals("post-save")) {
                var next=f.routes().stream().filter(r->r.stage()==SupplyFlowData.Stage.HELD&&f.bBody().equals(r.carrierEntity())&&r.pending()==null).findFirst().orElse(null);
                if(next!=null){record(f,old[0],start,"successor-claimed");snapshot(f,old[0],start);h.succeed();}return;
            }
            finish(h,f,old[0],start,false);
        });
    }
}
