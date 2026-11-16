package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Isolated opt-in native fixture. Teleport/forced chunks only prepare/preserve its test scene. */
@GameTestHolder("sagadyssey_war_m3c")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class WarCohesionGameTests {
    private static final String TEMPLATE="sagadyssey_war_m3c:empty";
    private static final Set<UUID> soldiers=new HashSet<>();
    private static final Map<UUID,Vec3> lastPositions=new HashMap<>();
    private static int hits,offset;
    private static double maximumStep;
    private static JsonObject capture;
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.m3c.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.m3c.phase","single");}
    private static BlockPos scene(BlockPos p){return p.offset(offset,0,0);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event) throws Exception {
        if(!enabled() || !(event.getServer() instanceof GameTestServer))return;
        hits=0;offset=phase().equals("save")?2048:0;maximumStep=0;capture=null;soldiers.clear();lastPositions.clear();
        var level=event.getServer().overworld();
        if(phase().equals("resume")) {
            var proof=read(event.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m3c-save.json"));
            if(proof.get("process").getAsLong()==ProcessHandle.current().pid())throw new IllegalStateException("restart requires another JVM");
            event.getServer().getWorldData().overworldData().setGameTime(proof.get("gameTime").getAsLong());
            event.getServer().getWorldData().overworldData().setDayTime(proof.get("dayTime").getAsLong());
        }
        level.getStructureManager().getOrCreate(Identifier.parse(TEMPLATE)).load(level.holderLookup(Registries.BLOCK),
                TagParser.parseCompoundFully("{size:"+(phase().equals("resume")?"[1,1,1]":"[56,8,40]")+",entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return enabled()?List.of(new TestFunction("war_m3c","cohesion_"+phase(),TEMPLATE,6000,0,true,phase().equals("resume")?WarCohesionGameTests::resume:WarCohesionGameTests::lifecycle)):List.of();
    }
    @SubscribeEvent public static void damage(LivingDamageEvent.Post event){
        if(enabled() && event.getSource().getEntity() instanceof NpcBase npc && soldiers.contains(npc.getUUID()) && event.getNewDamage()>0)hits++;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!enabled() || !(event.getServer() instanceof GameTestServer))return;
        for(var id:soldiers)if(event.getServer().overworld().getEntity(id) instanceof NpcBase npc){
            var old=lastPositions.put(id,npc.position());if(old!=null)maximumStep=Math.max(maximumStep,old.distanceTo(npc.position()));
        }
    }
    private record Fixture(WarCandidateGameTests.Fixture original,List<WarMilitiaRequest> requests,WarServerRuntime core,WarFieldRuntime field,
                           WarMobilizationRuntime military,WarNativeMobilization nativePorts,ItemLocation armory,ItemLocation treasury,List<WarKitRecord.Line> kit){}
    private static Fixture fixture(GameTestHelper helper){
        var level=helper.getLevel();var server=level.getServer();
        for(int x=0;x<56;x++)for(int z=0;z<40;z++){
            var p=helper.absolutePos(scene(new BlockPos(x,0,z)));level.getChunkAt(p);helper.setBlock(scene(new BlockPos(x,0,z)),Blocks.STONE);
            if(offset!=0)level.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
        }
        var commander=dummy(helper,new BlockPos(2,1,18));var original=WarCandidateGameTests.fixture(helper,3,commander.getCharacterId());
        var loadout=new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,true).id();
        var requests=original.requests().stream().map(r->new WarMilitiaRequest(r.id(),r.actorId(),r.armyId(),r.unitId(),r.characterId(),loadout,1,r.kind(),r.role(),r.wage(),r.requestedAtTick())).toList();
        for(int i=0;i<3;i++){
            var npc=original.npcs().get(i);var p=helper.absolutePos(scene(new BlockPos(2+i*2,1,3)));npc.teleportTo(p.getX()+.5,p.getY(),p.getZ()+.5);
            npc.setNoGravity(false);npc.setOnGround(true);npc.setCommand(NpcCommand.STAY);npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            npc.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);npc.setHealth(100);npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));npc.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));
            original.sources().life().setHunger(npc.getCharacterId(),100,level.getGameTime());soldiers.add(npc.getUUID());
        }
        var first=requests.getFirst();var core=WarServerEvents.activate(server);
        core.bindAuthority((actor,army)->actor.equals(first.actorId())&&army.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        helper.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),first.actorId(),first.armyId(),first.unitId(),loadout,1)).status()==WarResult.Status.APPLIED,"create original unit");
        server.overworld().getDataStorage().saveAndJoin();
        helper.setBlock(scene(new BlockPos(2,1,26)),Blocks.CHEST);helper.setBlock(scene(new BlockPos(5,1,26)),Blocks.CHEST);
        var a=(ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(2,1,26))));var t=(ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(5,1,26))));
        var armory=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),a.getBlockPos());var treasury=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),t.getBlockPos());armory.bindBlock(server);treasury.bindBlock(server);
        for(int i=0;i<3;i++){a.setItem(i*2,new ItemStack(Items.IRON_SWORD));a.setItem(i*2+1,new ItemStack(Items.IRON_CHESTPLATE));}t.setItem(0,new ItemStack(Items.EMERALD,64));
        var rights=RightsStateData.get(server);var owner=SubjectRef.character(first.actorId());rights.ensureContext(armory.id(),"test:armory",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(treasury.id(),"test:treasury",owner,owner,AccessPolicy.PRIVATE);
        for(var npc:original.npcs()){
            var person=SubjectRef.character(npc.getCharacterId());rights.ensureContext(ItemLocation.personal(npc.getCharacterId(),npc.getUUID()).id(),"test:bag",person,person,AccessPolicy.PRIVATE);
            rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(),npc.getUUID()).id(),"test:equipment",person,person,AccessPolicy.PRIVATE);
        }
        try{
            var nativePorts=core.bindNativeMobilization(original.sources());var field=core.bindFieldExecution(new WarFieldRuntime.Sources(original.sources().characters(),original.sources().injuries(),CombatEncounterData.get(server),original.sources().life()));
            return new Fixture(original,requests,core,field,core.mobilization().orElseThrow(),nativePorts,armory,treasury,List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)),1),new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)),1)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void lifecycle(GameTestHelper helper){
        var f=fixture(helper);var npcs=f.original().npcs();var first=f.requests().getFirst();var level=helper.getLevel();var server=level.getServer();
        UUID[] order={null},encounter={null};NpcBase[] enemy={null};int[] before={0},organization={10_000},selfDefense={0},afterDefense={0};
        var sequence=helper.startSequence().thenWaitUntil(()->{
            boolean ready=true;for(var r:f.requests())ready&=f.nativePorts().configure(r,f.treasury(),f.armory(),f.kit())==WarMobilizationPorts.Status.APPLIED;
            helper.assertTrue(ready,"durable kits ready");
        }).thenExecute(()->{for(var r:f.requests()){var result=f.military().begin(r);helper.assertTrue(result.outcome()==WarMobilizationService.Outcome.ACCEPTED,"mobilize original body: "+result);}})
          .thenWaitUntil(()->{for(var r:f.requests())helper.assertTrue(f.military().service().orElseThrow().find(r.id()).filter(v->v.stage()==WarMobilization.Stage.ACTIVE).isPresent(),"real service active");helper.assertTrue(f.field().reports(first.actorId(),first.armyId()).size()==3,"actual body reports");})
          .thenExecute(()->order[0]=issue(helper,f,InitialWarDefinitions.RALLY,new BlockPos(6,1,6)))
          .thenWaitUntil(()->atRally(helper,f,npcs,order[0]))
          .thenExecute(()->{helper.assertTrue(state(f).organization()==10_000,"rally does not invent a new score");order[0]=issue(helper,f,InitialWarDefinitions.HOLD,new BlockPos(30,1,6));})
          .thenWaitUntil(()->atFront(helper,f,npcs,order[0]))
          .thenExecute(()->{enemy[0]=dummy(helper,new BlockPos(30,1,9));encounter[0]=encounter(helper,npcs,enemy[0]);})
          .thenWaitUntil(()->helper.assertTrue(hits>0,"original soldiers use real native attacks before retreat"))
          .thenExecute(()->{for(var npc:npcs)morale(npc,-100);helper.assertTrue(npcs.stream().allMatch(n->n.getMoral()>20),"native entity cache remains high while the authoritative morale ledger is low");})
          .thenWaitUntil(()->helper.assertTrue(f.field().reports(first.actorId(),first.armyId()).stream().allMatch(r->r.state()==WarFieldReport.State.RETREATING||r.state()==WarFieldReport.State.REGROUPING),"shared morale drives actual retreat"))
          .thenExecute(()->before[0]=hits)
          .thenWaitUntil(()->atRally(helper,f,npcs,order[0]))
          .thenIdle(25).thenExecute(()->{
              helper.assertTrue(hits==before[0]&&npcs.stream().allMatch(n->n.getTarget()==null),"retreat cancels targeting and native attacks");
              helper.assertTrue(state(f).organization()==10_000,"low morale did not overwrite organization");
              helper.assertTrue(maximumStep<1.5,"actual native walking, no command teleport");
              enemy[0].discard();NpcCombatRuntime.finish(level,encounter[0],level.getGameTime());
              enemy[0]=dummy(helper,new BlockPos(6,1,7));encounter[0]=encounter(helper,npcs,enemy[0]);selfDefense[0]=hits;
              helper.assertTrue(npcs.getFirst().hurtServer(level,level.damageSources().mobAttack(enemy[0]),1),"actual immediate attack on a retreating original");
          }).thenWaitUntil(()->helper.assertTrue(hits>selfDefense[0],"retreating sword holder retains bounded native self-defense"))
          .thenExecute(()->{
              selfDefense[0]=hits-selfDefense[0];afterDefense[0]=hits;var far=helper.absolutePos(scene(new BlockPos(45,1,30)));enemy[0].teleportTo(far.getX()+.5,far.getY(),far.getZ()+.5);
          }).thenIdle(40).thenExecute(()->{
              helper.assertTrue(hits==afterDefense[0],"self-defense never strikes an attacker outside weapon reach");
              var received=f.field().transmission(first.actorId(),first.armyId(),order[0]).orElseThrow();
              for(var npc:npcs){var member=received.members().stream().filter(m->m.body().equals(npc.getUUID())).findFirst().orElseThrow();var p=received.station(member,state(f).rally());helper.assertTrue(npc.distanceToSqr(p.x()+.5,p.y(),p.z()+.5)<=9,"self-defense does not pursue away from the rally area");}
          }).thenWaitUntil(()->atRally(helper,f,npcs,order[0])).thenExecute(()->{
              enemy[0].discard();NpcCombatRuntime.finish(level,encounter[0],level.getGameTime());for(var npc:npcs)morale(npc,100);
          }).thenWaitUntil(()->atFront(helper,f,npcs,order[0]));
        for(int wave=0;wave<3;wave++)sequence.thenExecute(()->{
            enemy[0]=dummy(helper,new BlockPos(30,1,10));encounter[0]=encounter(helper,npcs,enemy[0]);organization[0]=state(f).organization();
            for(var npc:npcs)helper.assertTrue(npc.hurtServer(level,level.damageSources().mobAttack(enemy[0]),1),"real hostile wound");
            for(var npc:npcs)morale(npc,100);
        }).thenWaitUntil(()->helper.assertTrue(state(f).organization()<organization[0],"new original injury events reduce organization"))
          .thenExecute(()->{enemy[0].discard();NpcCombatRuntime.finish(level,encounter[0],level.getGameTime());}).thenIdle(25);
        sequence.thenWaitUntil(()->helper.assertTrue(state(f).organization()==0,"three native casualty shocks deplete organization despite high shared morale"))
          .thenExecute(()->{
              capture=proof(f,npcs,helper.absolutePos(scene(BlockPos.ZERO)).asLong());capture.addProperty("realWounds",f.original().sources().injuries().wounds().stream().filter(w->npcs.stream().anyMatch(n->n.getCharacterId().equals(w.patientCharacterId()))).count());
              capture.addProperty("maximumNativeStep",maximumStep);capture.addProperty("nativeHitsBeforeRetreat",before[0]);capture.addProperty("highMoraleZeroOrganization",true);capture.addProperty("organizationAfterNativeShocks",state(f).organization());
              capture.addProperty("retreatSelfDefenseHits",selfDefense[0]);capture.addProperty("noRetreatPursuit",true);
              if(phase().equals("save")){server.overworld().getDataStorage().saveAndJoin();}
          });
        if(phase().equals("save"))sequence.thenSucceed();
        else sequence.thenWaitUntil(()->helper.assertTrue(state(f).organization()>=6000&&state(f).phase()==WarCohesion.Phase.STEADY,"real regrouping restores combat control"))
          .thenExecute(()->{capture.addProperty("regroupedOrganization",state(f).organization());order[0]=issue(helper,f,InitialWarDefinitions.RETREAT,new BlockPos(6,1,6));})
          .thenWaitUntil(()->atRally(helper,f,npcs,order[0])).thenExecute(()->{for(var r:f.requests())f.military().demobilize(r.id(),first.actorId());})
          .thenWaitUntil(()->released(helper,f,npcs)).thenExecute(()->capture.addProperty("stage","RELEASED")).thenSucceed();
    }
    private static void resume(GameTestHelper helper){
        try{
            var level=helper.getLevel();var server=level.getServer();var saved=read(server.getWorldPath(LevelResource.ROOT).resolve("war-m3c-save.json"));
            var plans=List.copyOf(new WarKitCodec().read(Files.readAllBytes(server.getWorldPath(LevelResource.ROOT).resolve("data/"+WarKitAdapter.FILE_NAME))));var first=plans.getFirst().request();
            var sources=WarNativeMobilizationGameTests.sources(server);var core=WarServerEvents.existing(server).orElseThrow();core.pause();
            core.bindAuthority((actor,army)->actor.equals(first.actorId())&&army.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
            var ports=core.bindNativeMobilization(sources);var field=core.bindFieldExecution(new WarFieldRuntime.Sources(sources.characters(),sources.injuries(),CombatEncounterData.get(server),sources.life()));
            var initial=field.cohesion(first.actorId(),first.armyId(),first.unitId()).orElseThrow();helper.assertTrue(initial.organization()==saved.get("organization").getAsInt(),"original low organization survives a distinct JVM");
            var p=BlockPos.of(saved.get("origin").getAsLong());for(int x=p.getX()>>4;x<=(p.getX()+56)>>4;x++)for(int z=p.getZ()>>4;z<=(p.getZ()+40)>>4;z++)level.setChunkForced(x,z,true);
            var loaded=new ArrayList<NpcBase>();var f=new Fixture(null,plans.stream().map(WarKitRecord::request).toList(),core,field,core.mobilization().orElseThrow(),ports,plans.getFirst().armory(),ports.provisions().find(first.id()).orElseThrow().terms().treasury(),List.of());
            UUID[] retreat={null};int[] score={initial.organization()};
            helper.startSequence().thenWaitUntil(()->{
                loaded.clear();for(var id:saved.getAsJsonArray("bodies")){var npc=level.getEntity(UUID.fromString(id.getAsString()));helper.assertTrue(npc instanceof NpcBase,"load original saved body");loaded.add((NpcBase)npc);}
                helper.assertTrue(ports.reconcile(f.military().service().orElseThrow().records()).eligible(),"shared physical evidence reconciles");
            }).thenExecute(()->{for(var npc:loaded)soldiers.add(npc.getUUID());core.activate();})
              .thenIdle(10).thenExecute(()->helper.assertTrue(state(f).organization()==score[0],"restart does not credit unobserved safe time"))
              .thenWaitUntil(()->helper.assertTrue(state(f).organization()>=6000&&state(f).phase()==WarCohesion.Phase.STEADY,"reloaded originals physically regroup before rejoining"))
              .thenExecute(()->{helper.assertTrue(hits==0,"no old damage replayed after restart");retreat[0]=issue(helper,f,InitialWarDefinitions.RETREAT,new BlockPos(6,1,6).offset((int)(p.getX()-helper.absolutePos(BlockPos.ZERO).getX()),(int)(p.getY()-helper.absolutePos(BlockPos.ZERO).getY()),(int)(p.getZ()-helper.absolutePos(BlockPos.ZERO).getZ())));})
              .thenWaitUntil(()->atRally(helper,f,loaded,retreat[0])).thenExecute(()->{for(var r:f.requests())f.military().demobilize(r.id(),first.actorId());})
              .thenWaitUntil(()->released(helper,f,loaded)).thenExecute(()->{
                  capture=proof(f,loaded,saved.get("origin").getAsLong());capture.addProperty("stage","RELEASED");capture.addProperty("priorProcess",saved.get("process").getAsLong());capture.addProperty("initialOrganization",initial.organization());capture.addProperty("attackReplays",hits);
              }).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("native cohesion recovery",e);}
    }
    private static void released(GameTestHelper helper,Fixture f,List<NpcBase> npcs){
        for(var r:f.requests())helper.assertTrue(f.military().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED,"real service release");
        for(var npc:npcs)helper.assertTrue(npc.getMainHandItem().isEmpty()&&npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty()&&npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE),"return borrowed kit, retain private tools");
        var a=f.armory().resolve(helper.getLevel().getServer(),WarNativeMobilizationGameTests.sources(helper.getLevel().getServer()).characters()).orElseThrow().container();
        helper.assertTrue(a.countItem(Items.IRON_SWORD)==3&&a.countItem(Items.IRON_CHESTPLATE)==3,"all original loaned kit returned");
    }
    private static WarCohesion state(Fixture f){var r=f.requests().getFirst();return f.field().cohesion(r.actorId(),r.armyId(),r.unitId()).orElseThrow();}
    private static UUID issue(GameTestHelper helper,Fixture f,WarKey kind,BlockPos relative){
        var r=f.requests().getFirst();var p=helper.absolutePos(scene(relative));var unit=f.core().session().orElseThrow().unit(r.unitId()).orElseThrow();
        var command=new WarCommand.IssueOrder(UUID.randomUUID(),r.actorId(),r.armyId(),r.unitId(),unit.revision(),new WarIntent(kind,1,new WarIntent.Position(WarKey.parse(helper.getLevel().dimension().identifier().toString()),p.getX(),p.getY(),p.getZ())));
        var result=f.field().dispatch(command);helper.assertTrue(result.outcome()==WarDispatchService.Outcome.ACCEPTED,"durable received command: "+result);return command.operationId();
    }
    private static void atRally(GameTestHelper helper,Fixture f,List<NpcBase> npcs,UUID order){
        var r=f.requests().getFirst();var transmission=f.field().transmission(r.actorId(),r.armyId(),order).orElseThrow();helper.assertTrue(transmission.received().isPresent(),"wait for received order");
        helper.assertTrue(f.field().cohesion(r.actorId(),r.armyId(),r.unitId()).isPresent(),"wait for first durable tactical checkpoint: "+f.field().reports(r.actorId(),r.armyId()));
        var state=state(f);for(var npc:npcs){var member=transmission.members().stream().filter(m->m.body().equals(npc.getUUID())).findFirst().orElseThrow();var p=transmission.station(member,state.rally());
            helper.assertTrue(npc.distanceToSqr(p.x()+.5,p.y(),p.z()+.5)<=1.1&&npc.getTarget()==null,"wait for original body's physical rally: "+npc.getUUID()+" actual="+npc.position()+" goal="+p+" reports="+f.field().reports(r.actorId(),r.armyId()));}
    }
    private static void atFront(GameTestHelper helper,Fixture f,List<NpcBase> npcs,UUID order){
        var r=f.requests().getFirst();var t=f.field().transmission(r.actorId(),r.armyId(),order).orElseThrow();helper.assertTrue(t.received().isPresent(),"received front order");
        for(var npc:npcs){var m=t.members().stream().filter(v->v.body().equals(npc.getUUID())).findFirst().orElseThrow();var p=t.station(m);helper.assertTrue(npc.distanceToSqr(p.x()+.5,p.y(),p.z()+.5)<=1.1,"walk to actual front station");}
    }
    private static NpcBase dummy(GameTestHelper helper,BlockPos pos){
        var npc=helper.spawn(NpcEntityTypes.NPC_BASE.get(),scene(pos));npc.setNoAi(true);npc.setNoGravity(false);npc.setCommand(NpcCommand.STAY);npc.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);npc.setHealth(1000);
        var characters=com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(helper.getLevel().getServer());long now=helper.getLevel().getGameTime();
        characters.ensureNpcCharacter(npc.getCharacterId(),"native cohesion fixture",NpcSocialClass.COMMONER,NpcOffice.NONE,"neutral",now);characters.bindNpcBody(npc.getCharacterId(),helper.getLevel().dimension().identifier().toString(),npc.getUUID(),now);return npc;
    }
    private static UUID encounter(GameTestHelper helper,List<NpcBase> npcs,NpcBase enemy){
        UUID id=UUID.randomUUID();CombatEncounterService.start(CombatEncounterData.get(helper.getLevel().getServer()),id,null,helper.getLevel().dimension().identifier().toString(),enemy.blockPosition(),64,
                List.of(new CombatSide(UUID.randomUUID(),"original unit",null,new HashSet<>(npcs.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE),new CombatSide(UUID.randomUUID(),"native hostile fixture",null,Set.of(enemy.getCharacterId()),EngagementRule.LETHAL_FORCE)),helper.getLevel().getGameTime());return id;
    }
    private static void morale(NpcBase npc,int delta){
        var data=CombatMoraleData.get(((net.minecraft.server.level.ServerLevel)npc.level()).getServer());data.ensure(npc.getCharacterId(),50,npc.getMoral(),npc.level().getGameTime());
        data.record(new MoraleEvent(UUID.randomUUID(),npc.getCharacterId(),null,delta<0?MoraleEventType.UNPAID:MoraleEventType.LEADER_PRESENT,delta,null,npc.level().getGameTime(),"explicit authoritative fixture stimulus; no military duplicate morale"));
    }
    private static JsonObject proof(Fixture f,List<NpcBase> npcs,long origin){
        var value=new JsonObject();var bodies=new JsonArray();for(var npc:npcs)bodies.add(npc.getUUID().toString());value.add("bodies",bodies);value.addProperty("people",3);value.addProperty("organization",state(f).organization());value.addProperty("phase",state(f).phase().name());
        value.addProperty("origin",origin);
        value.addProperty("process",ProcessHandle.current().pid());return value;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) throws Exception{
        if(!enabled()||capture==null||!(event.getServer() instanceof GameTestServer))return;
        var root=event.getServer().getWorldPath(LevelResource.ROOT);var disk=new WarCohesionCodec().read(Files.readAllBytes(root.resolve("data/"+WarCohesionJournal.FILE_NAME))).iterator().next();capture.addProperty("organization",disk.organization());capture.addProperty("phase",disk.phase().name());
        var level=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64*1024*1024L)).getCompoundOrEmpty("Data");capture.addProperty("gameTime",level.getLongOr("Time",0L));capture.addProperty("dayTime",level.getLongOr("DayTime",0L));
        write(root.resolve("war-m3c-"+phase()+".json"),capture);
    }
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void write(Path p,JsonObject value)throws Exception{Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(value));}
}
