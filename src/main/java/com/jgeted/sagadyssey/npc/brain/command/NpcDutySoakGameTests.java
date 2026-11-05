package com.jgeted.sagadyssey.npc.brain.command;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class NpcDutySoakGameTests {
    @GameTest(template="fieldwork_empty",batch="phase9_soak",timeoutTicks=72020)
    public static void threeActualGameDaysKeepFiniteTasksAndRealFoodConserved(GameTestHelper h){
        if(!Boolean.getBoolean("sagadyssey.phase9.soak")){h.succeed();return;}
        var actors=new ArrayList<NpcBase>();for(int x:new int[]{2,5,8}){var n=NpcDutyGameTests.npc(h,NpcProfession.WARRIOR,x);n.setCommand(NpcCommand.WORK);n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,4));actors.add(n);}
        var smith=NpcDutyGameTests.npc(h,NpcProfession.BLACKSMITH,10);smith.setCommand(NpcCommand.WORK);smith.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,4));actors.add(smith);
        long start=h.getLevel().getGameTime();int initialDuties=NpcDutyData.get(h.getLevel().getServer()).all().size();
        h.runAtTickTime(72000,()->{h.assertTrue(h.getLevel().getGameTime()-start>=72000,"three actual server days, not only changing displayed time");
            for(var n:actors){h.assertTrue(n.isAlive()&&n.getPersonalInventory().count(s->s.is(Items.BREAD))<=4,"no spawned food or survival loss");
                h.assertTrue(NpcLifeData.get(n.getServer()).needs(n.getCharacterId()).hunger()<800,"actual bag eating prevents permanently starving work");
                var active=NpcBrainRuntime.data(n.getServer()).active(n.getUUID());h.assertTrue(active.isEmpty()||active.get().expiresAt()>h.getLevel().getGameTime(),"no expired active plan loop");
                h.assertTrue(NpcBrainRuntime.data(n.getServer()).history(n.getUUID()).size()<=32,"bounded terminal plan history");}
            h.assertTrue(NpcDutyData.get(h.getLevel().getServer()).all().size()==initialDuties,"idle guard did not create phantom patient or convoy jobs");
            var metrics=NpcPlanningMetrics.snapshot(h.getLevel().getServer());h.assertTrue(metrics.retained()<=4096&&metrics.calls()>10000,"bounded actual planning measurements");
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PHASE9_SOAK actualTicks=72000 actors={} planningCalls={} p95Nanos={} maxNanos={} retainedSamples={}",actors.size(),metrics.calls(),metrics.p95Nanos(),metrics.maxNanos(),metrics.retained());
            h.succeed();});
    }
}
