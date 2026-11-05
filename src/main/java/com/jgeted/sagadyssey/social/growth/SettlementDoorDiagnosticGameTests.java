package com.jgeted.sagadyssey.social.growth;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import java.nio.file.*;
/** Opt-in read observations only, on a preserved failed-world copy. */
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementDoorDiagnosticGameTests {
 private static long start=-1;
 @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
  var s=e.getServer();if(!(s instanceof GameTestServer)||!Files.exists(s.getServerDirectory().resolve("door-diagnostic.flag")))return;
  var l=s.overworld();long now=l.getGameTime();if(start<0)start=now;if(now%20!=0)return;
  for(var entity:l.getAllEntities())if(entity instanceof NpcBase n){
   var p=NpcBrainRuntime.data(s).active(n.getUUID()).orElse(null);if(p==null)continue;
   var step=p.currentStep();var params=step.params();var path=n.getNavigation().getPath();
   String nodes="";if(path!=null)for(int j=path.getNextNodeIndex();j<Math.min(path.getNodeCount(),path.getNextNodeIndex()+5);j++)nodes+=path.getNodePos(j)+";";
   BlockPos target=null;if(params.containsKey("x")&&params.containsKey("y")&&params.containsKey("z"))target=new BlockPos(Integer.parseInt(params.get("x")),Integer.parseInt(params.get("y")),Integer.parseInt(params.get("z")));
   Sagadyssey.LOGGER.info("DOOR_DIAGNOSTIC tick={} person={} body={} profession={} pos={} motion={} sleeping={} ground={} collision={} status={} gate={} pathTarget={} nodes={} plan={} step={}",now,n.getCharacterId(),n.getUUID(),n.getProfession(),n.position(),n.getDeltaMovement(),n.isSleeping(),n.onGround(),n.horizontalCollision,SettlementSurveyNavigation.status(n),target==null?null:SettlementDevelopmentData.gateWaypoint(n,target),path==null?null:path.getTarget(),nodes,p.providerId(),step);
  }
  long window=2400;try{var value=Files.readString(s.getServerDirectory().resolve("door-diagnostic.flag")).trim();if(value.equals("8000"))window=8000;}catch(Exception ignored){}
  if(now-start>=window)try{Files.writeString(s.getServerDirectory().resolve("stop-calibration.flag"),"Read-only door diagnostic complete; not acceptance.");}catch(Exception x){throw new IllegalStateException(x);}
 }
}
