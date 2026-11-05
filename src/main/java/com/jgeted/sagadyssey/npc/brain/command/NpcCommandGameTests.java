package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NpcCommandGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void checkpoint(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;previous=null;
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart"))return;
        try{previous=NbtIo.readCompressed(e.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("phase9-command-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            e.getServer().getWorldData().overworldData().setGameTime(Math.max(e.getServer().overworld().getGameTime(),previous.getLong("Time")+1));}
        catch(Exception x){throw new IllegalStateException("mandatory stage9 previous-process command checkpoint",x);}
    }
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=60)
    public static void unauthorizedPlayerCannotChangeCommandOrForgeIntent(GameTestHelper h){var n=npc(h);var p=player(h);int count=NpcCommandData.get(p.server).all().size();var result=NpcCommandService.issue(p,n,NpcCommand.STAY);
        h.assertTrue(!result.accepted()&&n.getCommand()==NpcCommand.IDLE&&NpcCommandData.get(p.server).all().size()==count,"no authority means no mutation even for a tester account");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=320)
    public static void ordinaryCommandWaitsForCurrentObligationSafeBoundary(GameTestHelper h){var n=npc(h);var p=employer(h,n);NpcBrainRuntime.createDiagnosticWait(n,80);var original=NpcBrainRuntime.data(p.server).active(n.getUUID()).orElseThrow().id();var i=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,300).intent();
        h.runAtTickTime(20,()->h.assertTrue(n.getCommand()==NpcCommand.IDLE&&NpcBrainRuntime.data(p.server).active(n.getUUID()).orElseThrow().id().equals(original),"regular request did not preempt current plan"));
        h.succeedWhen(()->h.assertTrue(NpcCommandData.get(p.server).find(i.id()).leg()>=1&&n.getCommand()==NpcCommand.STAY,"request activated only after old plan ended and performed finite hold"));}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=450)
    public static void patrolUsesRealMovementAndPersistsReachedLeg(GameTestHelper h){var n=npc(h);var p=employer(h,n);var i=NpcCommandService.issue(p,n,NpcCommand.PATROL,n.blockPosition(),4,400).intent();double before=n.getX();
        h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.PatrolGoal(n).canUse(),"legacy patrol cannot run beside planned executor");
        h.succeedWhen(()->{h.assertTrue(NpcCommandData.get(p.server).find(i.id()).leg()>=1&&n.getX()>before+2,"actual first patrol node reached; pos="+n.position()+" intent="+NpcCommandData.get(p.server).find(i.id())+" plan="+NpcBrainRuntime.data(p.server).active(n.getUUID()));h.assertTrue(NpcBrainRuntime.data(p.server).history(n.getUUID()).stream().anyMatch(v->v.providerId().equals(NpcCommandTask.PROVIDER)&&v.status()==com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus.SUCCEEDED),"successful reached leg has plan receipt");});}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=450)
    public static void followMovesToSpecificIssuerAndDoesNotExposeAnotherPlayersCommand(GameTestHelper h){
        var n=npc(h);var p=employer(h,n);var other=player(h);var profile=p.getGameProfile();
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);
        p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(p.server,connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false)){
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener){}
        };
        players(p.server).add(p);h.getLevel().addNewPlayer(p);var pos=h.absolutePos(new BlockPos(10,1,5));p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);double before=n.getX();
        var i=NpcCommandService.issue(p,n,NpcCommand.FOLLOW,n.blockPosition(),4,400).intent();
        h.assertTrue(!NpcCommandService.issue(other,n,NpcCommand.STAY).accepted(),"second player cannot replace first player's pending intent");
        h.assertTrue(NpcCommandData.get(p.server).known(CharacterRegistry.ensureCurrent(other).id(),n.getCharacterId()).isEmpty(),"ordinary second-player projection contains no first-player request or backend truth");
        h.succeedWhen(()->{h.assertTrue(NpcCommandData.get(p.server).find(i.id()).leg()>=1&&n.getX()>before+2&&n.distanceToSqr(p)<=9,"actual follow reached specific issuer without teleport");players(p.server).remove(p);p.discard();});
    }
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=350)
    public static void revokedOriginalProofIsNotReplacedByUnrelatedRenewal(GameTestHelper h){var n=npc(h);var p=employer(h,n);var i=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,300).intent();
        h.runAtTickTime(40,()->{var s=ServiceRelationshipData.get(p.server);s.endActive(i.issuer(),n.getCharacterId(),ServiceStatus.CANCELLED,"withdraw",h.getLevel().getGameTime());s.beginVoluntaryEmployment(i.issuer(),n.getCharacterId(),h.getLevel().getGameTime()+1);});
        h.runAtTickTime(80,()->{h.assertTrue(NpcCommandData.get(p.server).find(i.id()).status()==NpcCommandIntent.Status.CANCELLED&&n.getCommand()==NpcCommand.IDLE,"revoked original proof cannot inherit fresh authority");h.assertTrue(NpcBrainRuntime.data(p.server).active(n.getUUID()).isEmpty(),"no stale command action remains");h.succeed();});}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=300)
    public static void expiryDuringCombatDoesNotLeavePermanentFollowOrHold(GameTestHelper h){var n=npc(h);var p=employer(h,n);var i=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,80).intent();
        h.runAtTickTime(30,()->{var cow=h.spawn(EntityType.COW,new BlockPos(5,1,5));cow.setNoAi(true);cow.setInvulnerable(true);n.setTarget(cow);});
        h.runAtTickTime(140,()->{h.assertTrue(NpcCommandData.get(p.server).find(i.id()).status()==NpcCommandIntent.Status.EXPIRED&&n.getCommand()==NpcCommand.IDLE,"independent entity maintenance expires command while combat owns goals");h.succeed();});}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=420)
    public static void threatReleasesBridgeAndResumesSameAuthorizedIntent(GameTestHelper h){var n=npc(h);var p=employer(h,n);var i=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,400).intent();
        var cow=h.spawn(EntityType.COW,new BlockPos(6,1,5));cow.setNoAi(true);cow.setInvulnerable(true);
        h.runAtTickTime(30,()->n.setTarget(cow));h.runAtTickTime(90,()->{cow.discard();n.setTarget(null);});
        h.succeedWhen(()->{h.assertTrue(h.getTick()>100&&NpcCommandData.get(p.server).find(i.id()).leg()>=1,"same surviving request resumed after threat ended");h.assertTrue(NpcBrainRuntime.data(p.server).history(n.getUUID()).stream().anyMatch(v->v.providerId().equals(NpcCommandTask.PROVIDER)&&v.status()==com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus.CANCELLED),"interrupted leg has explicit terminal audit");});}
    @GameTest(template="fieldwork_empty",batch="phase9_commands",timeoutTicks=60)
    public static void plannedFollowAndHoldNeverFallBackToLegacyMovement(GameTestHelper h){var n=npc(h);n.setCommand(NpcCommand.FOLLOW);h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.FollowOwnerGoal(n,1,3,48).canUse(),"no implicit legacy follow teleport");n.setCommand(NpcCommand.STAY);h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.StayGoal(n).canUse(),"only command execution bridge can hold a planned NPC");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_commands_checkpoint",timeoutTicks=100)
    public static void saveOriginalCommandAndBodyForNewProcess(GameTestHelper h){
        // An isolated far fixture avoids later batches clearing the original checkpoint body.
        int shift=17000;var pos=h.absolutePos(new BlockPos(shift,1,shift));force(h.getLevel(),pos);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)h.getLevel().setBlock(pos.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);h.getLevel().addFreshEntity(n);prepare(n);var p=employer(h,n);
        var i=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,24000).intent();NpcCommandService.maintain(n);
        n.setNoAi(true);var t=new CompoundTag();t.putUUID("Body",n.getUUID());t.putUUID("Intent",i.id());t.putLong("Position",pos.asLong());t.putLong("Time",h.getLevel().getGameTime());
        try{NbtIo.writeCompressed(t,n.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("phase9-command-checkpoint.nbt"));n.getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();h.succeed();}
        catch(Exception x){throw new IllegalStateException("stage9 original command checkpoint save",x);}
    }
    @GameTest(template="fieldwork_empty",batch="phase9_commands_restart",timeoutTicks=500)
    public static void newProcessLoadsOriginalCommandAndBodyWithoutNewAuthority(GameTestHelper h){if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}h.assertTrue(previous!=null,"original stage9 disk checkpoint required");force(h.getLevel(),BlockPos.of(previous.getLong("Position")));boolean[] resumed={false};
        h.succeedWhen(()->{var n=(NpcBase)h.getLevel().getEntity(previous.getUUID("Body"));h.assertTrue(n!=null,"original entity loaded, not recreated");var d=NpcCommandData.get(n.getServer());var i=d.find(previous.getUUID("Intent"));h.assertTrue(i!=null&&i.character().equals(n.getCharacterId()),"same issuer and original relationship proof retained");
            if(!resumed[0]){h.assertTrue(i.status()==NpcCommandIntent.Status.ACTIVE&&i.leg()==0,"original pending arrival receipt");n.setNoAi(false);resumed[0]=true;}
            h.assertTrue(d.find(i.id()).leg()>=1,"same request performs finite hold after process restart");});}
    private static void force(ServerLevel l,BlockPos p){for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)l.setChunkForced((p.getX()>>4)+x,(p.getZ()>>4)+z,true);}
    private static NpcBase npc(GameTestHelper h){for(int x=0;x<12;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(4,1,5));prepare(n);return n;}
    private static void prepare(NpcBase n){n.setProfession(NpcProfession.WARRIOR);n.getPersonalInventory().clearContent();n.setPersistenceRequired();n.setCommand(NpcCommand.IDLE);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);}
    private static ServerPlayer player(GameTestHelper h){return new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"p9issuer"),ClientInformation.createDefault());}
    private static ServerPlayer employer(GameTestHelper h,NpcBase n){var p=player(h);var id=CharacterRegistry.ensureCurrent(p).id();ServiceRelationshipData.get(p.server).beginVoluntaryEmployment(id,n.getCharacterId(),h.getLevel().getGameTime());return p;}
    /** Physical headless issuer only; this does not stand in for client/UI acceptance. */
    @SuppressWarnings("unchecked") private static List<ServerPlayer> players(net.minecraft.server.MinecraftServer s){try{var f=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");f.setAccessible(true);return (List<ServerPlayer>)f.get(s.getPlayerList());}catch(ReflectiveOperationException x){throw new IllegalStateException("physical issuer fixture unavailable",x);}}
}
