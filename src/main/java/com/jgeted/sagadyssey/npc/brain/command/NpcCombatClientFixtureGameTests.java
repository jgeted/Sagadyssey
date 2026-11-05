package com.jgeted.sagadyssey.npc.brain.command;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class NpcCombatClientFixtureGameTests {
    @GameTest(template="fieldwork_empty",batch="phase9_client_fixture",timeoutTicks=1100)
    public static void consolidatedFixtureRunsOnceWithoutChangingOldSquad(GameTestHelper h){floor(h);ServerPlayer p=NpcCombatIntegrationGameTests.online(h);var groups=CommandGroupAttachments.get(p);String active=groups.activeSquadId();var strategy=groups.activeStrategy();
        var data=NpcCombatClientFixture.Data.get(p.server);var person=CharacterRegistry.ensureCurrent(p).id();boolean[] prepared={false};
        long[] finishedAt={-1};
        h.succeedWhen(()->{
            if(!prepared[0]){var anchor=p.blockPosition().offset(2,0,2);for(int x=0;x<=18;x++)for(int z=0;z<=12;z++)h.assertTrue(h.getLevel().isPositionEntityTicking(anchor.offset(x,0,z)),"expanded client fixture chunks must really tick before actors are added");
                NpcCombatClientFixture.prepare(p);var before=data.find(person);NpcCombatClientFixture.prepare(p);h.assertTrue(before.equals(data.find(person)),"repeat explicit fixture call did not create replacement actors or materials");h.assertTrue(active.equals(groups.activeSquadId())&&strategy==groups.activeStrategy(),"original active squad and strategy preserved");prepared[0]=true;}
            var tag=data.find(person);var order=ProductionOrderData.get(p.server).byId(tag.getUUID("Order")).orElseThrow();h.assertTrue(order.status()==ProductionOrderStatus.COMPLETED,"same client fixture order completed after finite withdrawal: "+order.detail());
            h.assertTrue(tag.hasUUID("Withdrawal")&&NpcDutyData.get(p.server).find(tag.getUUID("Withdrawal")).status()==NpcDutyData.Status.COMPLETED,"authorized withdrawal really arrived and ended");
            var medical=NpcDutyData.get(p.server).find(tag.getUUID("Medical"));var medicalDoctor=(NpcBase)h.getLevel().getEntity(tag.getUUID("Doctor"));h.assertTrue(medical.status()==NpcDutyData.Status.COMPLETED,"real consented client fixture medical work completed: "+medical+" doctor="+medicalDoctor.position()+" plan="+com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(p.server).active(medicalDoctor.getUUID())+" wound="+com.jgeted.sagadyssey.social.combat.InjuryData.get(p.server).wound(medical.source()));h.assertTrue(NeedRequestData.get(p.server).request(tag.getUUID("Need")).delivered()==3,"real client fixture goods delivered");
            var guard=(NpcBase)h.getLevel().getEntity(tag.getUUID("Guard"));var escort=NpcDutyData.get(p.server).last(guard.getUUID(),NpcDutyData.Kind.ESCORT);h.assertTrue(escort!=null&&escort.status()==NpcDutyData.Status.COMPLETED,"fixture includes actual finite escort receipt: "+escort+" squad="+groups.squadOf(guard.getCharacterId())+" strategy="+groups.strategyOf(guard.getCharacterId()));
            var doctor=(NpcBase)h.getLevel().getEntity(tag.getUUID("Doctor"));h.assertTrue(doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==0,"one real dressing consumed");
            if(finishedAt[0]<0)finishedAt[0]=h.getTick();
            h.assertTrue(h.getTick()-finishedAt[0]>=260,"observe post-completion behavior beyond the former 200-tick no-plan warning");
            var smith=(NpcBase)h.getLevel().getEntity(tag.getUUID("Smith"));var brain=com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(p.server);
            h.assertTrue(brain.active(smith.getUUID()).isPresent()&&brain.history(smith.getUUID()).stream().anyMatch(plan->plan.providerId().equals(com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithRestPlanFactory.PROVIDER)),"same fixture smith completes bounded rest instead of permanently standing without a plan");
            NpcCombatIntegrationGameTests.offline(p);});}
    @GameTest(template="fieldwork_empty",batch="phase9_client_fixture",timeoutTicks=60)
    public static void occupiedFixtureSiteDoesNotOverwriteUserBlockOrStartIntent(GameTestHelper h){floor(h);var p=NpcCombatIntegrationGameTests.online(h);var pos=p.blockPosition().offset(3,0,3);h.getLevel().setBlockAndUpdate(pos,Blocks.DIAMOND_BLOCK.defaultBlockState());boolean refused=false;try{NpcCombatClientFixture.prepare(p);}catch(IllegalStateException expected){refused=true;}
        h.assertTrue(refused&&h.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK)&&NpcCombatClientFixture.Data.get(p.server).find(CharacterRegistry.ensureCurrent(p).id())==null,"preflight rejected occupied ground without any fixture intent or overwrite");h.assertTrue(h.getLevel().getServer().getCommands().getDispatcher().parse("saga combat demo-status",p.server.createCommandSourceStack().withPermission(0)).getReader().canRead(),"tester fixture status unavailable to ordinary command source");NpcCombatIntegrationGameTests.offline(p);h.succeed();}
    private static void floor(GameTestHelper h){var start=h.absolutePos(BlockPos.ZERO);var end=h.absolutePos(new BlockPos(30,0,26));for(int cx=(start.getX()>>4)-1;cx<=(end.getX()>>4)+1;cx++)for(int cz=(start.getZ()>>4)-1;cz<=(end.getZ()>>4)+1;cz++){h.getLevel().setChunkForced(cx,cz,true);h.getLevel().getChunk(cx,cz);}for(int x=0;x<=30;x++)for(int z=0;z<=26;z++){h.setBlock(x,0,z,Blocks.STONE);h.setBlock(x,1,z,Blocks.AIR);h.setBlock(x,2,z,Blocks.AIR);}}
}
