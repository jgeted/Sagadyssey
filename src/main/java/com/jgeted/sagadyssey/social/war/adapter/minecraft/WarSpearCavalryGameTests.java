package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Three original riders share one unit/order. Test-only spawning, owner setup and forced scene chunks. */
@GameTestHolder("sagadyssey_war_spear_cavalry") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarSpearCavalryGameTests{
    private static final String TEMPLATE="sagadyssey_war_spear_cavalry:empty";
    private static final Map<UUID,Map<Long,Integer>> hits=new HashMap<>();private static final Set<UUID> riders=new HashSet<>();
    private static final Map<UUID,Vec3> positions=new HashMap<>();private static final Map<Long,Long> launches=new LinkedHashMap<>();
    private static final Map<Long,UUID> battles=new LinkedHashMap<>();private static List<NpcBase> own=List.of(),opponents=List.of();
    private static final List<String> contactEvidence=new ArrayList<>();
    private static final Map<UUID,Map<Long,Double>> runs=new HashMap<>();
    private static WarFieldRuntime field;private static WarMilitiaRequest first;private static JsonObject capture;private static double maximumStep;private static int offset;private static UUID account;
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.spear_cavalry.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.spear_cavalry.phase","single");}
    private static boolean heavy(){return Boolean.getBoolean("sagadyssey.war.spear_cavalry.heavy");}
    private static BlockPos scene(BlockPos p){return p.offset(offset,0,0);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception{
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;hits.clear();riders.clear();positions.clear();launches.clear();battles.clear();contactEvidence.clear();runs.clear();own=List.of();opponents=List.of();capture=null;field=null;first=null;maximumStep=0;offset=phase().equals("save")?2048:0;
        var l=e.getServer().overworld();if(phase().equals("resume")){var saved=read(e.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m4b1-save.json"));if(saved.get("process").getAsLong()==ProcessHandle.current().pid())throw new IllegalStateException("distinct JVM required");e.getServer().getWorldData().overworldData().setGameTime(saved.get("gameTime").getAsLong());e.getServer().getWorldData().overworldData().setDayTime(saved.get("dayTime").getAsLong());}
        l.getStructureManager().getOrCreate(Identifier.parse(TEMPLATE)).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:"+(phase().equals("resume")?"[1,1,1]":"[64,9,40]")+",entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_m4b1","three_rider_cycle_"+phase()+"_"+(heavy()?"heavy":"light"),TEMPLATE,7000,0,true,phase().equals("resume")?WarSpearCavalryGameTests::resume:WarSpearCavalryGameTests::lifecycle)):List.of();}
    @SubscribeEvent public static void damage(LivingDamageEvent.Post e){
        if(!enabled()||first==null||!(e.getSource().getDirectEntity() instanceof NpcBase npc)||!riders.contains(npc.getUUID())||!npc.isPassenger()||e.getNewDamage()<=0)return;
        long wave=state().wave();hits.computeIfAbsent(npc.getUUID(),k->new HashMap<>()).merge(wave,1,Integer::sum);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        if(!enabled()||first==null||field==null||!(e.getServer() instanceof GameTestServer))return;
        var state=field.cavalry(first.actorId(),first.armyId(),first.unitId());if(state.isPresent()&&state.get().phase()==WarCavalry.Phase.RUN)launches.putIfAbsent(state.get().wave(),state.get().startedAt());
        for(var r:field.reports(first.actorId(),first.armyId()))if(r.reason().startsWith("CAVALRY_SINGLE_NATIVE_CONTACT:")&&contactEvidence.size()<32){var note=r.body()+":"+r.observedAt()+":"+r.reason();if(!contactEvidence.contains(note))contactEvidence.add(note);}
        // The shared combat service retires idle encounters. This explicit host fixture establishes
        // hostile engagement only when an actual run reaches the opposition; CHARGE grants no hostility.
        if(state.isPresent()&&state.get().phase()==WarCavalry.Phase.RUN&&!battles.containsKey(state.get().wave())&&!opponents.isEmpty()
                &&own.stream().anyMatch(n->opponents.stream().anyMatch(enemy->n.distanceToSqr(enemy)<=64))){
            var level=e.getServer().overworld();for(var id:battles.values())NpcCombatRuntime.finish(level,id,level.getGameTime());
            var id=UUID.randomUUID();CombatEncounterService.start(CombatEncounterData.get(e.getServer()),id,null,level.dimension().identifier().toString(),opponents.getFirst().blockPosition(),64,List.of(new CombatSide(UUID.randomUUID(),"original cavalry",null,new HashSet<>(own.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE),new CombatSide(UUID.randomUUID(),"explicit hostile fixture",null,new HashSet<>(opponents.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE)),level.getGameTime());battles.put(state.get().wave(),id);
        }
        for(var id:riders)if(e.getServer().overworld().getEntity(id) instanceof NpcBase npc){var mover=npc.isPassenger()?npc.getVehicle():npc;var previous=positions.put(id,mover.position());if(previous!=null)maximumStep=Math.max(maximumStep,previous.distanceTo(mover.position()));}
        if(state.isPresent()&&state.get().phase()==WarCavalry.Phase.RUN){var s=state.get();for(int i=0;i<s.members().size();i++)if(e.getServer().overworld().getEntity(s.members().get(i).body()) instanceof NpcBase n){var rear=WarCavalryFormation.assembly(s.assembly(),s.objective(),i,s.members().size());var pose=WarNativeBindings.currentBuild().mobility().pose(n);double d=(pose.x()-rear.x()-.5)*WarCavalryFormation.headingX(s.assembly(),s.objective())+(pose.z()-rear.z()-.5)*WarCavalryFormation.headingZ(s.assembly(),s.objective());runs.computeIfAbsent(n.getUUID(),k->new HashMap<>()).merge(s.wave(),d,Math::max);}}
    }
    private record Fixture(WarCandidateGameTests.Fixture source,List<WarMilitiaRequest> requests,List<Horse> horses,WarServerRuntime core,WarNativeMobilization ports,ItemLocation armory,ItemLocation treasury,ServerPlayer player,BlockPos origin){}
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var server=l.getServer();for(int x=0;x<64;x++)for(int z=0;z<40;z++){var p=h.absolutePos(scene(new BlockPos(x,0,z)));l.getChunkAt(p);if(offset!=0)l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);h.setBlock(scene(new BlockPos(x,0,z)),Blocks.STONE);}
        account=UUID.randomUUID();var player=player(h,account,h.absolutePos(scene(new BlockPos(8,1,16))));var actor=CharacterRegistry.currentId(player);var source=WarCandidateGameTests.fixture(h,3,actor);var requests=new ArrayList<WarMilitiaRequest>();var horses=new ArrayList<Horse>();
        var loadout=new WarLoadout(heavy()?WarLoadout.Mount.HEAVY:WarLoadout.Mount.LIGHT,WarLoadout.Weapon.SPEAR,false,false).id();
        for(int i=0;i<3;i++){
            var n=source.npcs().get(i);riders.add(n.getUUID());n.setCommand(NpcCommand.STAY);n.setNoGravity(false);n.setOnGround(true);n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));n.getPersonalInventory().setItem(4,new ItemStack(Items.HAY_BLOCK,4));n.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);n.setHealth(100);source.sources().life().setHunger(n.getCharacterId(),100,l.getGameTime());
            var horse=h.spawn(EntityType.HORSE,scene(new BlockPos(5,1,9+i*4)));horse.setAge(0);horse.setTamed(true);horse.setOwner(player);horse.setItemSlot(EquipmentSlot.SADDLE,new ItemStack(Items.SADDLE));horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.22);if(heavy())horse.setItemSlot(EquipmentSlot.BODY,new ItemStack(Items.IRON_HORSE_ARMOR));horses.add(horse);h.assertTrue(n.startRiding(horse,true,false),"fixture mounts original NPC on actual horse");
            var r=source.requests().get(i);requests.add(new WarMilitiaRequest(r.id(),r.actorId(),r.armyId(),r.unitId(),r.characterId(),loadout,1,r.kind(),r.role(),r.wage(),r.requestedAtTick()));
        }
        first=requests.getFirst();var core=WarServerEvents.activate(server);core.bindAuthority((a,b)->a.equals(first.actorId())&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        h.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),actor,first.armyId(),first.unitId(),loadout,1)).status()==WarResult.Status.APPLIED,"one real three-person mounted unit");server.overworld().getDataStorage().saveAndJoin();
        h.setBlock(scene(new BlockPos(4,1,33)),Blocks.CHEST);h.setBlock(scene(new BlockPos(7,1,33)),Blocks.CHEST);var a=(ChestBlockEntity)l.getBlockEntity(h.absolutePos(scene(new BlockPos(4,1,33))));var t=(ChestBlockEntity)l.getBlockEntity(h.absolutePos(scene(new BlockPos(7,1,33))));for(int i=0;i<3;i++)a.setItem(i,new ItemStack(Items.IRON_SPEAR));t.setItem(0,new ItemStack(Items.EMERALD,64));
        var armory=ItemLocation.block(UUID.randomUUID(),l.dimension().identifier().toString(),a.getBlockPos());var treasury=ItemLocation.block(UUID.randomUUID(),l.dimension().identifier().toString(),t.getBlockPos());armory.bindBlock(server);treasury.bindBlock(server);var rights=RightsStateData.get(server);var owner=SubjectRef.character(actor);rights.ensureContext(armory.id(),"test:armory",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(treasury.id(),"test:treasury",owner,owner,AccessPolicy.PRIVATE);
        for(var n:source.npcs()){var person=SubjectRef.character(n.getCharacterId());rights.ensureContext(ItemLocation.personal(n.getCharacterId(),n.getUUID()).id(),"test:bag",person,person,AccessPolicy.PRIVATE);rights.ensureContext(ItemLocation.equipment(n.getCharacterId(),n.getUUID()).id(),"test:equipment",person,person,AccessPolicy.PRIVATE);}
        try{var ports=core.bindNativeMobilization(source.sources());var root=server.getWorldPath(LevelResource.ROOT);field=core.bindFieldExecution(new WarFieldRuntime.Sources(source.sources().characters(),source.sources().injuries(),CombatEncounterData.get(server),source.sources().life()),WarNativeBindings.currentBuild(),()->new WarDispatchJournal(root.resolve("data/"+WarDispatchJournal.FILE_NAME)),()->new WarCohesionJournal(root.resolve("data/"+WarCohesionJournal.FILE_NAME)),new WarCohesionPolicy(WarCohesionPolicy.Rules.prototype()),()->new WarCavalryJournal(root.resolve("data/"+WarCavalryJournal.FILE_NAME)),evidencePolicy());return new Fixture(source,requests,horses,core,ports,armory,treasury,player,h.absolutePos(scene(BlockPos.ZERO)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void lifecycle(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var npcs=f.source().npcs();var enemies=new ArrayList<NpcBase>();UUID[] order={null},encounter={null};long[] priorWave={0};
        var sequence=h.startSequence().thenWaitUntil(()->{boolean ready=true;for(var r:f.requests())ready&=f.ports().configure(r,f.treasury(),f.armory(),List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SPEAR)),1)))==WarMobilizationPorts.Status.APPLIED;h.assertTrue(ready,"configure three original cavalry kits");})
          .thenExecute(()->{for(var r:f.requests())h.assertTrue(f.core().mobilization().orElseThrow().begin(r).outcome()==WarMobilizationService.Outcome.ACCEPTED,"mobilize originals as one unit");})
          .thenWaitUntil(()->{for(var r:f.requests())h.assertTrue(f.core().mobilization().orElseThrow().activePerson(r.characterId()).isPresent(),"three active original services");h.assertTrue(field.reports(first.actorId(),first.armyId()).size()==3,"three actual reports");})
          .thenExecute(()->order[0]=issue(f,InitialWarDefinitions.RALLY,absolute(f,new BlockPos(12,1,16))))
          .thenWaitUntil(()->{
              if(phase().equals("early-charge")){
                  h.assertTrue(field.transmission(first.actorId(),first.armyId(),order[0]).orElseThrow().received().isPresent(),"rally must actually arrive before the next command");
                  var rally=position(absolute(f,new BlockPos(12,1,16)));
                  h.assertTrue(npcs.stream().anyMatch(n->!WarNativeBindings.currentBuild().mobility().arrived(n,rally)),"regression requires an original rider still away from assembly");
                  h.assertTrue(field.cavalry(first.actorId(),first.armyId(),first.unitId()).isEmpty(),"no sortie exists to authorize this initial regrouping");
              }else{var reports=field.reports(first.actorId(),first.armyId());h.assertTrue(reports.stream().allMatch(r->r.order().filter(order[0]::equals).isPresent()&&r.state()==WarFieldReport.State.REGROUPING),"original riders physically reach the rally before selecting a charge");}
          })
          .thenExecute(()->{
              var rear=position(absolute(f,new BlockPos(12,1,16)));var front=position(absolute(f,new BlockPos(36,1,16)));
              for(int i=0;i<3;i++){var p=WarCavalryFormation.objective(rear,front,i,3);enemies.add(enemy(h,new BlockPos(p.x(),p.y(),p.z())));}own=List.copyOf(npcs);opponents=List.copyOf(enemies);
              if(phase().equals("blocked"))for(int x=18;x<=19;x++)for(int z=3;z<=30;z++)l.setBlockAndUpdate(absolute(f,new BlockPos(x,3,z)),Blocks.STONE.defaultBlockState());
              if(phase().equals("single"))order[0]=mapCharge(h,f,absolute(f,new BlockPos(36,0,16)));else order[0]=issue(f,InitialWarDefinitions.CHARGE,absolute(f,new BlockPos(36,1,16)));
          }).thenWaitUntil(()->{
              h.assertTrue(field.cavalry(first.actorId(),first.armyId(),first.unitId()).isPresent(),"wait for the first durable cycle observation");
              var s=state();h.assertTrue(s.wave()>=1&&s.phase()==WarCavalry.Phase.REFORM&&s.spent().size()==3,"one whole native cycle must disengage/reform: "+diagnostic());
          }).thenExecute(()->{if(!phase().equals("blocked"))verifyWave(h,npcs,1);});
        if(phase().equals("save")){
            sequence.thenExecute(()->{for(var e:enemies)e.discard();for(var id:battles.values())NpcCombatRuntime.finish(l,id,l.getGameTime());opponents=List.of();capture=proof(f,npcs);capture.addProperty("stage","ACTIVE_REFORM");}).thenSucceed();return;
        }
        if(phase().equals("blocked")){
            sequence.thenExecute(()->{h.assertTrue(hits.isEmpty(),"loaded low-roof barrier prevents every rider from charging through or attacking");for(var n:npcs)h.assertTrue(runs.getOrDefault(n.getUUID(),Map.of()).getOrDefault(1L,0.0)<2,"blocked riders return without advancing into the roof");order[0]=issue(f,InitialWarDefinitions.RETREAT,absolute(f,new BlockPos(12,1,16)));});
        }else{
            sequence.thenWaitUntil(()->h.assertTrue(state().wave()>=2&&state().phase()==WarCavalry.Phase.REFORM&&state().spent().size()==3,"second whole real cycle returns: "+diagnostic())).thenExecute(()->verifyWave(h,npcs,2))
              .thenExecute(()->{h.assertTrue(launches.get(2L)-launches.get(1L)>=200,"native waves preserve recovery deadline");priorWave[0]=state().wave();npcs.getFirst().stopRiding();order[0]=issue(f,InitialWarDefinitions.CHARGE,absolute(f,new BlockPos(36,1,16)));})
              .thenIdle(240).thenExecute(()->{h.assertTrue(state().wave()==priorWave[0],"one dismounted original prevents a fresh group charge");h.assertTrue(field.cohesion(first.actorId(),first.armyId(),first.unitId()).orElseThrow().organization()==10000,"orders/dismount do not reset or duplicate organization");order[0]=issue(f,InitialWarDefinitions.RETREAT,absolute(f,new BlockPos(12,1,16)));});
        }
        sequence.thenWaitUntil(()->h.assertTrue(field.reports(first.actorId(),first.armyId()).stream().allMatch(r->r.order().filter(order[0]::equals).isPresent()&&r.state()==WarFieldReport.State.REGROUPING),"whole original unit actually withdraws"))
          .thenExecute(()->{for(var e:enemies)e.discard();for(var id:battles.values())NpcCombatRuntime.finish(l,id,l.getGameTime());opponents=List.of();for(var r:f.requests())f.core().mobilization().orElseThrow().demobilize(r.id(),r.actorId());})
          .thenWaitUntil(()->released(h,f,npcs)).thenExecute(()->{h.assertTrue(maximumStep<1.5,"all observed movement remains native without teleport");for(var perBody:hits.values())for(int count:perBody.values())h.assertTrue(count<=1,"no repeated contact inside one wave");capture=proof(f,npcs);capture.addProperty("stage","RELEASED");}).thenSucceed();
    }
    private static void resume(GameTestHelper h){
        try{
            var l=h.getLevel();var server=l.getServer();var root=server.getWorldPath(LevelResource.ROOT);var saved=read(root.resolve("war-m4b1-save.json"));account=UUID.fromString(saved.get("account").getAsString());var origin=BlockPos.of(saved.get("origin").getAsLong());var player=player(h,account,origin.offset(8,1,16));
            var plans=List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/"+WarKitAdapter.FILE_NAME))));var requests=saved.getAsJsonArray("rows").asList().stream().map(row->plans.stream().map(WarKitRecord::request).filter(r->r.id().toString().equals(row.getAsJsonObject().get("contract").getAsString())).findFirst().orElseThrow()).toList();first=requests.getFirst();
            var sources=WarNativeMobilizationGameTests.sources(server);var core=WarServerEvents.existing(server).orElseThrow();core.pause();core.bindAuthority((a,b)->a.equals(first.actorId())&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);var ports=core.bindNativeMobilization(sources);field=core.bindFieldExecution(new WarFieldRuntime.Sources(sources.characters(),sources.injuries(),CombatEncounterData.get(server),sources.life()));
            for(int x=origin.getX()>>4;x<=(origin.getX()+64)>>4;x++)for(int z=origin.getZ()>>4;z<=(origin.getZ()+40)>>4;z++)l.setChunkForced(x,z,true);
            var npcs=new ArrayList<NpcBase>();var horses=new ArrayList<Horse>();var f=new Fixture(null,requests,horses,core,ports,plans.getFirst().armory(),ports.provisions().find(first.id()).orElseThrow().terms().treasury(),player,origin);UUID[] retreat={null};
            h.startSequence().thenWaitUntil(()->{
                npcs.clear();horses.clear();for(var raw:saved.getAsJsonArray("rows")){var row=raw.getAsJsonObject();var n=l.getEntity(UUID.fromString(row.get("body").getAsString()));var horse=l.getEntity(UUID.fromString(row.get("horse").getAsString()));h.assertTrue(n instanceof NpcBase&&horse instanceof Horse,"load original rider/horse passenger tree");npcs.add((NpcBase)n);horses.add((Horse)horse);}
                h.assertTrue(ports.reconcile(core.mobilization().orElseThrow().service().orElseThrow().records()).eligible(),"original shared evidence reconciles");
            }).thenExecute(()->{
                for(int i=0;i<3;i++){var n=npcs.get(i);riders.add(n.getUUID());var row=saved.getAsJsonArray("rows").get(i).getAsJsonObject();h.assertTrue(n.getVehicle()==horses.get(i)&&!n.isNoAi()&&!horses.get(i).isNoAi(),"same actual original rider/horse");h.assertTrue(WarNativeBindings.currentBuild().checkpoints().read(n).value().orElseThrow().nextAttack()==row.get("cooldown").getAsLong(),"same original body cooldown");if(row.has("appearance"))h.assertTrue(row.get("appearance").getAsString().equals(n.getAppearanceProfile().toString()),"war recovery preserves the author's original appearance profile");}
                h.assertTrue(state().wave()==saved.get("wave").getAsLong()&&state().spent().size()==3&&state().nextLaunchAt()==saved.get("nextLaunchAt").getAsLong(),"durable wave/contact fences survive new JVM");core.activate();
            }).thenIdle(35).thenExecute(()->{
                h.assertTrue(hits.isEmpty()&&state().wave()==saved.get("wave").getAsLong(),"no recovered contact or offline free wave");h.assertTrue(state().phase()==WarCavalry.Phase.REFORM&&state().nextLaunchAt()>=saved.get("nextLaunchAt").getAsLong(),"recovery keeps deadline and requires reformation");retreat[0]=issue(f,InitialWarDefinitions.RETREAT,origin.offset(12,1,16));
            }).thenWaitUntil(()->h.assertTrue(field.reports(first.actorId(),first.armyId()).stream().allMatch(r->r.order().filter(retreat[0]::equals).isPresent()&&r.state()==WarFieldReport.State.REGROUPING),"reopened real unit withdraws"))
              .thenExecute(()->{for(var r:requests)core.mobilization().orElseThrow().demobilize(r.id(),r.actorId());}).thenWaitUntil(()->released(h,f,npcs))
              .thenExecute(()->{capture=proof(f,npcs);capture.addProperty("stage","RELEASED");capture.addProperty("priorProcess",saved.get("process").getAsLong());capture.addProperty("attackReplays",hits.size());}).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("cavalry recovery fixture",e);}
    }
    private static void released(GameTestHelper h,Fixture f,List<NpcBase> npcs){
        for(var r:f.requests())h.assertTrue(f.core().mobilization().orElseThrow().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED,"real service released");
        var a=f.armory().resolve(h.getLevel().getServer(),WarNativeMobilizationGameTests.sources(h.getLevel().getServer()).characters()).orElseThrow().container();h.assertTrue(a.countItem(Items.IRON_SPEAR)==3,"all three original loaned spears returned");for(var n:npcs)h.assertTrue(n.getMainHandItem().isEmpty()&&n.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE)&&!n.isNoAi(),"original private tools and AI retained");
    }
    private static WarCavalry state(){return field.cavalry(first.actorId(),first.armyId(),first.unitId()).orElseThrow();}
    private static void verifyWave(GameTestHelper h,List<NpcBase> npcs,long wave){
        h.assertTrue(hits.values().stream().mapToInt(m->m.getOrDefault(wave,0)).sum()>0,"wave has actual native spear damage: "+diagnostic());
        for(var n:npcs){h.assertTrue(hits.getOrDefault(n.getUUID(),Map.of()).getOrDefault(wave,0)<=1,"at most one actual contact per original rider/wave");h.assertTrue(runs.getOrDefault(n.getUUID(),Map.of()).getOrDefault(wave,0.0)>=6,"every original rider physically sallies at least six blocks before regrouping: "+diagnostic());}
    }
    private static String diagnostic(){return state()+" hits="+hits+" contactEvidence="+contactEvidence+" reports="+field.reports(first.actorId(),first.armyId());}
    private static WarCavalryPorts.Policy evidencePolicy(){
        var delegate=new WarCavalryPolicy(WarCavalryPolicy.Rules.prototype());return new WarCavalryPorts.Policy(){
            public WarCavalry plan(Optional<WarCavalry> a,WarCavalryFrame f,boolean reformed,boolean interrupted){return delegate.plan(a,f,reformed,interrupted);}
            public boolean permits(WarCavalryPorts.Contact c,long now){boolean allowed=delegate.permits(c,now);if(contactEvidence.size()<64)contactEvidence.add("run="+c.distance()+",speed="+c.speed()+",age="+(now-c.movingAt())+",alignment="+c.alignment()+",allowed="+allowed);return allowed;}
            public long reformTicks(){return delegate.reformTicks();}public long maximumObservationGap(){return delegate.maximumObservationGap();}
        };
    }
    private static UUID issue(Fixture f,WarKey kind,BlockPos absolute){var unit=f.core().session().orElseThrow().unit(first.unitId()).orElseThrow();var command=new WarCommand.IssueOrder(UUID.randomUUID(),f.core().session().orElseThrow().generation(),first.actorId(),first.armyId(),first.unitId(),unit.revision(),new WarIntent(kind,1,position(absolute)));var result=field.dispatch(command);if(result.outcome()!=WarDispatchService.Outcome.ACCEPTED)throw new IllegalStateException("native cycle dispatch: "+result);return command.operationId();}
    private static UUID mapCharge(GameTestHelper h,Fixture f,BlockPos ground){
        var at=ground.offset(-2,1,0);f.player().setPos(at.getX()+.5,at.getY(),at.getZ()+.5);var map=MapItem.create(h.getLevel(),ground.getX(),ground.getZ(),(byte)0,true,true);f.player().setItemInHand(InteractionHand.MAIN_HAND,map);
        try{var source=f.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL).withSuppressedOutput();h.assertTrue(h.getLevel().getServer().getCommands().getDispatcher().execute("saga war select "+first.unitId(),source)==1&&h.getLevel().getServer().getCommands().getDispatcher().execute("saga war mode charge",source)==1,"real player selects whole unit and charge mode");}catch(Exception e){throw new IllegalStateException(e);}
        var hit=h.getLevel().clip(new ClipContext(f.player().getEyePosition(),new Vec3(ground.getX()+.5,ground.getY()+.999,ground.getZ()+.5),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,f.player()));h.assertTrue(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(ground),"actual visible charge objective map click");f.player().gameMode.useItemOn(f.player(),h.getLevel(),map,InteractionHand.MAIN_HAND,hit);return null;
    }
    private static ServerPlayer player(GameTestHelper h,UUID id,BlockPos at){var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(id,"CavalryCommander"));p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);p.setNoGravity(true);p.setGameMode(GameType.SURVIVAL);h.getLevel().addNewPlayer(p);var c=CharacterRegistry.ensureCurrent(p);CharacterRegistryData.get(h.getLevel().getServer()).bindNpcBody(c.id(),h.getLevel().dimension().identifier().toString(),p.getUUID(),h.getLevel().getGameTime());return p;}
    private static NpcBase enemy(GameTestHelper h,BlockPos at){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);n.setNoAi(true);n.setNoGravity(false);n.setCommand(NpcCommand.STAY);n.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);n.setHealth(1000);n.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);var registry=CharacterRegistryData.get(h.getLevel().getServer());registry.ensureNpcCharacter(n.getCharacterId(),"native cycle hostile fixture",NpcSocialClass.COMMONER,NpcOffice.NONE,"neutral",h.getLevel().getGameTime());registry.bindNpcBody(n.getCharacterId(),h.getLevel().dimension().identifier().toString(),n.getUUID(),h.getLevel().getGameTime());return n;}
    private static UUID encounter(GameTestHelper h,List<NpcBase> own,List<NpcBase> enemies){var id=UUID.randomUUID();CombatEncounterService.start(CombatEncounterData.get(h.getLevel().getServer()),id,null,h.getLevel().dimension().identifier().toString(),enemies.getFirst().blockPosition(),64,List.of(new CombatSide(UUID.randomUUID(),"original cavalry",null,new HashSet<>(own.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE),new CombatSide(UUID.randomUUID(),"explicit hostile fixture",null,new HashSet<>(enemies.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE)),h.getLevel().getGameTime());return id;}
    private static BlockPos absolute(Fixture f,BlockPos p){return f.origin().offset(p);}
    private static WarIntent.Position position(BlockPos p){return new WarIntent.Position(WarKey.parse("minecraft:overworld"),p.getX(),p.getY(),p.getZ());}
    private static JsonObject proof(Fixture f,List<NpcBase> npcs){
        var p=new JsonObject();p.addProperty("process",ProcessHandle.current().pid());p.addProperty("account",account.toString());p.addProperty("origin",f.origin().asLong());p.addProperty("people",3);p.addProperty("heavy",heavy());p.addProperty("wave",state().wave());p.addProperty("nextLaunchAt",state().nextLaunchAt());p.addProperty("maximumNativeStep",maximumStep);p.add("hits",new Gson().toJsonTree(hits));p.add("runs",new Gson().toJsonTree(runs));p.add("contactEvidence",new Gson().toJsonTree(contactEvidence));p.add("launches",new Gson().toJsonTree(launches));p.add("budgets",new Gson().toJsonTree(field.budgets()));var rows=new JsonArray();for(int i=0;i<3;i++){var n=npcs.get(i);var row=new JsonObject();row.addProperty("contract",f.requests().get(i).id().toString());row.addProperty("body",n.getUUID().toString());row.addProperty("horse",f.horses().get(i).getUUID().toString());row.addProperty("cooldown",WarNativeBindings.currentBuild().checkpoints().read(n).value().orElseThrow().nextAttack());row.addProperty("appearance",n.getAppearanceProfile().toString());rows.add(row);}p.add("rows",rows);return p;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e)throws Exception{if(!enabled()||capture==null||!(e.getServer() instanceof GameTestServer))return;var root=e.getServer().getWorldPath(LevelResource.ROOT);var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64*1024*1024L)).getCompoundOrEmpty("Data");capture.addProperty("gameTime",data.getLongOr("Time",0L));capture.addProperty("dayTime",data.getLongOr("DayTime",0L));Files.writeString(root.resolve("war-m4b1-"+phase()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(capture));}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
}
