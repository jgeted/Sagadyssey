package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.command.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import static com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class NpcCombatIntegrationGameTests {
    @GameTest(template="fieldwork_empty",batch="phase9_integration",timeoutTicks=700)
    public static void escortMovesToOriginalRealCarryingShipment(GameTestHelper h){var f=convoy(h);double before=f.guard().getX();
        h.succeedWhen(()->{var d=NpcDutyData.get(MinecraftApi.server(f.guard())).last(f.guard().getUUID(),NpcDutyData.Kind.ESCORT);
            h.assertTrue(d!=null&&d.status()==NpcDutyData.Status.COMPLETED,"finite real escort completed: "+d+" guard="+diagnostic(f.guard())+" carrier="+diagnostic(f.carrier()));
            h.assertTrue(f.guard().getX()<before-1&&f.guard().distanceToSqr(f.carrier())<=16,"escort physically followed the original carrier");
            var proof=NpcDutyEvidenceData.get(MinecraftApi.server(f.guard())).find(d.id());var route=SupplyFlowData.get(MinecraftApi.server(f.guard())).route(proof.record());
            h.assertTrue(route.stage()==SupplyFlowData.Stage.CARRYING&&route.carrierEntity().equals(f.carrier().getUUID())
                    &&SupplyFlowService.count(f.carrier().getTransportInventory(),ItemFingerprint.of(new ItemStack(Items.COAL)))==3,"protected actual cargo, not a name tag or replaced shipment");offline(f.owner());});}
    @GameTest(template="fieldwork_empty",batch="phase9_integration",timeoutTicks=450)
    public static void revokingEscortProofCannotBorrowNewEmployment(GameTestHelper h){var f=convoy(h);UUID[] original={null};
        h.succeedWhen(()->{var data=NpcDutyData.get(MinecraftApi.server(f.guard()));if(original[0]==null){var d=data.active(f.guard().getUUID());h.assertTrue(d!=null&&d.kind()==NpcDutyData.Kind.ESCORT,"actual escort acquired original relationship: "+diagnostic(f.guard())+" carrier="+diagnostic(f.carrier()));original[0]=d.id();long now=h.getLevel().getGameTime();var s=ServiceRelationshipData.get(MinecraftApi.server(f.guard()));s.endActive(d.source(),f.guard().getCharacterId(),ServiceStatus.CANCELLED,"original proof revoked",now);s.beginVoluntaryEmployment(d.source(),f.guard().getCharacterId(),now+1);}
            h.assertTrue(data.find(original[0]).status()==NpcDutyData.Status.CANCELLED,"fresh employment cannot revive original escort");h.assertTrue(f.carrier().getTransportInventory().countItem(Items.COAL)==3,"revocation does not steal, deliver or discard carried stock");offline(f.owner());});}
    @GameTest(template="fieldwork_empty",batch="phase9_integration",timeoutTicks=180)
    public static void actualDamageArrowsDeathAndCargoWriteBackOnce(GameTestHelper h){var n=npc(h,NpcProfession.ARCHER,4);var other=npc(h,NpcProfession.WARRIOR,8);n.setNoAi(true);other.setNoAi(true);var owner=online(h);n.setOwner(owner);long now=h.getLevel().getGameTime();var life=NpcLifeData.get(MinecraftApi.server(n));var principal=CharacterRegistry.ensureCurrent(owner).id();life.onRecruited(n,principal,now);var settlement=UUID.randomUUID();h.assertTrue(life.beginContract(n.getCharacterId(),principal,settlement,n.blockPosition(),3,2,now),"actual contract for impact inbox");
        var home=HouseholdData.get(MinecraftApi.server(n)).create("actual battle household","minecraft:overworld",settlement,n.getCharacterId(),n.blockPosition(),now);
        var encounter=CombatEncounterService.start(CombatEncounterData.get(MinecraftApi.server(n)),UUID.randomUUID(),settlement,"minecraft:overworld",n.blockPosition(),32,List.of(new CombatSide(UUID.randomUUID(),"original carrier",null,Set.of(n.getCharacterId()),EngagementRule.SELF_DEFENSE_ONLY),new CombatSide(UUID.randomUUID(),"other side",null,Set.of(other.getCharacterId()),EngagementRule.SELF_DEFENSE_ONLY)),now);
        h.assertTrue(n.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(),4),"actual health damage generated injury evidence");n.getPersonalInventory().setItem(0,new ItemStack(Items.ARROW));h.assertTrue(NpcAmmunitionService.launch(n,arrow(h,n),new ItemStack(Items.ARROW)),"actual arrow entity and actual ammo expenditure");
        n.getTransportInventory().setItem(0,new ItemStack(Items.COAL,3));MinecraftApi.kill(n);
        h.runAtTickTime(30,()->{NpcCombatRuntime.finish(h.getLevel(),encounter.id(),h.getLevel().getGameTime());NpcCombatRuntime.finish(h.getLevel(),encounter.id(),h.getLevel().getGameTime()+20);
            var impacts=HouseholdData.get(MinecraftApi.server(n)).combatImpacts().stream().filter(i->i.encounter().equals(encounter.id())&&i.character().equals(n.getCharacterId())).toList();h.assertTrue(impacts.size()==1&&impacts.getFirst().wounds()==2&&impacts.getFirst().shots()==1&&impacts.getFirst().droppedStacks()==1,"both actual hits (including lethal hit), ammo and cargo settled exactly once: "+impacts+" events="+CombatEncounterData.get(MinecraftApi.server(n)).encounter(encounter.id()).events());
            h.assertTrue(life.combatImpacts().stream().filter(i->i.id().equals(impacts.getFirst().id())).count()==1&&com.jgeted.sagadyssey.social.economy.SettlementEconomyData.get(MinecraftApi.server(n)).combatImpacts().stream().filter(i->i.id().equals(impacts.getFirst().id())).count()==1,"original contract and market received same immutable impact");
            int coal=h.getLevel().getEntitiesOfClass(ItemEntity.class,h.getBounds()).stream().filter(i->i.getItem().is(Items.COAL)).mapToInt(i->i.getItem().getCount()).sum();h.assertTrue(coal==3,"cargo remains actual ground stock, not destroyed or delivered stock");h.assertTrue(life.contract(n.getCharacterId()).nextPaymentAt()==now+24000,"no invented wages from combat settlement");offline(owner);h.succeed();});}

    private record Convoy(NpcBase guard,NpcBase carrier,ServerPlayer owner){}
    private static String diagnostic(NpcBase n){return "pos="+n.position()+" owner="+n.getActiveOwner()+" cmd="+n.getCommand()+" stage="+n.getLogisticsStage()+" target="+n.getTarget()+" strategy="+(n.getActiveOwner() instanceof ServerPlayer p?CommandGroupAttachments.get(p).strategyOf(n.getCharacterId()):null)+" relations="+ServiceRelationshipData.get(MinecraftApi.server(n)).activeForAgent(n.getCharacterId(),n.level().getGameTime())+" visible="+n.level().getEntitiesOfClass(NpcBase.class,n.getBoundingBox().inflate(16),n::hasLineOfSight).stream().map(v->v.getCharacterId()).toList()+" schedule="+com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.current(n)+" plan="+NpcBrainRuntime.data(MinecraftApi.server(n)).active(n.getUUID())+" routes="+SupplyFlowData.get(MinecraftApi.server(n)).routes().stream().filter(r->n.getUUID().equals(r.carrierEntity())).toList();}
    private static Convoy convoy(GameTestHelper h){var g=npc(h,NpcProfession.WARRIOR,10);var carrier=npc(h,NpcProfession.COURIER,2);var p=online(h);g.setOwner(p);carrier.setOwner(p);var principal=CharacterRegistry.ensureCurrent(p).id();long now=h.getLevel().getGameTime();for(var n:List.of(g,carrier)){NpcLifeData.get(MinecraftApi.server(n)).onRecruited(n,principal,now);n.setCommand(NpcCommand.WORK);}
        var groups=CommandGroupAttachments.get(p);groups.registerMember(g.getCharacterId(),"escort");groups.registerMember(carrier.getCharacterId(),"actual courier");for(int i=0;i<20&&groups.activeStrategy()!=SquadStrategy.ESCORT_CONVOY;i++)groups.cycleActiveStrategy();
        var source=point(h,3,SubjectRef.character(principal));var dest=point(h,4,SubjectRef.character(principal));var box=(Container)h.getLevel().getBlockEntity(source.position());box.setItem(0,new ItemStack(Items.COAL,3));var output=(Container)h.getLevel().getBlockEntity(dest.position());for(int s=0;s<output.getContainerSize();s++)output.setItem(s,new ItemStack(Items.COBBLESTONE,64));
        var flow=SupplyFlowData.get(MinecraftApi.server(g));flow.reveal(SubjectRef.character(principal),source.containerId());SupplyFlowService.request(MinecraftApi.server(g),SubjectRef.character(principal),"real escort test",dest,ItemFingerprint.of(new ItemStack(Items.COAL)),3,now,now+2000);SupplyFlowService.match(MinecraftApi.server(g),now);return new Convoy(g,carrier,p);}
    private static FieldworkStorage point(GameTestHelper h,int x,SubjectRef principal){h.setBlock(x,1,5,Blocks.BARREL);var pos=h.absolutePos(new BlockPos(x,1,5));var id=ContainerIds.block("physical_storage","minecraft:overworld",pos);var point=new FieldworkStorage("minecraft:overworld",pos,id,principal,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"real convoy fixture");RightsStateData.get(h.getLevel().getServer()).ensureContext(id,"fixture:"+pos.asLong(),principal,principal,AccessPolicy.PRIVATE);SupplyFlowData.get(h.getLevel().getServer()).register(point);return point;}
    static ServerPlayer online(GameTestHelper h){var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"p9convoy"),ClientInformation.createDefault());var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(p.level().getServer(),connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(p.getGameProfile(),false)){
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){} 
        };players(p.level().getServer()).add(p);h.getLevel().addNewPlayer(p);var pos=h.absolutePos(new BlockPos(6,1,9));p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);com.jgeted.sagadyssey.social.character.CharacterLifecycleEvents.onLogin(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));return p;}
    static void offline(ServerPlayer p){players(p.level().getServer()).remove(p);p.discard();}
    @SuppressWarnings("unchecked") private static List<ServerPlayer> players(net.minecraft.server.MinecraftServer s){try{var f=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");f.setAccessible(true);return(List<ServerPlayer>)f.get(s.getPlayerList());}catch(ReflectiveOperationException x){throw new IllegalStateException(x);}}
}
