package com.jgeted.sagadyssey.social.growth;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
/** Opt-in observations in an exact failed-world copy, never a success or repair entry. */
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementTimberDiagnosticGameTests {
    private static long start=-1;
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var s=event.getServer();if(!(s instanceof GameTestServer)||!System.getProperty("sagadyssey.development.phase","").equals("resume")||!Files.exists(s.getServerDirectory().resolve("timber-diagnostic.flag")))return;
        var d=SettlementDevelopmentData.existing(s);if(d==null||d.sites().isEmpty())return;var l=s.overworld();long now=l.getGameTime();if(start<0)start=now;if(now%200!=0)return;
        var b=NaturalVillageLifeData.decode(d.sites().getFirst().getCompound("Village"));var n=NaturalVillageR1GameTests.actor(l,b,2);if(n==null)return;
        var out=new CompoundTag();out.putLong("Time",now);out.putLong("DayTime",l.getDayTime());out.putBoolean("OrdinaryWork",SettlementDevelopmentData.ordinaryWorker(n));out.putString("Schedule",String.valueOf(com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(n)));out.putString("Active",String.valueOf(NpcBrainRuntime.data(s).active(n.getUUID())));out.put("Body",n.saveWithoutId(new CompoundTag()));
        out.putString("NavigationStatus",SettlementSurveyNavigation.status(n));out.putString("NativePath",String.valueOf(n.getNavigation().getPath()));
        var active=NpcBrainRuntime.data(s).active(n.getUUID()).orElse(null);if(active!=null){var params=active.currentStep().params();if(params.containsKey("x")&&params.containsKey("y")&&params.containsKey("z")){var dest=new BlockPos(Integer.parseInt(params.get("x")),Integer.parseInt(params.get("y")),Integer.parseInt(params.get("z")));out.putString("GateWaypoint",String.valueOf(SettlementDevelopmentData.gateWaypoint(n,dest)));}}
        var checks=new ListTag();for(int z:new int[]{23,27,31,35})for(int y=1;y<=3;y++){
            var at=NaturalVillageLayout.at(b,new BlockPos(23,y,z));var t=new CompoundTag();t.putLong("Position",at.asLong());t.putString("Block",l.getBlockState(at).toString());t.putBoolean("Allowed",FieldworkDiscovery.allowed(n,at));t.putString("Target",String.valueOf(FieldworkDiscovery.inspect(n,at)));t.putString("Route",String.valueOf(FieldworkRoutes.plan(n,at,true)));checks.add(t);
        }out.put("Timber",checks);out.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));
        try{NbtIo.writeCompressed(out,s.getWorldPath(LevelResource.ROOT).resolve("timber-diagnostic-"+now+".nbt"));if(now-start>=8000)Files.writeString(s.getServerDirectory().resolve("stop-calibration.flag"),"Read-only diagnostic window complete; no acceptance claim.");}catch(Exception x){throw new IllegalStateException(x);}
        Sagadyssey.LOGGER.info("TIMBER_DIAGNOSTIC now={} work={} schedule={} pos={} navigation={} gate={} active={} targets={}",now,out.getBoolean("OrdinaryWork"),out.getString("Schedule"),n.position(),out.getString("NavigationStatus"),out.getString("GateWaypoint"),out.getString("Active"),checks);
    }
}
