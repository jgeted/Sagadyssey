package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.api.WarFieldReport;
import com.jgeted.sagadyssey.social.work.*;
import io.netty.channel.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in loopback dedicated-server scene. All player actions come from real native clients over TCP. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarMultiplayerGameTests {
    private static final Map<MinecraftServer,Scene> SCENES=new WeakHashMap<>();
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.multiplayer.server");}
    private static boolean mounted(){return Boolean.getBoolean("sagadyssey.war.multiplayer.mounted");}
    private static final class Scene {
        final MinecraftServer server;final Path root;final NpcBase npc;final ItemStack map;final Map<UUID,Integer> joins=new HashMap<>();
        final Map<String,Map<String,AtomicInteger>> packets=new ConcurrentHashMap<>();final List<Map<String,Object>> requests=new CopyOnWriteArrayList<>();
        final List<Map<String,Object>> swordContacts=new ArrayList<>();
        final Set<UUID> initialized=new HashSet<>();NpcBase foe;Horse horse;boolean attacked,actualAttack,stopping;long started,publicationBlockedAt=-1;
        Scene(MinecraftServer s,Path r,NpcBase n){server=s;root=r;npc=n;started=s.overworld().getGameTime();map=MapItem.create(s.overworld(),8,8,(byte)0,true,true);Arrays.fill(MapItem.getSavedData(map,s.overworld()).colors,(byte)40);}
    }
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!enabled())return;var s=event.getServer();if(!s.isDedicatedServer())throw new IllegalStateException("ordinary dedicated server required");
        var root=Path.of(System.getProperty("sagadyssey.war.multiplayer.root")).toAbsolutePath().normalize();
        if(!s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().startsWith(root)||Files.exists(root.resolve("network-state.json")))throw new IllegalStateException("fresh isolated scene required");
        var l=s.overworld();l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,s);l.getGameRules().set(GameRules.SPAWN_MOBS,false,s);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)l.setChunkForced(x,z,true);
        for(int x=-4;x<28;x++)for(int z=-4;z<25;z++){l.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());for(int y=80;y<87;y++)l.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
        l.setBlockAndUpdate(new BlockPos(5,80,9),Blocks.WHITE_BANNER.defaultBlockState());
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(5.5,80,5.5);n.setProfession(NpcProfession.WORKER);n.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);n.setNpcName("Network Worker");n.setCommand(mounted()?NpcCommand.FOLLOW:NpcCommand.STAY);n.setPersistenceRequired();
        for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
        if(!l.addFreshEntity(n))throw new IllegalStateException("initial scene body unavailable");NpcLifeData.get(s).ensureRegistered(n,l.getGameTime());NpcLifeData.get(s).setHunger(n.getCharacterId(),300,l.getGameTime());
        var scene=new Scene(s,root,n);SCENES.put(s,scene);write(root.resolve("network-state.json"),state(scene));
    }
    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer p))return;var scene=SCENES.get(p.level().getServer());if(scene==null)return;
        String name=p.getGameProfile().name();if(!Set.of("WarLord","WarOther").contains(name))throw new IllegalStateException("unexpected loopback test account");
        scene.joins.merge(p.getUUID(),1,Integer::sum);CharacterRegistry.ensureCurrent(p);
        if(scene.initialized.add(p.getUUID())){
            p.setGameMode(GameType.SURVIVAL);p.teleportTo(name.equals("WarLord")?5.5:10.5,80,mounted()?7.5:8.5);
            p.getInventory().clearContent();p.getInventory().setItem(1,new ItemStack(Items.IRON_SWORD));p.getInventory().setItem(2,new ItemStack(Items.BREAD,32));p.getInventory().setItem(7,new ItemStack(Items.EMERALD,64));
            p.getInventory().setItem(3,scene.map.copy()); // two ordinary chart copies sharing one vanilla map ID
            p.getInventory().setItem(4,InstrumentItem.create(Items.GOAT_HORN,p.level().registryAccess().lookupOrThrow(Registries.INSTRUMENT).listElements().findFirst().orElseThrow()));
            p.inventoryMenu.broadcastChanges();
            if(name.equals("WarLord")&&mounted()){
                var horse=new Horse(EntityType.HORSE,p.level());horse.setPos(5.5,80,5.5);horse.setAge(0);horse.setTamed(true);horse.setOwner(p);horse.setPersistenceRequired();horse.setItemSlot(EquipmentSlot.SADDLE,new ItemStack(Items.SADDLE));horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.22);p.level().addFreshEntity(horse);
                if(!scene.npc.startRiding(horse,true,false))throw new IllegalStateException("original owned horse fixture must mount");scene.horse=horse;
            }
            if(name.equals("WarOther"))p.level().getServer().getPlayerList().op(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
        }
        var connection=p.connection.getConnection();var channel=connection.channel();
        if(channel==null||connection.getRemoteAddress()==null||!channel.getClass().getName().contains("SocketChannel"))throw new IllegalStateException("real TCP connection required");
        var counts=scene.packets.computeIfAbsent(name,k->new ConcurrentHashMap<>());
        channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler","war_native_observer",new ChannelInboundHandlerAdapter(){
            @Override public void channelRead(ChannelHandlerContext c,Object packet)throws Exception{
                counts.computeIfAbsent(packet.getClass().getSimpleName(),k->new AtomicInteger()).incrementAndGet();
                if(packet instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.RequestPayload payload){
                    var r=payload.value();
                    scene.requests.add(Map.of("account",p.getUUID().toString(),"name",name,"session",r.session().toString(),"action",r.action().name(),"ticket",r.ticket().toString()));
                }
                c.fireChannelRead(packet);
            }
        }));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        var scene=SCENES.get(event.getServer());if(scene==null||scene.stopping)return;var s=scene.server;long now=s.overworld().getGameTime();
        if(now-scene.started>18000)throw new IllegalStateException("native multiplayer test timed out");
        attackIfReached(scene);
        if(now%4!=0)return;
        var control=read(scene.root.resolve("network-control.json"));
        if(Files.isRegularFile(scene.root.resolve("network-abort.json"))){scene.stopping=true;s.halt(false);return;}
        if(control.has("attack")&&control.get("attack").getAsBoolean()&&!scene.attacked){
            scene.attacked=true;var n=scene.npc;var f=new NpcBase(NpcEntityTypes.NPC_BASE.get(),s.overworld());f.setPos(mounted()?18.5:n.getX()+2,mounted()?80:n.getY(),mounted()?9.5:n.getZ());f.setProfession(NpcProfession.HEAVY);f.setFaction(FactionRegistry.get("sagadyssey:wilderness"));f.setNoAi(true);f.setPersistenceRequired();
            for(var slot:EquipmentSlot.values())f.setItemSlot(slot,ItemStack.EMPTY);f.getAttribute(Attributes.MAX_HEALTH).setBaseValue(64);f.setHealth(64);s.overworld().addFreshEntity(f);scene.foe=f;
            attackIfReached(scene);
        }
        var facts=state(scene);
        try{write(scene.root.resolve("network-state.json"),facts);scene.publicationBlockedAt=-1;}
        catch(IllegalStateException e){if(!(e.getCause() instanceof AccessDeniedException))throw e;if(scene.publicationBlockedAt<0)scene.publicationBlockedAt=now;if(now-scene.publicationBlockedAt>40)throw e;return;}
        if(control.has("complete")&&control.get("complete").getAsBoolean()){
            if(!Boolean.TRUE.equals(facts.get("ended"))||!Boolean.TRUE.equals(facts.get("closingPaid"))||!Boolean.TRUE.equals(facts.get("actualSwordDamage")))throw new IllegalStateException("incomplete network workflow");
            facts.put("complete",true);write(scene.root.resolve("network-final-proof.json"),facts);scene.stopping=true;s.halt(false);
        }
    }
    private static void attackIfReached(Scene scene){if(scene.foe!=null&&!scene.actualAttack&&scene.npc.distanceToSqr(scene.foe)<=12.25){scene.actualAttack=true;if(!scene.npc.hurtServer(scene.server.overworld(),scene.server.overworld().damageSources().mobAttack(scene.foe),1))throw new IllegalStateException("actual in-reach self-defense attack required");}}
    @SubscribeEvent public static void damage(LivingDamageEvent.Post event){
        if(!enabled()||!(event.getSource().getDirectEntity() instanceof NpcBase attacker)||!(attacker.level() instanceof net.minecraft.server.level.ServerLevel level))return;
        var v=SCENES.get(level.getServer());if(v==null||attacker!=v.npc||event.getEntity()!=v.foe||event.getNewDamage()<=0)return;
        v.swordContacts.add(Map.of("at",level.getGameTime(),"body",attacker.getUUID(),"sword",attacker.getMainHandItem().is(Items.IRON_SWORD),"mountedOnOriginalHorse",v.horse!=null&&attacker.getVehicle()==v.horse,"damage",event.getNewDamage(),"position",List.of(attacker.getX(),attacker.getY(),attacker.getZ())));
    }
    private static Map<String,Object> state(Scene v){
        var s=v.server;var l=s.overworld();var n=v.npc;var f=new LinkedHashMap<String,Object>();f.put("process",ProcessHandle.current().pid());f.put("ordinaryDedicatedServer",true);f.put("gameTime",l.getGameTime());f.put("body",n.getUUID());f.put("person",n.getCharacterId());f.put("entity",n.getId());f.put("profession",n.getProfession());f.put("command",n.getCommand());f.put("bread",n.getPersonalInventory().count(i->i.is(Items.BREAD)));f.put("npcEmeralds",n.getPersonalInventory().count(i->i.is(Items.EMERALD)));f.put("sword",n.getPersonalInventory().count(i->i.is(Items.IRON_SWORD))+(n.getMainHandItem().is(Items.IRON_SWORD)?1:0));f.put("heldSword",n.getMainHandItem().is(Items.IRON_SWORD));f.put("workBlocked",WorkCommitmentRuntime.blocksCivil(n));f.put("actualSwordDamage",v.foe!=null&&v.foe.getHealth()<64);f.put("encounters",CombatEncounterData.get(s).encounters().size());
        f.put("mountedScene",mounted());f.put("actualInReachAttack",v.actualAttack);if(v.horse!=null){f.put("horse",v.horse.getUUID());f.put("mountedAtEnd",n.getVehicle()==v.horse);f.put("horseAlive",v.horse.isAlive());}
        f.put("npcPosition",List.of(n.getX(),n.getY(),n.getZ()));if(v.horse!=null)f.put("horsePosition",List.of(v.horse.getX(),v.horse.getY(),v.horse.getZ()));
        f.put("nativeSwordContacts",List.copyOf(v.swordContacts));
        if(v.horse!=null){var horse=v.horse;var path=horse.getNavigation().getPath();f.put("nativeMotion",Map.of("sameNavigation",n.getNavigation()==horse.getNavigation(),"navigating",horse.getNavigation().isInProgress(),"hasWanted",horse.getMoveControl().hasWanted(),"wanted",List.of(horse.getMoveControl().getWantedX(),horse.getMoveControl().getWantedY(),horse.getMoveControl().getWantedZ()),"pathNext",path==null?-1:path.getNextNodeIndex(),"pathSize",path==null?0:path.getNodeCount()));}
        var players=new LinkedHashMap<String,Object>();for(var p:s.getPlayerList().getPlayers()){
            var row=new LinkedHashMap<String,Object>();row.put("account",p.getUUID());row.put("person",CharacterRegistry.currentId(p));row.put("nativeClass",p.getClass().getName());row.put("channel",p.connection.getConnection().channel().getClass().getName());row.put("remote",String.valueOf(p.connection.getConnection().getRemoteAddress()));row.put("joins",v.joins.getOrDefault(p.getUUID(),0));row.put("emeralds",p.getInventory().countItem(Items.EMERALD));players.put(p.getGameProfile().name(),row);
            row.put("operator",s.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(p.getGameProfile())));row.put("heldMap",p.getMainHandItem().is(Items.FILLED_MAP));var mapId=p.getMainHandItem().get(net.minecraft.core.component.DataComponents.MAP_ID);row.put("mapId",mapId==null?-1:mapId.id());
            if(p.getGameProfile().name().equals("WarLord")){
                var actor=CharacterRegistry.currentId(p);var army=WarMusterEntry.armyId(actor,"NetworkArmy");var unit=WarMusterEntry.unitId(army,"Workers");f.put("actor",actor);f.put("army",army);f.put("unit",unit);f.put("payerEmeralds",p.getInventory().countItem(Items.EMERALD));f.put("recruited",ServiceRelationshipData.get(s).hasActiveBetween(actor,n.getCharacterId(),l.getGameTime()));
                WarMusterRuntime.existing(s).ifPresent(m->{
                    f.put("musterStatus",m.status(actor));f.put("armyOwned",m.book().owns(actor,army));f.put("unitReady",m.book().unit(unit).filter(u->u.recorded()&&m.book().durable(u)).isPresent());
                    m.book().latest(n.getCharacterId()).ifPresent(o->{f.put("contract",o.id());f.put("offerStage",o.stage());f.put("draw",o.draw());f.put("probability",o.probability());f.put("ended",o.stage()==WarMusterEntry.Stage.ENDED&&m.book().durable(o));});
                });
                WarServerEvents.existing(s).ifPresent(core->{
                    core.session().flatMap(session->session.unit(unit)).ifPresent(u->{f.put("orderSequence",u.lastIssuedSequence());u.currentOrder().ifPresent(o->{f.put("receivedKind",o.intent().kind().toString());f.put("receivedOrder",o.id());});});
                    f.put("holding",core.field().map(field->field.reports(actor,army).stream().anyMatch(r->r.unit().equals(unit)&&r.state()==WarFieldReport.State.HOLDING)).orElse(false));f.put("postBound",core.commandMedia().flatMap(m->m.post(p)).isPresent());
                    core.field().ifPresent(field->{f.put("fieldReports",field.reports(actor,army).stream().map(r->Map.of("state",r.state().name(),"reason",r.reason(),"at",r.observedAt(),"position",r.position())).toList());field.cavalry(actor,army,unit).ifPresent(c->f.put("cavalry",c));field.cohesion(actor,army,unit).ifPresent(c->f.put("cohesion",c));});
                    var other=s.getPlayerList().getPlayers().stream().filter(q->q.getGameProfile().name().equals("WarOther")).findFirst();other.ifPresent(q->f.put("otherPermission",core.permission(CharacterRegistry.currentId(q),army).name()));
                    if(f.containsKey("contract"))core.nativeMobilization().ifPresent(a->a.provisions().find((UUID)f.get("contract")).ifPresent(c->f.put("closingPaid",c.pending().isEmpty()&&a.provisions().durable(c.terms().id())&&c.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)&&c.arrears(l.getGameTime()).signum()==0)));
                });
            }
        }
        f.put("players",players);f.put("joins",v.joins.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().toString(),Map.Entry::getValue)));
        var packets=new LinkedHashMap<String,Object>();v.packets.forEach((name,counts)->{var p=new TreeMap<String,Integer>();counts.forEach((k,c)->p.put(k,c.get()));packets.put(name,p);});f.put("packets",packets);f.put("mapRequests",List.copyOf(v.requests));return f;
    }
    private static JsonObject read(Path p){try{return Files.isRegularFile(p)?JsonParser.parseString(Files.readString(p)).getAsJsonObject():new JsonObject();}catch(Exception ignored){return new JsonObject();}}
    private static void write(Path p,Map<String,Object> value){try{var temp=p.resolveSibling(p.getFileName()+".writing");Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(value));Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException("native scene proof",e);}}
}
