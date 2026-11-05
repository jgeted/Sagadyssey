package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService;
import com.jgeted.sagadyssey.social.politics.CharterTradeGovernance;
import com.jgeted.sagadyssey.social.religion.ReligionService;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Headless real-server regression. Client death-screen/restart evidence is recorded separately. */
@GameTestHolder("sagadyssey_identity") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class IdentityLifecycleGameTests {
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_identity:empty");
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;
        try {var l=e.getServer().overworld();l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[40,8,40],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return List.of(
        test("normal_dead_wait_queries_and_old_assets",IdentityLifecycleGameTests::deadWait,500),
        test("first_login_active_relogin_and_respawn_idempotence",IdentityLifecycleGameTests::respawn,100),
        test("real_rescue_keeps_first_identity_and_consumes_emeralds",IdentityLifecycleGameTests::rescue,200),
        test("query_without_current_never_creates_first_identity",IdentityLifecycleGameTests::missing,100),
        test("lawful_witness_and_church_queries_do_not_create",IdentityLifecycleGameTests::churchWitness,1200),
        test("existing_lawful_witness_visibility",com.jgeted.sagadyssey.social.religion.ReligionGameTests::nearbyWitnessLearnsOnlyObservedEventAndDistantPlayerLearnsNothing,1200),
        test("existing_church_fixture_actual_teaching_and_records",com.jgeted.sagadyssey.social.religion.life.ChurchLifeGameTests::concentratedClientFixtureCompletesAllTenJobsForActuallyPresentStudent,2000),
        test("permission_reads_reject_dead_without_new_life",IdentityLifecycleGameTests::permissionReads,600),
        test("valid_existing_client_fixture",NpcCombatClientFixtureGameTests::consolidatedFixtureRunsOnceWithoutChangingOldSquad,1100),
        test("occupied_fixture_remains_rejected",NpcCombatClientFixtureGameTests::occupiedFixtureSiteDoesNotOverwriteUserBlockOrStartIntent,60));}
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> f,int limit){return new TestFunction("identity",name,EMPTY.toString(),limit,0,true,f);}
    private static CompoundTag registry(ServerPlayer p){return CharacterRegistryData.get(p.server).save(new CompoundTag(),p.server.registryAccess());}
    private static int lives(ServerPlayer p){for(var x:registry(p).getList("LifeCounts",Tag.TAG_COMPOUND)){var t=(CompoundTag)x;if(t.getUUID("Account").equals(p.getUUID()))return t.getInt("Count");}return 0;}
    private static int command(ServerPlayer p,String text){try{return p.server.getCommands().getDispatcher().execute(text,p.createCommandSourceStack().withPermission(4));}catch(Exception ex){throw new IllegalStateException("command "+text,ex);}}
    private static NpcBase owned(GameTestHelper h,ServerPlayer p){
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());n.setNpcName("identity-owned");n.setOwner(p);n.setPersistenceRequired();n.setNoAi(true);
        var at=h.absolutePos(new BlockPos(8,1,8));n.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);h.getLevel().addFreshEntity(n);h.assertTrue(n.isOwnedBy(p.getUUID()),"initial valid legacy service");return n;
    }
    private static void queryChecks(GameTestHelper h,ServerPlayer p,NpcBase n){
        int count=lives(p);UUID current=CharacterRegistry.currentId(p);
        for(String cmd:List.of("saga identity get","saga society status","saga household inspect","saga logistics orders","saga politics realm","saga religion records 00000000-0000-0000-0000-000000000001","saga church here","saga church known 00000000-0000-0000-0000-000000000001")){
            command(p,cmd);h.assertTrue(lives(p)==count&&Objects.equals(current,CharacterRegistry.currentId(p)),"query has no creation side effect: "+cmd);
        }
        NpcCombatClientFixture.status(p);
        var attachment=PlayerIdentityAttachments.get(p);var faction=attachment.titleFactionId();var social=attachment.socialClass();var office=attachment.office();
        try {var set=com.jgeted.sagadyssey.npc.identity.command.PlayerIdentityCommand.class.getDeclaredMethod("set",net.minecraft.commands.CommandSourceStack.class,ServerPlayer.class,String.class,String.class,String.class);set.setAccessible(true);
            h.assertTrue((int)set.invoke(null,p.createCommandSourceStack().withPermission(4),p,"COMMONER","NONE","identity:denied")==0,"missing/dead target rejected before attachment writes");
        }catch(Exception ex){throw new IllegalStateException(ex);}
        h.assertTrue(attachment.titleFactionId().equals(faction)&&attachment.socialClass()==social&&attachment.office()==office,"denied identity mutation preserves original attachments");
        h.assertTrue(CharterTradeGovernance.actor(p)==null&&ReligionService.currentCharacter(p)==null,"non-creating shared lookups");
        if(n!=null){
            h.assertTrue(!n.isOwnedBy(p.getUUID())&&n.getActiveOwner()==null,"owner lookup does not revive absent owner");
            var view=NpcProjectionService.forViewer(p,n);h.assertTrue(view.actions().isEmpty()&&view.knownFacts().isEmpty()&&view.systemInsights().isEmpty(),"no private facts/actions for missing observer");
            NpcDialogue.line(n,NpcDialogueContext.CONVERSATION,p);NpcIdentityRelations.addressPrefix(n,p);
        }
        h.assertTrue(lives(p)==count&&Objects.equals(current,CharacterRegistry.currentId(p)),"all non-creating queries preserve life count and binding");
    }
    private static void deadWait(GameTestHelper h){
        var p=NpcCombatIntegrationGameTests.online(h);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();var old=CharacterRegistry.currentId(p);var n=owned(h,p);
        h.setBlock(3,1,3,Blocks.BARREL);var pos=h.absolutePos(new BlockPos(3,1,3));var box=(Container)h.getLevel().getBlockEntity(pos);box.setItem(0,new ItemStack(Items.OAK_LOG,4));
        var rights=RightsStateData.get(p.server);UUID container=UUID.randomUUID();var owner=SubjectRef.character(old);rights.ensureContext(container,"identity-test:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
        long now=h.getLevel().getGameTime();String token="identity-obligation:"+old;var reservation=new AssetReservation(UUID.randomUUID(),container,new ItemFingerprint("minecraft:oak_log",0),2,owner,token,"pre-existing obligation",now,now+4000);
        h.assertTrue(rights.reserve(reservation,4,now),"initial actual goods reserved");
        p.kill();h.assertTrue(!p.isAlive()&&CharacterRegistry.currentId(p)==null,"real death event removes CURRENT");
        long deathAt=h.getLevel().getGameTime();
        h.runAfterDelay(160,()->{
            h.assertTrue(h.getLevel().getGameTime()-deathAt>=160,"normal scheduling crossed at least eight fixture periods");
            h.assertTrue(CharacterRegistry.currentId(p)==null&&lives(p)==1,"dead online player never got a second life");
            queryChecks(h,p,n);CharacterLifecycleEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(p));queryChecks(h,p,n);
            CharacterLifecycleEvents.onDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(p,p.damageSources().genericKill()));
            h.assertTrue(lives(p)==1&&CharacterRegistry.currentId(p)==null,"duplicate death and dead login do not create");
            var reg=CharacterRegistryData.get(p.server);h.assertTrue(!reg.byId(old).alive()&&old.equals(n.getOwnerCharacterId())&&old.equals(reg.compatibilityOwnerCharacter(p.getUUID())),"original death history and old owner mapping remain");
            h.assertTrue(box.getItem(0).getCount()==4&&rights.context(container).defaultBeneficialOwner().equals(owner)&&rights.reservationsForToken(token,h.getLevel().getGameTime()).equals(List.of(reservation)),"old private goods and obligation neither inherited nor cleared");
            Sagadyssey.LOGGER.info("IDENTITY_DEAD_WAIT account={} old={} death={} now={} lifeCount={} current={} privateOak=4 reservedOak=2",p.getUUID(),old,deathAt,h.getLevel().getGameTime(),lives(p),CharacterRegistry.currentId(p));
            NpcCombatIntegrationGameTests.offline(p);h.succeed();
        });
    }
    private static void respawn(GameTestHelper h){
        var p=NpcCombatIntegrationGameTests.online(h);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();var old=CharacterRegistry.currentId(p);
        h.assertTrue(lives(p)==1&&old!=null,"formal first login created one identity");
        CharacterLifecycleEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(p));h.assertTrue(lives(p)==1&&old.equals(CharacterRegistry.currentId(p)),"live relog preserves original");
        p.kill();h.assertTrue(CharacterRegistry.currentId(p)==null,"death before respawn");
        // Exercise the actual respawn callback twice on a new body with the same account;
        // the independent client run supplies the real click and full-process restart evidence.
        var body=new ServerPlayer(p.server,h.getLevel(),p.getGameProfile(),ClientInformation.createDefault());body.connection=p.connection;body.setGameMode(GameType.SURVIVAL);
        var event=new PlayerEvent.PlayerRespawnEvent(body,false);CharacterLifecycleEvents.onRespawn(event);UUID fresh=CharacterRegistry.currentId(body);CharacterLifecycleEvents.onRespawn(event);
        h.assertTrue(fresh!=null&&!fresh.equals(old)&&fresh.equals(CharacterRegistry.currentId(body))&&lives(body)==2,"one legal fresh life; repeat callback idempotent");
        h.assertTrue(!CharacterRegistryData.get(p.server).byId(old).alive()&&CharacterRegistryData.get(p.server).byId(fresh).alive(),"distinct retained history");
        NpcCombatIntegrationGameTests.offline(p);h.succeed();
    }
    private static void rescue(GameTestHelper h){
        var p=NpcCombatIntegrationGameTests.online(h);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.EMERALD,4));var before=CharacterRegistry.current(p);var id=before.id();
        p.kill();h.assertTrue(p.isAlive()&&id.equals(CharacterRegistry.currentId(p))&&lives(p)==1&&before.rescueCount()==1,"real lethal event rescued same first identity");
        h.assertTrue(p.getInventory().countItem(Items.EMERALD)==0,"four actual emeralds consumed");
        h.runAfterDelay(100,()->{h.assertTrue(p.isAlive()&&lives(p)==1&&id.equals(CharacterRegistry.currentId(p)),"rescued identity stable over normal ticks");NpcCombatIntegrationGameTests.offline(p);h.succeed();});
    }
    private static void missing(GameTestHelper h){
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"identity-new");var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),profile,ClientInformation.createDefault());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);
        p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(p.server,connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false)){
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){} @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener){}
        };
        h.assertTrue(lives(p)==0,"no test-created identity");queryChecks(h,p,null);h.assertTrue(lives(p)==0,"queries cannot implement first login");
        CharacterLifecycleEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(p));h.assertTrue(lives(p)==1,"formal first login still works");h.succeed();
    }
    private static boolean invokeBoolean(Class<?> owner,String name,Class<?>[] types,Object... args){
        try {var m=owner.getDeclaredMethod(name,types);m.setAccessible(true);return (boolean)m.invoke(null,args);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void permissionReads(GameTestHelper h){
        var p=NpcCombatIntegrationGameTests.online(h);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();var n=owned(h,p);
        var work=com.jgeted.sagadyssey.social.service.AuthorityScope.WORK;
        var lifeTypes=new Class<?>[]{ServerPlayer.class,NpcBase.class,work.getClass()};
        var groupTypes=new Class<?>[]{ServerPlayer.class,NpcBase.class,work.getClass(),boolean.class};
        h.assertTrue(invokeBoolean(com.jgeted.sagadyssey.npc.command.NpcLifeCommand.class,"authorized",lifeTypes,p,n,work),"existing lawful work authority remains");
        h.assertTrue(invokeBoolean(com.jgeted.sagadyssey.npc.command.network.CommandGroupActionPacket.class,"authorized",groupTypes,p,n,work,false),"existing lawful group authority remains");
        var b=com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests.fixture(h,0);
        p.kill();h.assertTrue(!p.isAlive()&&CharacterRegistry.currentId(p)==null,"real death before permission reads");
        h.runAfterDelay(240,()->{
            var pos=com.jgeted.sagadyssey.core.structure.NaturalVillageLayout.at(b,com.jgeted.sagadyssey.core.structure.NaturalVillageLayout.PUBLIC);
            h.assertTrue(com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.managedStore(p.server,h.getLevel().dimension().location().toString(),pos)!=null,"formal initialization registered actual managed store");
            h.assertTrue(invokeBoolean(com.jgeted.sagadyssey.social.settlement.NaturalStorageAccessEvents.class,"denied",new Class<?>[]{ServerLevel.class,ServerPlayer.class,BlockPos.class},h.getLevel(),p,pos),"dead actor denied managed storage");
            h.assertTrue(!invokeBoolean(com.jgeted.sagadyssey.npc.command.NpcLifeCommand.class,"authorized",lifeTypes,p,n,work),"dead actor denied life authority");
            h.assertTrue(!invokeBoolean(com.jgeted.sagadyssey.npc.command.network.CommandGroupActionPacket.class,"authorized",groupTypes,p,n,work,false),"dead actor denied group authority");
            var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)java.lang.reflect.Proxy.newProxyInstance(IdentityLifecycleGameTests.class.getClassLoader(),new Class<?>[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,m,args)->{
                if(m.getName().equals("player"))return p;
                if(m.getName().equals("enqueueWork")){((Runnable)args[0]).run();return java.util.concurrent.CompletableFuture.completedFuture(null);}
                throw new UnsupportedOperationException(m.getName());
            });
            var profession=n.getProfession();var mode=n.getFarmMode();var command=n.getCommand();
            com.jgeted.sagadyssey.npc.network.NpcProfessionPacket.handle(new com.jgeted.sagadyssey.npc.network.NpcProfessionPacket(n.getId(),"FARMER"),ctx);
            com.jgeted.sagadyssey.npc.network.NpcFarmModePacket.handle(new com.jgeted.sagadyssey.npc.network.NpcFarmModePacket(n.getId(),"CANE"),ctx);
            h.assertTrue(n.getProfession()==profession&&n.getFarmMode()==mode&&n.getCommand()==command,"denied packets preserve role and work state");
            h.assertTrue(lives(p)==1&&CharacterRegistry.currentId(p)==null,"five permission paths never create second life");
            Sagadyssey.LOGGER.info("IDENTITY_PERMISSION_READS account={} tick={} lifeCount=1 current=null fivePathsDenied=true",p.getUUID(),h.getLevel().getGameTime());
            NpcCombatIntegrationGameTests.offline(p);h.succeed();
        });
    }
    private static Object member(Object object,String name){try{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void churchWitness(GameTestHelper h){
        Object fixture;
        try{var m=com.jgeted.sagadyssey.social.religion.ReligionGameTests.class.getDeclaredMethod("fixture",GameTestHelper.class,int.class,int.class,int.class,boolean.class);m.setAccessible(true);fixture=m.invoke(null,h,0,2,6,true);}catch(Exception e){throw new IllegalStateException(e);}
        var temple=(com.jgeted.sagadyssey.social.religion.TempleRecord)member(fixture,"temple");
        var deacon=(NpcBase)member(fixture,"deacon");var recipient=(NpcBase)member(fixture,"recipient");
        var near=NpcCombatIntegrationGameTests.online(h);var remote=NpcCombatIntegrationGameTests.online(h);var dead=NpcCombatIntegrationGameTests.online(h);
        var missing=new ServerPlayer(near.server,h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"unboundwitness"),ClientInformation.createDefault());missing.connection=near.connection;
        var at=temple.counter();for(var p:List.of(near,dead,missing))p.setPos(at.getX()+.5,at.getY(),at.getZ()-.5);remote.setPos(near.getX()+100,near.getY(),near.getZ());
        dead.setGameMode(GameType.SURVIVAL);dead.getInventory().clearContent();UUID old=CharacterRegistry.currentId(dead);dead.kill();
        h.assertTrue(!dead.isAlive()&&CharacterRegistry.currentId(dead)==null,"real death before actual alms event");
        var data=CharacterRegistryData.get(near.server);int before=data.allCharacters().size();
        for(var p:List.of(dead,missing)){
            h.assertTrue(command(p,"saga church here")==0&&command(p,"saga church known "+temple.id())==0,"unbound/dead church queries reject");
            var status=com.jgeted.sagadyssey.social.religion.life.ChurchLifeClientFixture.status(p,temple);
            h.assertTrue(status.size()==1&&status.getFirst().contains("身份"),"missing fixture observer has clear explanation");
        }
        h.assertTrue(command(near,"saga church here")==1,"existing living reader can inspect local church");command(near,"saga church known "+temple.id());
        h.assertTrue(com.jgeted.sagadyssey.social.religion.life.ChurchLifeClientFixture.status(near,temple).stream().anyMatch(x->x.contains("教学时长=0/40")),"existing living fixture status remains usable without invented lesson credit");
        h.assertTrue(data.allCharacters().size()==before&&lives(missing)==0&&lives(dead)==1,"all church query counts unchanged");
        var request=ReligionService.request(near.server,temple,recipient,new ItemFingerprint("minecraft:bread",0),3,"identity test real finite relief");
        h.succeedWhen(()->{
            var issued=com.jgeted.sagadyssey.social.religion.ReligionData.get(near.server).aid(request.id());
            h.assertTrue(issued.stage()==com.jgeted.sagadyssey.social.religion.AlmsCase.Stage.ISSUED,"normal deacon actually delivered three bread");long now=h.getLevel().getGameTime();
            h.assertTrue(ReligionService.knownRecords(near.server,CharacterRegistry.currentId(near),temple.id()).size()==1,"normal background issuance informed existing nearby witness");
            var forged=issued.update(com.jgeted.sagadyssey.social.religion.AlmsCase.Stage.ISSUED,issued.reviewer(),now,now,null,"forged witness payload",false);
            h.assertTrue(!ReligionService.witness(near,deacon,forged,now),"forged evidence still rejected");
            boolean nearAccepted=ReligionService.witness(near,deacon,issued,now),remoteAccepted=ReligionService.witness(remote,deacon,issued,now);
            if(!nearAccepted||remoteAccepted)Sagadyssey.LOGGER.info("IDENTITY_WITNESS_DIAGNOSTIC now={} updated={} near={} remote={} nearAlive={} current={} distance={} losDeacon={} losRecipient={} worker={} deacon={}",now,issued.updatedAt(),nearAccepted,remoteAccepted,near.isAlive(),CharacterRegistry.currentId(near),near.distanceToSqr(deacon),near.hasLineOfSight(deacon),near.hasLineOfSight(recipient),issued.workerCharacter(),deacon.getCharacterId());
            h.assertTrue(nearAccepted&&!remoteAccepted,"real visible witness accepted; distant witness rejected");
            h.assertTrue(!ReligionService.witness(dead,deacon,issued,now)&&!ReligionService.witness(missing,deacon,issued,now),"dead and unbound observers excluded from real event");
            h.assertTrue(lives(dead)==1&&lives(missing)==0&&CharacterRegistry.currentId(dead)==null&&CharacterRegistry.currentId(missing)==null,"background and direct reads never create observers for either account");
            h.assertTrue(!data.byId(old).alive()&&ReligionService.knownRecords(near.server,old,temple.id()).isEmpty(),"old dead identity remains dead without new observation");
            h.assertTrue(recipient.getPersonalInventory().countItem(Items.BREAD)==3&&((Container)member(fixture,"alms")).countItem(Items.BREAD)==3,"actual six bread split into three retained and three delivered");
            Sagadyssey.LOGGER.info("IDENTITY_CHURCH_WITNESS tick={} case={} deliveredBread=3 retainedBread=3 deadLives=1 missingLives=0 nearbyObserved=true",now,issued.id());
            for(var p:List.of(near,remote,dead))NpcCombatIntegrationGameTests.offline(p);h.succeed();
        });
    }

}
