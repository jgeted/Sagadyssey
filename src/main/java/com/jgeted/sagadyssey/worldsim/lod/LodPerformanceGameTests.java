package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.production.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class LodPerformanceGameTests {
    @GameTest(template="fieldwork_empty",batch="phase10_profile",timeoutTicks=78500)
    public static void hundredLoadedRealActorsHaveBoundedPlanningAndConserveActualOrders(GameTestHelper h) {
        if(!Boolean.getBoolean("sagadyssey.phase10.profile")){h.succeed();return;}
        var level=h.getLevel();var origin=h.absolutePos(new BlockPos(38000,1,38000));
        for(int x=0;x<=40;x+=16)for(int z=0;z<=40;z+=16)LodGameTests.force(level,origin.offset(x,0,z),true);
        LodGameTests.floor(level,origin.offset(20,0,20),23);
        var actors=new ArrayList<NpcBase>();var orders=new ArrayList<ProductionOrder>();boolean[] spawned={false};
        h.runAtTickTime(100,()->{
            h.assertTrue(level.isPositionEntityTicking(origin)&&level.isPositionEntityTicking(origin.offset(36,0,36)),"hundred-actor chunks genuinely ticking");
            var roles=new NpcProfession[]{NpcProfession.BLACKSMITH,NpcProfession.FARMER,NpcProfession.WORKER,NpcProfession.WARRIOR};
            for(int i=0;i<100;i++){var pos=origin.offset(i%10*4,0,i/10*4);var n=LodGameTests.actor(level,pos,roles[i%4]);n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,4));actors.add(n);if(i%4==0)orders.add(LodGameTests.order(n,pos,400));}
            spawned[0]=true;
        });
        long[] started={0};var ticks=new HashMap<UUID,Integer>();jdk.jfr.Recording[] recording={null};
        h.runAtTickTime(5900,()->{
            var flight=new jdk.jfr.Recording();flight.setMaxSize(16*1024*1024);flight.enable("jdk.GarbageCollection");flight.enable("jdk.GCPhasePause");
            for(var name:List.of("jdk.ThreadPark","jdk.JavaMonitorEnter","jdk.FileWrite"))flight.enable(name).withThreshold(java.time.Duration.ofMillis(20)).withStackTrace();
            flight.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(5));
            flight.start();recording[0]=flight;
        });
        h.runAtTickTime(6000,()->{h.assertTrue(spawned[0]&&actors.stream().allMatch(n->!n.isNoAi()&&n.tickCount>5000),"all 100 are real actively ticking entities, not dry-run counters");
            LodMetrics.reset(level.getServer());started[0]=level.getGameTime();for(var n:actors)ticks.put(n.getUUID(),n.tickCount);
        });
        int duration=Boolean.getBoolean("sagadyssey.phase10.soak")?72000:3000;
        h.runAtTickTime(6000+duration,()->{
            if(recording[0]!=null)try(var flight=recording[0]){flight.stop();flight.dump(level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("phase10-profile.jfr"));}catch(java.io.IOException ex){throw new IllegalStateException("save actual tick profiling evidence",ex);}
            for(var n:actors){h.assertTrue(n.isAlive()&&n.tickCount-ticks.get(n.getUUID())>=duration-2,"all hundred actors remained loaded and active throughout the sample");
                h.assertTrue(n.getPersonalInventory().count(s->s.is(Items.BREAD))<=4&&NpcLifeData.get(n.getServer()).needs(n.getCharacterId()).hunger()<800,"real finite food only, survival still progresses");
                h.assertTrue(NpcBrainRuntime.data(n.getServer()).history(n.getUUID()).size()<=32,"bounded history");
            }
            for(var d:orders){h.assertTrue(ProductionOrderData.get(level.getServer()).byId(d.id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"25 actual orders completed through entity labor");var box=(net.minecraft.world.Container)level.getBlockEntity(d.targetContainerPosition());int outputs=0;for(int i=0;i<box.getContainerSize();i++){var s=box.getItem(i);if(s.is(Items.IRON_PICKAXE))outputs+=s.getCount();}h.assertTrue(outputs==1,"one real output per order, never double settlement");}
            var budget=NpcLodBudgets.planning(level.getServer()).snapshot();var metric=LodMetrics.snapshot(level.getServer(),LodMetrics.Kind.SERVER_TICK);
            h.assertTrue(budget.maxAdmissions()<=8&&budget.maxWaitTicks()<=200,"hard new-plan count budget and bounded fair queue latency");
            h.assertTrue(metric.samples()>=1000&&metric.p95Nanos()<50_000_000,"actual main-thread server-tick P95 below 50ms after warmup, not only provider timing: "+metric);
            h.assertTrue(metric.over50ms()<=Math.max(3,duration/24000),"bounded isolated outliers, not repeated 50ms tick spikes: "+metric);
            h.assertTrue(NpcPerceptionQueue.snapshot(level.getServer()).maxCount()<=12&&NpcPerceptionQueue.snapshot(level.getServer()).maxWaitTicks()<=200,"autonomous perception cannot starve behind an infrequently polled Goal");
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PHASE10_PROFILE actors=100 realOrders=25 actualTicks={} serverTick={} planning={} perception={} navigation={} offline={} planningBudget={} perceptionQueue={}",level.getGameTime()-started[0],metric,LodMetrics.snapshot(level.getServer(),LodMetrics.Kind.SELECTION),LodMetrics.snapshot(level.getServer(),LodMetrics.Kind.PERCEPTION_SCAN),LodMetrics.snapshot(level.getServer(),LodMetrics.Kind.NAVIGATION),LodMetrics.snapshot(level.getServer(),LodMetrics.Kind.OFFLINE),budget,NpcPerceptionQueue.snapshot(level.getServer()));
            actors.forEach(n->n.setNoAi(true));h.succeed();
        });
    }
}
