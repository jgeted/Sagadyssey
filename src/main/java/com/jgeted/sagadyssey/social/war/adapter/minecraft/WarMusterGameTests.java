package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.chat.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import java.nio.file.Files;
import java.util.*;

/** Native headless acceptance, with a listed ServerPlayer and no-op wire. Army/consent/claims are all production actions, never fixture injections. */
@GameTestHolder("sagadyssey_war_m4b2b") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarMusterGameTests {
    private static final List<io.netty.channel.embedded.EmbeddedChannel> CHANNELS=new ArrayList<>();
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){
        for(var c:CHANNELS)c.finishAndReleaseAll();CHANNELS.clear();
        if(enabled()&&phase().equals("save"))try{
            var root=event.getServer().getWorldPath(LevelResource.ROOT);var file=root.resolve("war-m4b2b-save.json");if(!Files.isRegularFile(file))return;var proof=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var level=net.minecraft.nbt.NbtIo.readCompressed(root.resolve("level.dat"),net.minecraft.nbt.NbtAccounter.create(16L*1024*1024)).getCompoundOrEmpty("Data");
            proof.addProperty("gameTime",level.getLongOr("Time",0));proof.addProperty("dayTime",level.getLongOr("DayTime",0));Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(proof));
        }catch(Exception failure){throw new IllegalStateException("capture stopped native clock",failure);}
    }
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.m4b2b.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.m4b2b.phase","muster");}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST) public static void started(ServerStartedEvent e){if(enabled()&&e.getServer() instanceof GameTestServer)try{
        var l=e.getServer().overworld();if(phase().equals("resume")){var saved=JsonParser.parseString(Files.readString(e.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m4b2b-save.json"))).getAsJsonObject();e.getServer().getWorldData().overworldData().setGameTime(saved.get("gameTime").getAsLong());e.getServer().getWorldData().overworldData().setDayTime(saved.get("dayTime").getAsLong());}
        l.getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_war_m4b2b:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[40,4,32],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }catch(Exception x){throw new IllegalStateException(x);}}
    @SubscribeEvent public static void missingCore(ServerAboutToStartEvent event)throws java.io.IOException{
        if(enabled()&&phase().equals("missing-core")){var file=event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/sagadyssey_war.dat");Files.move(file,file.resolveSibling("sagadyssey_war.dat.test-retained"));}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_m4b2b",phase(),"sagadyssey_war_m4b2b:empty",5000,0,true,phase().equals("resume")?WarMusterGameTests::resume:phase().equals("missing-core")?WarMusterGameTests::blocked:WarMusterGameTests::muster)):List.of();}
    private static void muster(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();var sceneOrigin=absolute(h,BlockPos.ZERO);int floor=phase().equals("save")?-3:0;
        if(phase().equals("save"))for(int x=sceneOrigin.getX()>>4;x<=(sceneOrigin.getX()+40)>>4;x++)for(int z=sceneOrigin.getZ()>>4;z<=(sceneOrigin.getZ()+32)>>4;z++)level.setChunkForced(x,z,true);
        for(int x=0;x<40;x++)for(int z=0;z<28;z++){for(int y=floor+1;y<10;y++)h.setBlock(scene(new BlockPos(x,y,z)),Blocks.AIR);h.setBlock(scene(new BlockPos(x,floor,z)),Blocks.STONE);}
        var player=player(h,"MusterLord",new BlockPos(5,1,7));var stranger=player(h,"OtherLord",new BlockPos(8,1,7));
        var actor=CharacterRegistry.currentId(player);var npcs=new ArrayList<NpcBase>();var ids=new ArrayList<UUID>();UUID[] order={null};
        var life=NpcLifeData.get(server);for(int i=0;i<4;i++){
            var npc=h.spawn(NpcEntityTypes.NPC_BASE.get(),scene(new BlockPos(3+i,floor+1,4)));var at=absolute(h,new BlockPos(3+i,floor+1,4));npc.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);npc.setNoGravity(false);npc.setOnGround(true);npc.setProfession(NpcProfession.WORKER);npc.setCommand(NpcCommand.WORK);
            for(var slot:EquipmentSlot.values())npc.setItemSlot(slot,ItemStack.EMPTY);npc.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));npc.getPersonalInventory().clearContent();npc.getTransportInventory().clearContent();
            npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));npc.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));life.onRecruited(npc,actor,level.getGameTime());life.setHunger(npc.getCharacterId(),300,level.getGameTime());npcs.add(npc);
        }
        player.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));
        h.assertTrue(command(player,"saga war muster create TestArmy")==1,"ordinary create command");
        var runtime=WarMusterRuntime.existing(server).orElseThrow();var army=WarMusterEntry.armyId(actor,"TestArmy");var unit=WarMusterEntry.unitId(army,"First");
        h.startSequence().thenWaitUntil(()->h.assertTrue(runtime.book().owns(actor,army),"owner checkpoint"))
            .thenExecute(()->{
                h.assertTrue(command(player,"saga war muster unit TestArmy First sword")==1,"ordinary unit command");
                h.assertTrue(command(player,"saga war muster unit TestArmy Unknown unknown_profile")==0,"unknown profiles denied without a phantom unit");
                h.assertTrue(!runtime.book().owns(CharacterRegistry.currentId(stranger),army),"another current life / OP cannot command this army");
            }).thenWaitUntil(()->h.assertTrue(runtime.book().unit(unit).filter(u->u.recorded()&&runtime.book().durable(u)).isPresent(),"core unit saved and acknowledged: "+runtime.status(actor)))
            .thenExecute(()->{
                for(int i=0;i<4;i++){
                    var npc=npcs.get(i);player.setPos(npc.getX(),npc.getY(),npc.getZ()+3);((ListedPlayer)player).inbox.clear();player.lookAt(EntityAnchorArgument.Anchor.EYES,npc.getEyePosition());
                    h.assertTrue(command(player,"saga war muster offer TestArmy First")==1,"actual raycast offer command; feedback="+((ListedPlayer)player).inbox.stream().map(Component::getString).toList());
                    var click=((ListedPlayer)player).inbox.stream().map(c->c.getStyle().getClickEvent()).filter(ClickEvent.RunCommand.class::isInstance).map(ClickEvent.RunCommand.class::cast).reduce((a,b)->b).orElseThrow();
                    var ticket=UUID.fromString(click.command().substring(click.command().lastIndexOf(' ')+1));long seed=seed(i<3);npc.getRandom().setSeed(seed);
                    h.assertTrue(command(player,click.command().substring(1))==1,"actual chat confirmation command uses one NPC choice");ids.add(ticket);
                    h.assertTrue(command(player,"saga war muster confirm "+ticket)==1,"duplicate confirmation is an idempotent lookup");
                }
            }).thenWaitUntil(()->{h.assertTrue(ids.size()==4,"all four raycast offers must be recorded before inspecting their decisions");h.assertTrue(ids.subList(0,3).stream().allMatch(id->runtime.book().offer(id).filter(o->o.stage()==Stage.ACTIVE&&runtime.book().durable(o)).isPresent())
                    &&runtime.book().offer(ids.get(3)).filter(o->o.stage()==Stage.DECLINED&&runtime.book().durable(o)).isPresent(),"three real accepted services / one refusal: "+runtime.status(actor));})
            .thenExecute(()->{
                var core=WarServerEvents.existing(server).orElseThrow();var military=core.mobilization().orElseThrow();h.assertTrue(military.activeUnit(unit).size()==3,"only the three willing original NPCs mobilize");
                h.assertTrue(!WorkCommitmentRuntime.blocksCivil(npcs.get(3)),"refusal never claims labor");
                for(int i=0;i<3;i++){var o=runtime.book().offer(ids.get(i)).orElseThrow();h.assertTrue(core.servicePermits(o.request(),level.getGameTime()),"current exact agreement");life.setHunger(npcs.get(i).getCharacterId(),650,level.getGameTime());}
                var mapCenter=absolute(h,new BlockPos(16,1,13));var map=MapItem.create(level,mapCenter.getX(),mapCenter.getZ(),(byte)0,true,true);player.setItemSlot(EquipmentSlot.MAINHAND,map);Arrays.fill(MapItem.getSavedData(map,level).colors,(byte)40);
                h.assertTrue(command(player,"saga war map")==1,"ordinary map entry works with production-owned army");
                var maps=WarMapRuntime.get(server);var update=maps.begin(player);h.assertTrue(update.view().orElseThrow().units().stream().anyMatch(u->u.id().equals(unit)&&u.members()==3),"new unit appears on command map");
                h.assertTrue(CharacterRegistryData.get(server).bodyOf(actor)==null,"player issuer does not depend on a fake NPC body-table entry");
                h.assertTrue(core.field().orElseThrow().localRoute(actor,army,unit).isPresent(),"online original player can originate a local order without NPC registration");
                var target=absolute(h,new BlockPos(16,1,13));var view=update.view().orElseThrow();int x=(int)Math.round(view.sheet().pixelX(target.getX())),z=(int)Math.round(view.sheet().pixelZ(target.getZ()));
                var preview=maps.act(player,new Request(view.session(),view.revision(),Action.PREVIEW,0,unit,WarMapView.Mode.HOLD,x,z,new UUID(0,0))).view().orElseThrow();h.assertTrue(preview.preview().isPresent(),"ordinary new unit can preview a map order");
                var issued=maps.act(player,new Request(preview.session(),preview.revision(),Action.ISSUE,0,unit,WarMapView.Mode.HOLD,0,0,preview.preview().orElseThrow().ticket())).view().orElseThrow().receipt().orElseThrow();h.assertTrue(issued.outcome()==WarMapView.Outcome.ACCEPTED,"map order accepted using actual muster authority");order[0]=issued.order().orElseThrow();
            }).thenWaitUntil(()->{var reports=WarServerEvents.existing(server).orElseThrow().field().orElseThrow().reports(actor,army);h.assertTrue(reports.size()==3&&reports.stream().allMatch(r->r.order().filter(order[0]::equals).isPresent()&&r.state()==WarFieldReport.State.HOLDING),"three original people physically hold the production army's map order: "+reports);})
            .thenWaitUntil(()->h.assertTrue(npcs.subList(0,3).stream().allMatch(n->n.getPersonalInventory().count(s->s.is(Items.BREAD))<8),"actual food eaten from original private bags"))
            .thenWaitUntil(()->{var ports=WarServerEvents.existing(server).orElseThrow().nativeMobilization().orElseThrow();h.assertTrue(ids.subList(0,3).stream().allMatch(id->ports.provisions().find(id).filter(v->v.pending().isEmpty()&&ports.provisions().durable(id)).isPresent()),"meal and supply history durably settled before snapshot");})
            .thenExecute(()->{
                if(phase().equals("save")){
                    var facts=new LinkedHashMap<String,Object>();facts.put("actor",actor.toString());facts.put("account",player.getUUID().toString());facts.put("name",player.getGameProfile().name());facts.put("army",army.toString());facts.put("unit",unit.toString());
                    var origin=absolute(h,BlockPos.ZERO);facts.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));facts.put("contracts",ids.stream().map(UUID::toString).toList());facts.put("bodies",npcs.stream().map(n->n.getUUID().toString()).toList());
                    facts.put("bread",npcs.stream().map(n->n.getPersonalInventory().count(s->s.is(Items.BREAD))).toList());facts.put("draws",ids.stream().map(id->runtime.book().offer(id).orElseThrow().draw()).toList());facts.put("probabilities",ids.stream().map(id->runtime.book().offer(id).orElseThrow().probability()).toList());facts.put("stage","ACTIVE");facts.put("headlessNoOpWire",true);proof(h,facts);h.succeed();return;
                }
                var first=runtime.book().offer(ids.get(0)).orElseThrow();h.assertTrue(command(player,"saga war muster retire "+first.id())==1,"ordinary retire command");
                var second=runtime.book().offer(ids.get(1)).orElseThrow();var services=ServiceRelationshipData.get(server);services.endDelegation(second.id(),actor,second.request().characterId(),"NPC撤回本次军役",level.getGameTime());
                h.assertTrue(!WarServerEvents.existing(server).orElseThrow().servicePermits(second.request(),level.getGameTime()),"revocation immediately blocks queued actions before muster scheduler cleanup");
                var third=runtime.book().offer(ids.get(2)).orElseThrow();runtime.retire(player,third.id());
            }).thenWaitUntil(()->h.assertTrue(ids.subList(0,3).stream().allMatch(id->runtime.book().offer(id).filter(o->o.stage()==Stage.ENDED&&runtime.book().durable(o)).isPresent()),"all original labor released: "+runtime.status(actor)))
            .thenWaitUntil(()->h.assertTrue(npcs.subList(0,3).stream().allMatch(n->n.getPersonalInventory().count(s->s.is(Items.EMERALD))>=1),"real prorated emerald payroll is paid from the lord's actual backpack"))
            .thenExecute(()->{
                h.assertTrue(player.getInventory().countItem(Items.EMERALD)==13,"exactly three emeralds spent, none created");
                h.assertTrue(npcs.stream().allMatch(n->n.getMainHandItem().is(Items.IRON_SWORD)&&n.getPersonalInventory().count(s->s.is(Items.IRON_PICKAXE))==1),"self-owned equipment and private tools remain with original people");
                h.assertTrue(npcs.stream().noneMatch(WorkCommitmentRuntime::blocksCivil),"civil work is available again, refusal included");
                for(int i=0;i<3;i++){var o=runtime.book().offer(ids.get(i)).orElseThrow();h.assertTrue(ServiceRelationshipData.get(server).relationship(o.parent()).activeAt(level.getGameTime()),"ordinary employment survived exact military release");}
                var oldInventory=ItemLocation.player(actor,player.getUUID());h.assertTrue(oldInventory.resolve(server,CharacterRegistryData.get(server)).isPresent(),"original current-life source is present");
                var fresh=CharacterRegistryData.get(server).createFreshLife(player);h.assertTrue(!fresh.id().equals(actor)&&oldInventory.resolve(server,CharacterRegistryData.get(server)).isEmpty(),"a new life cannot spend the prior life's inventory authority");
                h.assertTrue(WarServerEvents.existing(server).orElseThrow().permission(fresh.id(),army)!=WarCommandService.Permission.ALLOWED,"new life does not inherit command authority");
                var otherSource=ItemLocation.player(CharacterRegistry.currentId(stranger),stranger.getUUID());h.assertTrue(otherSource.resolve(server,CharacterRegistryData.get(server)).isPresent(),"other current-life source present");server.getPlayerList().remove(stranger);h.assertTrue(otherSource.resolve(server,CharacterRegistryData.get(server)).isEmpty(),"offline player inventory is unavailable");
                proof(h,Map.ofEntries(Map.entry("people",4),Map.entry("activeAccepted",3),Map.entry("declined",1),Map.entry("payrollSpent",3),Map.entry("ordinaryCommands",true),Map.entry("productionAuthorityAndConsent",true),Map.entry("realBackpackAndNpcItems",true),Map.entry("mapPhysicalHold",true),Map.entry("newLifeCannotInherit",true),Map.entry("offlineSourceUnavailable",true),Map.entry("headlessNoOpWire",true),Map.entry("noNormalClientClickAcceptance",true),Map.entry("stage","ENDED")));
            }).thenSucceed();
    }
    private static void resume(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();try{
            var saved=JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT).resolve("war-m4b2b-save.json"))).getAsJsonObject();h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"new JVM, not an in-memory rebind");
            var actor=UUID.fromString(saved.get("actor").getAsString());var army=UUID.fromString(saved.get("army").getAsString());var ids=saved.getAsJsonArray("contracts").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();var bodies=saved.getAsJsonArray("bodies").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();
            var pos=saved.getAsJsonArray("origin");var origin=new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt());
            for(int x=origin.getX()>>4;x<=(origin.getX()+40)>>4;x++)for(int z=origin.getZ()>>4;z<=(origin.getZ()+32)>>4;z++)level.setChunkForced(x,z,true);
            var player=player(h,new GameProfile(UUID.fromString(saved.get("account").getAsString()),saved.get("name").getAsString()),origin.offset(5,1,7),true);h.assertTrue(actor.equals(CharacterRegistry.currentId(player)),"original current player life loaded from original player file");
            var runtime=WarMusterRuntime.existing(server).orElseThrow();var npcs=new ArrayList<NpcBase>();
            h.startSequence().thenWaitUntil(()->{
                npcs.clear();for(var body:bodies){h.assertTrue(level.getEntity(body) instanceof NpcBase,"load the same saved original NPC body");npcs.add((NpcBase)level.getEntity(body));}
                h.assertTrue(WarServerEvents.existing(server).orElseThrow().nativeMobilization().isPresent(),"production adapters restored without fixture binding: "+runtime.status(actor));
                for(int i=0;i<3;i++){var o=runtime.book().offer(ids.get(i)).orElseThrow();h.assertTrue(o.stage()==Stage.ACTIVE&&o.body().equals(npcs.get(i).getUUID())&&WarServerEvents.existing(server).orElseThrow().servicePermits(o.request(),level.getGameTime()),"same original service and exact accepted identity");}
            }).thenExecute(()->{
                for(int i=0;i<4;i++){var o=runtime.book().offer(ids.get(i)).orElseThrow();h.assertTrue(o.draw()==saved.getAsJsonArray("draws").get(i).getAsDouble()&&o.probability()==saved.getAsJsonArray("probabilities").get(i).getAsDouble(),"original decision preserved without resampling");h.assertTrue(npcs.get(i).getPersonalInventory().count(s->s.is(Items.BREAD))==saved.getAsJsonArray("bread").get(i).getAsInt(),"no recovered meal replay");}
                h.assertTrue(runtime.book().entries().size()==6,"one army, one unit and exactly four original offers");h.assertTrue(player.getInventory().countItem(Items.EMERALD)==16,"actual saved payer inventory, no fixture refill");
                for(var id:ids.subList(0,3))h.assertTrue(command(player,"saga war muster retire "+id)==1,"retire original service after new JVM");
            }).thenWaitUntil(()->h.assertTrue(ids.subList(0,3).stream().allMatch(id->runtime.book().offer(id).filter(o->o.stage()==Stage.ENDED&&runtime.book().durable(o)).isPresent()),"recover then release original labor: "+runtime.status(actor)))
            .thenWaitUntil(()->h.assertTrue(npcs.subList(0,3).stream().allMatch(n->n.getPersonalInventory().count(s->s.is(Items.EMERALD))==1),"saved player pays exactly once after restart"))
            .thenExecute(()->{h.assertTrue(player.getInventory().countItem(Items.EMERALD)==13&&npcs.stream().noneMatch(WorkCommitmentRuntime::blocksCivil),"conservation and original work release after restart");proof(h,Map.of("priorProcess",saved.get("process").getAsLong(),"originalBodies",4,"decisionsResampled",0,"mealsReplayed",0,"payrollSpent",3,"stage","ENDED","headlessNoOpWire",true));}).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("muster restart fixture",e);}
    }
    private static void blocked(GameTestHelper h){
        var server=h.getLevel().getServer();var directory=server.getWorldPath(LevelResource.ROOT).resolve("data");try{
            var files=List.of("sagadyssey_war_muster.sgw","sagadyssey_war_mobilization.dat","sagadyssey_war_kits.sgw","sagadyssey_work_commitments.sgw");var before=new HashMap<String,byte[]>();for(var file:files)before.put(file,Files.readAllBytes(directory.resolve(file)));
            h.startSequence().thenIdle(40).thenExecute(()->{try{
                h.assertTrue(!WarServerEvents.existing(server).orElseThrow().data().writable()&&!Files.exists(directory.resolve("sagadyssey_war.dat")),"missing main ledger is never replaced with an empty army");
                h.assertTrue(WarMusterRuntime.existing(server).map(r->!r.problem().isEmpty()).orElse(true),"muster also remains blocked");
                for(var file:files)h.assertTrue(Arrays.equals(before.get(file),Files.readAllBytes(directory.resolve(file))),"orphan source preserved: "+file);
                proof(h,Map.of("missingCoreBlocked",true,"sourceFilesUnchanged",4,"stage","REVIEW"));
            }catch(java.io.IOException e){throw new IllegalStateException(e);}}).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("missing-core fixture",e);}
    }
    private static long seed(boolean yes){for(long s=0;s<100000;s++){var r=net.minecraft.util.RandomSource.create(s);double d=r.nextDouble();if(yes?d<.001:d>.999)return s;}throw new AssertionError("seed search");}
    /** Fixture networking only. It never supplies game authority, NPC willingness or physical-effect receipts. */
    private static final class ListedPlayer extends FakePlayer {
        private final List<Component> inbox=new ArrayList<>();
        private final ServerGamePacketListenerImpl headless;
        ListedPlayer(ServerLevel level,GameProfile profile){super(level,profile);headless=connection;if(headless.getConnection().channel()==null)CHANNELS.add(new io.netty.channel.embedded.EmbeddedChannel(headless.getConnection()));}
        @Override public void initInventoryMenu(){connection=headless;super.initInventoryMenu();}
        @Override public void sendSystemMessage(Component message){if(inbox!=null)inbox.add(message);}
    }
    private static ServerPlayer player(GameTestHelper h,String name,BlockPos pos){
        return player(h,new GameProfile(UUID.randomUUID(),name),absolute(h,phase().equals("save")?pos.below(3):pos),false);
    }
    private static BlockPos scene(BlockPos relative){return relative.offset(phase().equals("save")?2048:0,0,0);}
    private static BlockPos absolute(GameTestHelper h,BlockPos relative){return h.absolutePos(scene(relative));}
    private static ServerPlayer player(GameTestHelper h,GameProfile profile,BlockPos at,boolean restore){
        var level=h.getLevel();var p=new ListedPlayer(level,profile);
        if(restore){var saved=level.getServer().getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(profile)).orElseThrow();com.jgeted.sagadyssey.core.MinecraftApi.loadEntity(p,saved);}
        else p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);p.setNoGravity(true);p.setGameMode(GameType.SURVIVAL);
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol,T listener){}
            @Override public void send(Packet<?> packet,io.netty.channel.ChannelFutureListener listener,boolean flush){}
            @Override public void flushChannel(){}
        };
        var channel=new io.netty.channel.embedded.EmbeddedChannel(connection);
        try{level.getServer().getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));}finally{channel.finishAndReleaseAll();}
        CharacterRegistry.ensureCurrent(p);return p;
    }
    static int command(ServerPlayer p,String text){try{return p.level().getServer().getCommands().getDispatcher().execute(text,p.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL));}catch(Exception e){throw new IllegalStateException(e);}}
    static String latestConfirmation(ServerPlayer player){return ((ListedPlayer)player).inbox.stream().map(c->c.getStyle().getClickEvent()).filter(ClickEvent.RunCommand.class::isInstance).map(ClickEvent.RunCommand.class::cast).reduce((a,b)->b).orElseThrow().command();}
    static String feedback(ServerPlayer p){return p instanceof ListedPlayer v?v.inbox.stream().skip(Math.max(0,v.inbox.size()-4)).map(Component::getString).toList().toString():"";}
    static ServerPlayer battlePlayer(GameTestHelper h,String name,BlockPos relative){return player(h,name,relative);}
    static ServerPlayer restoreBattlePlayer(GameTestHelper h,GameProfile profile,BlockPos absolute){return player(h,profile,absolute,true);}
    static UUID battleOffer(GameTestHelper h,ServerPlayer player,NpcBase npc,String army,String unit){
        return battleOffer(h,player,npc,army,unit,false);
    }
    static UUID battleOffer(GameTestHelper h,ServerPlayer player,NpcBase npc,String army,String unit,boolean staff){
        return battleOffer(h,player,npc,army,unit,staff?WarMilitiaRequest.Role.STAFF:WarMilitiaRequest.Role.COMBATANT);
    }
    static UUID battleOffer(GameTestHelper h,ServerPlayer player,NpcBase npc,String army,String unit,WarMilitiaRequest.Role role){
        player.setPos(npc.getX(),npc.getY(),npc.getZ()+3);
        return offerFacing(h,player,npc,army,unit,role);
    }
    static UUID battleOfferFromAbove(GameTestHelper h,ServerPlayer player,NpcBase npc,String army,String unit){
        player.setPos(npc.getX(),npc.getY()+2.3,npc.getZ());
        return offerFacing(h,player,npc,army,unit,WarMilitiaRequest.Role.COMBATANT);
    }
    private static UUID offerFacing(GameTestHelper h,ServerPlayer player,NpcBase npc,String army,String unit,WarMilitiaRequest.Role role){
        ((ListedPlayer)player).inbox.clear();player.lookAt(EntityAnchorArgument.Anchor.EYES,npc.getEyePosition());
        h.assertTrue(command(player,"saga war muster "+(role==WarMilitiaRequest.Role.ENGINEER?"engineeroffer ":role==WarMilitiaRequest.Role.STAFF?"staffoffer ":"offer ")+army+" "+unit)==1,"ordinary raycast battle offer for "+unit+"; "+((ListedPlayer)player).inbox.stream().map(Component::getString).toList());
        var click=((ListedPlayer)player).inbox.stream().map(c->c.getStyle().getClickEvent()).filter(ClickEvent.RunCommand.class::isInstance).map(ClickEvent.RunCommand.class::cast).reduce((a,b)->b).orElseThrow();
        npc.getRandom().setSeed(seed(true));h.assertTrue(command(player,click.command().substring(1))==1,"ordinary exact chat confirmation");
        var ticket=UUID.fromString(click.command().substring(click.command().lastIndexOf(' ')+1));
        h.assertTrue(WarMusterRuntime.existing(player.level().getServer()).orElseThrow().book().offer(ticket).filter(o->o.request().characterId().equals(npc.getCharacterId())).isPresent(),"fixture raycast selected the intended original person");
        return ticket;
    }
    private static void proof(GameTestHelper h,Map<String,Object> values){var p=new JsonObject();values.forEach((k,v)->p.add(k,new Gson().toJsonTree(v)));p.addProperty("process",ProcessHandle.current().pid());try{Files.writeString(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("war-m4b2b-"+phase()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(p));}catch(Exception e){throw new IllegalStateException(e);}}
}
