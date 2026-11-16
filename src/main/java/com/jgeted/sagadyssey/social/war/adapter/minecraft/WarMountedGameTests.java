package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.*;
import java.nio.file.*;
import com.google.gson.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Opt-in generated world; all ownership, mounts, gear and hostile encounters are explicit fixture inputs. */
@GameTestHolder("sagadyssey_war_m4a") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarMountedGameTests {
    private static final String TEMPLATE="sagadyssey_war_m4a:empty";
    private static final Set<UUID> soldiers=new HashSet<>();
    private static final Set<UUID> hitBy=new HashSet<>();
    private static int offset;
    private static JsonObject capture;
    private static Horse interception;
    private static UUID interceptionShooter;
    private static Vec3 restorePosition;
    private static boolean placed,enemyCollision,friendlyCollision,enemyHorseDamaged;
    private static String phase(){return System.getProperty("sagadyssey.war.m4a.phase","single");}
    private static BlockPos scene(BlockPos p){return p.offset(offset,0,0);}
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.m4a.test");}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event)throws Exception{
        if(!enabled() || !(event.getServer() instanceof GameTestServer))return;
        soldiers.clear();hitBy.clear();capture=null;interception=null;interceptionShooter=null;placed=false;enemyCollision=false;friendlyCollision=false;enemyHorseDamaged=false;offset=phase().equals("save")?2048:0;var level=event.getServer().overworld();
        if(phase().equals("resume")){
            var saved=JsonParser.parseString(Files.readString(event.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m4a-save.json"))).getAsJsonObject();
            if(saved.get("process").getAsLong()==ProcessHandle.current().pid())throw new IllegalStateException("distinct JVM required");
            event.getServer().getWorldData().overworldData().setGameTime(saved.get("gameTime").getAsLong());event.getServer().getWorldData().overworldData().setDayTime(saved.get("dayTime").getAsLong());
        }
        level.getStructureManager().getOrCreate(Identifier.parse(TEMPLATE)).load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:"+(phase().equals("resume")?"[1,1,1]":"[56,9,48]")+",entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()&&!phase().equals("media")?List.of(new TestFunction("war_m4a","original_mounted_"+phase(),TEMPLATE,5000,0,true,phase().equals("resume")?WarMountedGameTests::resume:WarMountedGameTests::lifecycle)):List.of();}
    @SubscribeEvent public static void damage(LivingDamageEvent.Post event){
        if(enabled() && event.getSource().getEntity() instanceof NpcBase npc && soldiers.contains(npc.getUUID()) && event.getNewDamage()>0)hitBy.add(npc.getUUID());
        if(enabled() && interception!=null && restorePosition==null && event.getEntity()==interception && event.getSource().getDirectEntity() instanceof AbstractArrow && event.getNewDamage()>0)enemyHorseDamaged=true;
    }
    @SubscribeEvent public static void arrow(EntityJoinLevelEvent event){
        if(!enabled() || interception==null || placed || !(event.getEntity() instanceof AbstractArrow arrow) || arrow.getOwner()==null || !arrow.getOwner().getUUID().equals(interceptionShooter))return;
        var point=arrow.position().add(arrow.getDeltaMovement().scale(2));interception.teleportTo(point.x,point.y-.7,point.z);interception.setNoGravity(true);placed=true;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void impact(ProjectileImpactEvent event){
        if(!enabled() || interception==null || !(event.getProjectile() instanceof AbstractArrow arrow) || !(event.getRayTraceResult() instanceof EntityHitResult hit) || hit.getEntity()!=interception)return;
        if(restorePosition==null)enemyCollision=!event.isCanceled();
        else {friendlyCollision=event.isCanceled()&&arrow.isRemoved();interception.teleportTo(restorePosition.x,restorePosition.y,restorePosition.z);interception.setNoGravity(false);}
    }
    private static void lifecycle(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();
        for(int x=0;x<56;x++)for(int z=0;z<48;z++){var p=h.absolutePos(scene(new BlockPos(x,0,z)));level.getChunkAt(p);if(offset!=0)level.setChunkForced(p.getX()>>4,p.getZ()>>4,true);h.setBlock(scene(new BlockPos(x,0,z)),Blocks.STONE);}
        var commander=dummy(h,new BlockPos(3,1,38));var original=WarCandidateGameTests.fixture(h,6,commander.getCharacterId());
        var horses=new ArrayList<Horse>();var requests=new ArrayList<WarMilitiaRequest>();
        for(int i=0;i<6;i++){
            var npc=original.npcs().get(i);soldiers.add(npc.getUUID());npc.setCommand(NpcCommand.STAY);npc.setNoGravity(false);npc.setOnGround(true);npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            npc.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);npc.setHealth(100);npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));npc.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));npc.getPersonalInventory().setItem(4,new ItemStack(Items.HAY_BLOCK,4));npc.getPersonalInventory().setItem(2,new ItemStack(Items.ARROW,6));
            if(i>=2)npc.getPersonalInventory().setItem(3,new ItemStack(Items.IRON_SWORD));
            original.sources().life().setHunger(npc.getCharacterId(),100,level.getGameTime());
            var horse=h.spawn(EntityType.HORSE,scene(new BlockPos(5,1,3+i*5)));horse.setAge(0);horse.setTamed(true);horse.setOwner(commander);horse.setItemSlot(EquipmentSlot.SADDLE,new ItemStack(Items.SADDLE));
            if(i%2==1)horse.setItemSlot(EquipmentSlot.BODY,new ItemStack(Items.IRON_HORSE_ARMOR));horse.setOnGround(true);horses.add(horse);
            h.assertTrue(npc.startRiding(horse,true,false),"original rider mounts existing horse");
            var r=original.requests().get(i);var weapon=i<2?WarLoadout.Weapon.SWORD:i<4?WarLoadout.Weapon.BOW:WarLoadout.Weapon.CROSSBOW;
            var loadout=new WarLoadout(i%2==0?WarLoadout.Mount.LIGHT:WarLoadout.Mount.HEAVY,weapon,false,false);
            requests.add(new WarMilitiaRequest(r.id(),r.actorId(),r.armyId(),UUID.randomUUID(),r.characterId(),loadout.id(),1,r.kind(),r.role(),r.wage(),r.requestedAtTick()));
        }
        var first=requests.getFirst();var core=WarServerEvents.activate(server);core.bindAuthority((a,b)->a.equals(first.actorId())&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        for(var r:requests)h.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),r.actorId(),r.armyId(),r.unitId(),r.loadout(),1)).status()==WarResult.Status.APPLIED,"create original cavalry unit");
        server.overworld().getDataStorage().saveAndJoin();
        h.setBlock(scene(new BlockPos(4,1,42)),Blocks.CHEST);h.setBlock(scene(new BlockPos(7,1,42)),Blocks.CHEST);
        var armoryBlock=(ChestBlockEntity)level.getBlockEntity(h.absolutePos(scene(new BlockPos(4,1,42))));var treasuryBlock=(ChestBlockEntity)level.getBlockEntity(h.absolutePos(scene(new BlockPos(7,1,42))));
        var armory=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),armoryBlock.getBlockPos());var treasury=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),treasuryBlock.getBlockPos());armory.bindBlock(server);treasury.bindBlock(server);
        for(int i=0;i<6;i++)armoryBlock.setItem(i,new ItemStack(i<2?Items.IRON_SWORD:i<4?Items.BOW:Items.CROSSBOW));treasuryBlock.setItem(0,new ItemStack(Items.EMERALD,64));
        var rights=RightsStateData.get(server);var owner=SubjectRef.character(first.actorId());rights.ensureContext(armory.id(),"test:mounted_armory",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(treasury.id(),"test:treasury",owner,owner,AccessPolicy.PRIVATE);
        for(var npc:original.npcs()){var p=SubjectRef.character(npc.getCharacterId());rights.ensureContext(ItemLocation.personal(npc.getCharacterId(),npc.getUUID()).id(),"test:bag",p,p,AccessPolicy.PRIVATE);rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(),npc.getUUID()).id(),"test:equipment",p,p,AccessPolicy.PRIVATE);}
        try{
            var ports=core.bindNativeMobilization(original.sources());var field=core.bindFieldExecution(new WarFieldRuntime.Sources(original.sources().characters(),original.sources().injuries(),CombatEncounterData.get(server),original.sources().life()));
            var orders=new HashMap<UUID,UUID>();var enemies=new ArrayList<NpcBase>();var encounters=new ArrayList<UUID>();
            var sequence=h.startSequence().thenWaitUntil(()->{
                boolean ready=true;for(int i=0;i<6;i++){var r=requests.get(i);ready&=ports.configure(r,treasury,armory,List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(i<2?Items.IRON_SWORD:i<4?Items.BOW:Items.CROSSBOW)),1)))==WarMobilizationPorts.Status.APPLIED;}h.assertTrue(ready,"durable cavalry kit configuration");
            }).thenExecute(()->{for(var r:requests)h.assertTrue(core.mobilization().orElseThrow().begin(r).outcome()==WarMobilizationService.Outcome.ACCEPTED,"mobilize actual mounted body");})
              .thenWaitUntil(()->{for(var r:requests)h.assertTrue(core.mobilization().orElseThrow().activePerson(r.characterId()).isPresent(),"active mounted labor: "+core.mobilization().orElseThrow().service().orElseThrow().find(r.id()));h.assertTrue(field.reports(first.actorId(),first.armyId()).size()==6,"six real mounted reports");})
              .thenExecute(()->{for(int i=0;i<6;i++)orders.put(requests.get(i).id(),issue(h,core,field,requests.get(i),InitialWarDefinitions.HOLD,new BlockPos(24,1,3+i*5)));})
              .thenWaitUntil(()->at(h,field,requests,original.npcs(),orders))
              .thenExecute(()->{
                  var rider=original.npcs().getFirst();var probeHorse=horses.getFirst();var probe=h.absolutePos(scene(new BlockPos(40,1,8)));var goal=new WarIntent.Position(WarKey.parse(level.dimension().identifier().toString()),probe.getX(),probe.getY(),probe.getZ());
                  h.setBlock(scene(new BlockPos(40,3,8)),Blocks.STONE);h.assertTrue(!WarNativeBindings.currentBuild().actors().canStand(rider,goal),"horse plus rider cannot enter a two-block-high tunnel");h.setBlock(scene(new BlockPos(40,3,8)),Blocks.AIR);h.assertTrue(WarNativeBindings.currentBuild().actors().canStand(rider,goal),"loaded open horse footing is available");
                  probeHorse.setOwner(rider);h.assertTrue(WarNativeBindings.currentBuild().mobility().observe(rider,first).mode()==WarMobilityPorts.Mode.BLOCKED,"changed native owner revokes mount use");probeHorse.setOwner(commander);
                  var reference=probeHorse.getPersistentData().get(WarMountReferenceCodec.MOUNT_KEY).copy();probeHorse.getPersistentData().remove(WarMountReferenceCodec.MOUNT_KEY);
                  h.assertTrue(WarNativeBindings.currentBuild().mobility().observe(rider,first).mode()==WarMobilityPorts.Mode.REVIEW && !WarNativeBindings.currentBuild().mobility().prepare(rider,first).usable(),"missing half of a current mount reference is not guessed/replayed");probeHorse.getPersistentData().put(WarMountReferenceCodec.MOUNT_KEY,reference);
                  for(int i=0;i<6;i++){
                      var npc=original.npcs().get(i);var horse=horses.get(i);h.assertTrue(npc.getVehicle()==horse && horse.getControllingPassenger()==npc && !horse.isNoAi(),"native rider/horse control retained");
                      h.assertTrue(WarNativeBindings.currentBuild().mobility().observe(npc,requests.get(i)).mode()==WarMobilityPorts.Mode.RIDING,"matching native mount reference");
                      var enemy=dummy(h,new BlockPos(i<2?25:34,1,3+i*5));enemies.add(enemy);encounters.add(encounter(h,npc,enemy));
                  }
              }).thenWaitUntil(()->h.assertTrue(hitBy.containsAll(soldiers),"every light/heavy rider causes native damage: "+java.util.stream.IntStream.range(0,6).mapToObj(i->i+":hit="+hitBy.contains(original.npcs().get(i).getUUID())+",range="+original.npcs().get(i).distanceToSqr(enemies.get(i))+",foeHealth="+enemies.get(i).getHealth()+",ammo="+original.npcs().get(i).getPersonalInventory().countItem(Items.ARROW)).toList()+" reports="+field.reports(first.actorId(),first.armyId())));
            if(!phase().equals("save")){
                float[] friendlyHealth={horses.get(3).getHealth()};
                sequence.thenExecute(()->{
                    interception=h.spawn(EntityType.HORSE,scene(new BlockPos(34,1,23)));interception.setAge(0);interception.setNoAi(true);interception.setTamed(true);interception.setOwner(enemies.get(4));enemies.get(4).startRiding(interception,true,false);restorePosition=null;placed=false;interceptionShooter=original.npcs().get(4).getUUID();
                }).thenWaitUntil(()->h.assertTrue(enemyCollision&&enemyHorseDamaged,"actual arrow collision damages the actually ridden hostile horse"))
                  .thenExecute(()->{
                      enemies.get(4).stopRiding();interception.discard();var p=h.absolutePos(scene(new BlockPos(34,1,23)));enemies.get(4).teleportTo(p.getX()+.5,p.getY(),p.getZ()+.5);
                      interception=horses.get(3);restorePosition=interception.position();placed=false;
                  }).thenWaitUntil(()->h.assertTrue(friendlyCollision&&interception.getHealth()==friendlyHealth[0],"actual friendly ridden horse intercepts and cancels the arrow without damage"))
                  .thenExecute(()->{interception=null;interceptionShooter=null;});
            }
            sequence.thenExecute(()->{
                  for(var id:encounters)NpcCombatRuntime.finish(level,id,level.getGameTime());for(var enemy:enemies)enemy.discard();
              });
            if(phase().equals("save")){
                sequence.thenExecute(()->{for(int i=0;i<6;i++)orders.put(requests.get(i).id(),issue(h,core,field,requests.get(i),InitialWarDefinitions.RETREAT,new BlockPos(18,1,3+i*5)));})
                        .thenWaitUntil(()->at(h,field,requests,original.npcs(),orders)).thenExecute(()->capture=proof(core,field,requests,original.npcs(),horses,h.absolutePos(scene(BlockPos.ZERO)),"ACTIVE_MOUNTED")).thenSucceed();return;
            }
            sequence.thenExecute(()->{
                  for(var npc:original.npcs())npc.stopRiding();
                  for(int i=0;i<6;i++){h.assertTrue(WarNativeBindings.currentBuild().mobility().observe(original.npcs().get(i),requests.get(i)).mode()==WarMobilityPorts.Mode.FOOT_FALLBACK,"same original person continues on foot");orders.put(requests.get(i).id(),issue(h,core,field,requests.get(i),InitialWarDefinitions.RETREAT,new BlockPos(12,1,3+i*5)));}
              }).thenWaitUntil(()->at(h,field,requests,original.npcs(),orders))
              .thenExecute(()->{
                  // Replacing a horse inside an active contract cannot silently rebind either reference.
                  var npc=original.npcs().getFirst();h.assertTrue(npc.startRiding(horses.get(1),true,false),"fixture places rider on another original horse");
                  h.assertTrue(!WarNativeBindings.currentBuild().mobility().observe(npc,first).usable()&&!WarNativeBindings.currentBuild().mobility().prepare(npc,first).usable(),"different active mount is refused");npc.stopRiding();
                  for(var r:requests)core.mobilization().orElseThrow().demobilize(r.id(),r.actorId());
              }).thenWaitUntil(()->{for(var r:requests)h.assertTrue(core.mobilization().orElseThrow().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED,"release actual service and return original gear");})
              .thenExecute(()->{
                  h.assertTrue(armoryBlock.countItem(Items.IRON_SWORD)==2&&armoryBlock.countItem(Items.BOW)==2&&armoryBlock.countItem(Items.CROSSBOW)==2,"six original weapons returned");
                  for(int i=0;i<6;i++){var npc=original.npcs().get(i);h.assertTrue(!npc.isNoAi() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE)&&npc.getPersonalInventory().countItem(Items.LEAD)==0,"private tools, original AI and no free lead");h.assertTrue(horses.get(i).getOwnerReference().getUUID().equals(commander.getUUID())&&horses.get(i).isSaddled(),"native horse owner and saddle unchanged");}
                  capture=proof(core,field,requests,original.npcs(),horses,h.absolutePos(scene(BlockPos.ZERO)),"RELEASED");
              }).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("mounted native fixture",e);}
    }
    private static void at(GameTestHelper h,WarFieldRuntime field,List<WarMilitiaRequest> requests,List<NpcBase> npcs,Map<UUID,UUID> orders){
        for(int i=0;i<requests.size();i++){
            var r=requests.get(i);var t=field.transmission(r.actorId(),r.armyId(),orders.get(r.id())).orElseThrow();h.assertTrue(t.received().isPresent(),"durably received mounted order");
            var p=t.station(t.members().getFirst());h.assertTrue(WarNativeBindings.currentBuild().mobility().arrived(npcs.get(i),p) && WarNativeBindings.currentBuild().mobility().pose(npcs.get(i)).distanceSquared(p)<=1.5,"actual native horse/foot movement to station "+i+" pose="+WarNativeBindings.currentBuild().mobility().pose(npcs.get(i))+" goal="+p+" reports="+field.reports(r.actorId(),r.armyId()));
        }
    }
    private static UUID issue(GameTestHelper h,WarServerRuntime core,WarFieldRuntime field,WarMilitiaRequest r,WarKey kind,BlockPos relative){
        var p=h.absolutePos(scene(relative));var unit=core.session().orElseThrow().unit(r.unitId()).orElseThrow();var command=new WarCommand.IssueOrder(UUID.randomUUID(),core.session().orElseThrow().generation(),r.actorId(),r.armyId(),r.unitId(),unit.revision(),new WarIntent(kind,1,new WarIntent.Position(WarKey.parse(h.getLevel().dimension().identifier().toString()),p.getX(),p.getY(),p.getZ())));
        var result=field.dispatch(command);h.assertTrue(result.outcome()==WarDispatchService.Outcome.ACCEPTED,"mounted dispatch accepted: "+result);return command.operationId();
    }
    private static NpcBase dummy(GameTestHelper h,BlockPos p){
        var npc=h.spawn(NpcEntityTypes.NPC_BASE.get(),scene(p));npc.setNoAi(true);npc.setNoGravity(false);npc.setCommand(NpcCommand.STAY);npc.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);npc.setHealth(1000);var registry=CharacterRegistryData.get(h.getLevel().getServer());long now=h.getLevel().getGameTime();
        registry.ensureNpcCharacter(npc.getCharacterId(),"native mounted fixture",NpcSocialClass.COMMONER,NpcOffice.NONE,"neutral",now);registry.bindNpcBody(npc.getCharacterId(),h.getLevel().dimension().identifier().toString(),npc.getUUID(),now);return npc;
    }
    private static UUID encounter(GameTestHelper h,NpcBase npc,NpcBase enemy){var id=UUID.randomUUID();CombatEncounterService.start(CombatEncounterData.get(h.getLevel().getServer()),id,null,h.getLevel().dimension().identifier().toString(),enemy.blockPosition(),64,List.of(new CombatSide(UUID.randomUUID(),"mounted originals",null,Set.of(npc.getCharacterId()),EngagementRule.LETHAL_FORCE),new CombatSide(UUID.randomUUID(),"explicit hostile",null,Set.of(enemy.getCharacterId()),EngagementRule.LETHAL_FORCE)),h.getLevel().getGameTime());return id;}
    private static JsonObject proof(WarServerRuntime core,WarFieldRuntime field,List<WarMilitiaRequest> requests,List<NpcBase> npcs,List<Horse> horses,BlockPos origin,String stage){
        var result=new JsonObject();result.addProperty("process",ProcessHandle.current().pid());result.addProperty("stage",stage);result.addProperty("people",npcs.size());result.addProperty("origin",origin.asLong());result.addProperty("nativeDamagingRiders",hitBy.size());result.addProperty("enemyHorseNativeCollisionDamage",enemyCollision&&enemyHorseDamaged);result.addProperty("friendlyRiddenHorsePhysicalInterception",friendlyCollision);var rows=new JsonArray();
        for(int i=0;i<npcs.size();i++){
            var n=npcs.get(i);var horse=horses.get(i);var r=requests.get(i);var row=new JsonObject();
            row.addProperty("contract",r.id().toString());row.addProperty("body",n.getUUID().toString());row.addProperty("horse",horse.getUUID().toString());row.addProperty("owner",horse.getOwnerReference().getUUID().toString());row.addProperty("health",horse.getHealth());row.addProperty("horseArmor",horse.getItemBySlot(EquipmentSlot.BODY).toString());
            row.addProperty("reference",n.getPersistentData().getCompoundOrEmpty(WarMountReferenceCodec.RIDER_KEY).toString());row.addProperty("cooldown",WarNativeBindings.currentBuild().checkpoints().read(n).value().orElseThrow().nextAttack());row.addProperty("ammo",n.getPersonalInventory().countItem(Items.ARROW));row.addProperty("organization",field.cohesion(r.actorId(),r.armyId(),r.unitId()).orElseThrow().organization());rows.add(row);
        }result.add("rows",rows);result.add("budgets",new Gson().toJsonTree(field.budgets()));return result;
    }
    private static void resume(GameTestHelper h){
        try{
            var level=h.getLevel();var server=level.getServer();var root=server.getWorldPath(LevelResource.ROOT);var saved=JsonParser.parseString(Files.readString(root.resolve("war-m4a-save.json"))).getAsJsonObject();
            h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"distinct native JVM restart");
            var plans=List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/"+WarKitAdapter.FILE_NAME))));h.assertTrue(plans.size()==6,"six original cavalry contracts");
            var requests=new ArrayList<WarMilitiaRequest>();for(var row:saved.getAsJsonArray("rows")){var id=UUID.fromString(row.getAsJsonObject().get("contract").getAsString());requests.add(plans.stream().filter(p->p.request().id().equals(id)).findFirst().orElseThrow().request());}
            var first=requests.getFirst();var sources=WarNativeMobilizationGameTests.sources(server);var core=WarServerEvents.existing(server).orElseThrow();core.pause();core.bindAuthority((a,b)->a.equals(first.actorId())&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
            var ports=core.bindNativeMobilization(sources);var field=core.bindFieldExecution(new WarFieldRuntime.Sources(sources.characters(),sources.injuries(),CombatEncounterData.get(server),sources.life()));
            var origin=BlockPos.of(saved.get("origin").getAsLong());for(int x=origin.getX()>>4;x<=(origin.getX()+56)>>4;x++)for(int z=origin.getZ()>>4;z<=(origin.getZ()+48)>>4;z++)level.setChunkForced(x,z,true);
            var npcs=new ArrayList<NpcBase>();var horses=new ArrayList<Horse>();var orders=new HashMap<UUID,UUID>();
            h.startSequence().thenWaitUntil(()->{
                npcs.clear();horses.clear();for(var raw:saved.getAsJsonArray("rows")){var row=raw.getAsJsonObject();var n=level.getEntity(UUID.fromString(row.get("body").getAsString()));var horse=level.getEntity(UUID.fromString(row.get("horse").getAsString()));h.assertTrue(n instanceof NpcBase && horse instanceof Horse,"load exact original passenger tree");npcs.add((NpcBase)n);horses.add((Horse)horse);}
                h.assertTrue(ports.reconcile(core.mobilization().orElseThrow().service().orElseThrow().records()).eligible(),"shared original labor/gear/provisions reconcile");
            }).thenExecute(()->{
                for(int i=0;i<6;i++){
                    var npc=npcs.get(i);var horse=horses.get(i);var row=saved.getAsJsonArray("rows").get(i).getAsJsonObject();var r=requests.get(i);soldiers.add(npc.getUUID());
                    h.assertTrue(npc.getVehicle()==horse && horse.getControllingPassenger()==npc,"same native mounted original person");
                    h.assertTrue(WarNativeBindings.currentBuild().mobility().observe(npc,r).mode()==WarMobilityPorts.Mode.RIDING,"same mount use after restart");
                    h.assertTrue(row.get("reference").getAsString().equals(npc.getPersistentData().getCompoundOrEmpty(WarMountReferenceCodec.RIDER_KEY).toString()),"original use reference retained");
                    h.assertTrue(row.get("cooldown").getAsLong()==WarNativeBindings.currentBuild().checkpoints().read(npc).value().orElseThrow().nextAttack() && row.get("ammo").getAsInt()==npc.getPersonalInventory().countItem(Items.ARROW),"original attack cooldown and consumed arrows retained");
                    h.assertTrue(row.get("organization").getAsInt()==field.cohesion(r.actorId(),r.armyId(),r.unitId()).orElseThrow().organization(),"mount restoration cannot reset organization");
                    h.assertTrue(row.get("owner").getAsString().equals(horse.getOwnerReference().getUUID().toString()) && row.get("horseArmor").getAsString().equals(horse.getItemBySlot(EquipmentSlot.BODY).toString()) && horse.isSaddled() && !horse.isNoAi(),"native owner/saddle/armor/AI preserved");
                }core.activate();
            }).thenIdle(30).thenExecute(()->{
                h.assertTrue(hitBy.isEmpty(),"old native attacks not replayed on resume");
                for(int i=0;i<6;i++){
                    var p=origin.offset(12,1,3+i*5);var r=requests.get(i);var u=core.session().orElseThrow().unit(r.unitId()).orElseThrow();var cmd=new WarCommand.IssueOrder(UUID.randomUUID(),core.session().orElseThrow().generation(),r.actorId(),r.armyId(),r.unitId(),u.revision(),new WarIntent(InitialWarDefinitions.RETREAT,1,new WarIntent.Position(WarKey.parse(level.dimension().identifier().toString()),p.getX(),p.getY(),p.getZ())));
                    h.assertTrue(field.dispatch(cmd).outcome()==WarDispatchService.Outcome.ACCEPTED,"new received retreat after reopen");orders.put(r.id(),cmd.operationId());
                }
            }).thenWaitUntil(()->at(h,field,requests,npcs,orders)).thenExecute(()->{for(var r:requests)core.mobilization().orElseThrow().demobilize(r.id(),r.actorId());})
              .thenWaitUntil(()->{for(var r:requests)h.assertTrue(core.mobilization().orElseThrow().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED,"reopened original cavalry service releases");})
              .thenExecute(()->{
                  var armory=plans.getFirst().armory().resolve(server,sources.characters()).orElseThrow().container();h.assertTrue(armory.countItem(Items.IRON_SWORD)==2&&armory.countItem(Items.BOW)==2&&armory.countItem(Items.CROSSBOW)==2,"all native loaned weapons returned after restart");
                  for(var npc:npcs)h.assertTrue(npc.getMainHandItem().isEmpty() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE) && !npc.isNoAi(),"original body private tools and AI retained");
                  capture=proof(core,field,requests,npcs,horses,origin,"RELEASED");capture.addProperty("priorProcess",saved.get("process").getAsLong());capture.addProperty("attackReplays",hitBy.size());
              }).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("mounted native recovery fixture",e);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event)throws Exception{
        if(!enabled() || capture==null || !(event.getServer() instanceof GameTestServer))return;
        var root=event.getServer().getWorldPath(LevelResource.ROOT);var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64*1024*1024L)).getCompoundOrEmpty("Data");capture.addProperty("gameTime",data.getLongOr("Time",0L));capture.addProperty("dayTime",data.getLongOr("DayTime",0L));
        Files.writeString(root.resolve("war-m4a-"+phase()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(capture));
    }

}
