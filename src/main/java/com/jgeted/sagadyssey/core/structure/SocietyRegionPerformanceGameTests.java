package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import net.minecraft.gametest.framework.*;
import java.util.*;
public final class SocietyRegionPerformanceGameTests {
 public static void run(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var villages=List.of(NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2),NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2),NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.LIVING),NaturalVillageR1GameTests.fixture(h,896,NaturalVillageLayout.FARM_V2));int[] stage={0},last={-1};long[] baseline={0},peak={0},after={0},until={0};h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var d=NaturalVillageEconomyData.get(s);
  if(stage[0]==0&&h.getTick()>=300){for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=1;}
  if(stage[0]==1){for(var b:villages){var r=d.row(b.buildingKey());if(r==null||!r.getString("Phase").equals("REMOTE"))return;for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(l,b,i)!=null)return;}
   baseline[0]=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();until[0]=l.getGameTime()+120000;s.getWorldData().overworldData().setGameTime(until[0]);l.setDayTime(l.getDayTime()+120000);peak[0]=d.backlog(l.getGameTime());stage[0]=2;
  }
  if(stage[0]==2){peak[0]=Math.max(peak[0],d.backlog(l.getGameTime()));if(d.backlog(l.getGameTime())>0)return;after[0]=l.getGameTime();stage[0]=3;}
  if(stage[0]==3&&l.getGameTime()-after[0]>=6000){h.assertTrue(d.rows().size()==4&&d.backlog(l.getGameTime())==0&&villages.stream().flatMap(b->NaturalPolityData.population(s,b).stream()).distinct().count()==12,"four actual enrolled sites retain twelve original people and bounded backlog clears without lost time");var metric=LodMetrics.snapshot(s,LodMetrics.Kind.REGION_ECONOMY);h.assertTrue(metric.p95Nanos()<=2000000,"four-site p95 must meet the inherited frozen two-millisecond model budget: "+metric);var tag=d.save(new net.minecraft.nbt.CompoundTag(),s.registryAccess());var bytes=new java.io.ByteArrayOutputStream();try{net.minecraft.nbt.NbtIo.writeCompressed(tag,bytes);}catch(Exception e){throw new IllegalStateException(e);}long heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();Sagadyssey.LOGGER.info("R3_REGION_PERFORMANCE_ACCEPT sites=4 people=12 peakBacklog={} backlog=0 model={} transitions={} navigationQueue={} authoritativeCompressedBytes={} heapBefore={} heapAfter={} heapDelta={} (heap includes Minecraft chunk/GC changes)",peak[0],metric,LodMetrics.snapshot(s,LodMetrics.Kind.REGION_HANDOFF),NpcNavigationQueue.backlog(s),bytes.size(),baseline[0],heap,heap-baseline[0]);h.succeed();stage[0]=4;}
 });}
}
