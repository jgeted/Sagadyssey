package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.growth.GrowthLayout;
import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.growth.SettlementSurveyNavigation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Eligibility component checks on a fresh fixture, never a claim of autonomous recovery. */
@GameTestHolder("sagadyssey_playability_fix") @PrefixGameTestTemplate(false)
public final class KnownNaturalSheepGameTests {
 private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind OBSERVER=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("known-sheep-component");
 @GameTestGenerator public static Collection<TestFunction> tests(){return "known-sheep-boundaries".equals(System.getProperty("sagadyssey.playability.phase",""))?List.of(new TestFunction("playability_fix","known_sheep_boundaries","sagadyssey_playability_fix:empty",1500,0,true,KnownNaturalSheepGameTests::run)):List.of();}
 private static void run(GameTestHelper h){
  var level=h.getLevel();var village=NaturalVillageR1GameTests.fixture(h,0,GrowthLayout.COORDINATED);
  long initialized=level.getGameTime();
  com.jgeted.sagadyssey.npc.entity.NpcBase[] moving={null};
  BlockPos[] destination={null},origin={null};long[] movementStart={-1},preparedAt={0};int[] initialBodyTicks={0};
  var corridor=new ArrayList<BlockPos>();
  h.onEachTick(()->{
   if(moving[0]!=null){
    var n=moving[0];var p=destination[0];
    long now=level.getGameTime();
    if(movementStart[0]<0){
     h.assertTrue(now-preparedAt[0]<=200,"bounded initial entity-ticket readiness within200 ticks");
     if(!corridor.stream().allMatch(level::isPositionEntityTicking))return;
     // Initial placement only, after the entire corridor is confirmed entity-ticking.
     // Keep AI/navigation live and keep the old LEGACY+IDLE component backend.
     var begin=origin[0];n.snapTo(begin.getX()+.5,begin.getY(),begin.getZ()+.5,0,0);n.setCommand(NpcCommand.IDLE);
     NpcBrainRuntime.setMode(n,NpcBehaviorMode.LEGACY);
     h.assertTrue(!n.isNoAi()&&n.isAlive(),"original body must run real AI and physics");
     h.assertTrue(Math.abs(n.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)-900)<.001,"unchanged actual30-block start distance");
     var path=n.getNavigation().createPath(p,0);
     Sagadyssey.LOGGER.info("KNOWN_SHEEP_APPROACH_INITIAL distance=30 nativeCanReach={} followRange={} entityTickingStart={} entityTickingTarget={} corridorCells={} npcTicks={} gameTime={} begin={} target={} mode={} activePlan={}",path!=null&&path.canReach(),n.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE),level.isPositionEntityTicking(begin),level.isPositionEntityTicking(p),corridor.size(),n.tickCount,now,begin,p,NpcBrainRuntime.mode(n),NpcBrainRuntime.data(level.getServer()).active(n.getUUID()).map(x->x.id().toString()).orElse("none"));
     initialBodyTicks[0]=n.tickCount;movementStart[0]=now;return;
    }
    long elapsed=now-movementStart[0];int bodyTicks=n.tickCount-initialBodyTicks[0];
    h.assertTrue(level.isPositionEntityTicking(n.blockPosition())&&corridor.stream().allMatch(level::isPositionEntityTicking),"body, whole corridor and target must remain entity-ticking; never count a frozen body as walking");
    h.assertTrue(elapsed<=500,"unchanged500-tick arrival deadline");
    h.assertTrue(bodyTicks>=elapsed-2,"NPC must actually tick throughout the measured global-time window");
    if(elapsed%50==0){
     var path=n.getNavigation().getPath();
     Sagadyssey.LOGGER.info("KNOWN_SHEEP_APPROACH_TRACE elapsed={} npcTicks={} position={} distance={} entityTicking=true mode={} pathTarget={} pathDone={} navigation={} activePlan={}",elapsed,bodyTicks,n.position(),Math.sqrt(n.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)),NpcBrainRuntime.mode(n),path==null?"none":path.getTarget(),n.getNavigation().isDone(),SettlementSurveyNavigation.status(n),NpcBrainRuntime.data(level.getServer()).active(n.getUUID()).map(x->x.id().toString()).orElse("none"));
    }
    if(n.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)<=6.25){
     h.assertTrue(bodyTicks>0,"actual body ticks and displacement required");
     Sagadyssey.LOGGER.info("KNOWN_SHEEP_APPROACH_PASS componentOnly=true elapsed={} npcTicks={} position={} actualMovement=true entityTicking=true finalDistance={} body={} person={}",elapsed,bodyTicks,n.position(),Math.sqrt(n.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)),n.getUUID(),n.getCharacterId());h.succeed();return;
    }
    h.assertTrue(elapsed<500,"original executor must actually reach beyond-follow-range target within500 ticks");
    try{var method=EntityResourceTask.class.getDeclaredMethod("approach",com.jgeted.sagadyssey.npc.entity.NpcBase.class,BlockPos.class);method.setAccessible(true);method.invoke(null,n,p);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}return;
   }
   h.assertTrue(level.getGameTime()-initialized<=600,"resident initialization must finish within600 ticks");
   var worker=NaturalVillageR1GameTests.actor(level,village,2);if(worker==null)return;
   // Use village-local coordinates so no rotation clears the original house or farm.
   var point=NaturalVillageLayout.at(village,new BlockPos(0,1,45));
   var begin=NaturalVillageLayout.at(village,new BlockPos(-30,1,45));
   int minX=Math.min(begin.getX(),point.getX()),maxX=Math.max(begin.getX(),point.getX());
   int minZ=Math.min(begin.getZ(),point.getZ()),maxZ=Math.max(begin.getZ(),point.getZ());
   int ticketCount=0;
   for(int x=(minX-8)>>4;x<=(maxX+8)>>4;x++)for(int z=(minZ-8)>>4;z<=(maxZ+8)>>4;z++){
    h.assertTrue(++ticketCount<=25,"component loaded-region budget");
    var chunk=new ChunkPos(x,z);level.setChunkForced(x,z,true);level.getChunk(x,z);com.jgeted.sagadyssey.worldsim.ChunkTickets.add(level.getChunkSource(), OBSERVER,chunk,2,chunk,true);
   }
   for(var p:BlockPos.betweenClosed(new BlockPos(minX-4,point.getY(),minZ-4),new BlockPos(maxX+4,point.getY()+2,maxZ+4)))level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
   for(var p:BlockPos.betweenClosed(new BlockPos(minX-4,point.getY()-1,minZ-4),new BlockPos(maxX+4,point.getY()-1,maxZ+4)))level.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
   for(var p:BlockPos.betweenClosed(new BlockPos(minX-4,point.getY(),minZ-4),new BlockPos(maxX+4,point.getY(),maxZ+4)))corridor.add(p.immutable());
   Sagadyssey.LOGGER.info("KNOWN_SHEEP_COMPONENT_SETUP gameTime={} loadedChunks={} origin={} rotation={} begin={} target={} initialConfigurationOnly=true noProductionOutput=true",level.getGameTime(),ticketCount,village.buildingOrigin(),village.rotation(),begin,point);
   for(var p:BlockPos.betweenClosed(point.offset(-4,0,-4),point.offset(4,2,4)))level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
   for(var p:BlockPos.betweenClosed(point.offset(-4,-1,-4),point.offset(4,-1,4)))level.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
   var sheep=EntityType.SHEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);sheep.snapTo(point.getX()+.5,point.getY(),point.getZ()+.5,0,0);
   EventHooks.finalizeMobSpawn(sheep,level,level.getCurrentDifficultyAt(point),EntitySpawnReason.NATURAL,null);sheep.setColor(DyeColor.WHITE);
   h.assertTrue(level.addFreshEntity(sheep),"independent component fixture animal");
   h.assertTrue(!worker.isWorkAllowedAt(point)&&worker.distanceToSqr(sheep)>16*16&&SettlementIndustryAccess.local(worker,point),"outside old work zone and search16, inside loaded local64");
   var data=new EntityResourceActionData();h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"unknown outside-zone sheep denied");
   var source=new CompoundTag();source.store("Id", UUIDUtil.CODEC,UUID.randomUUID());source.store("Need", UUIDUtil.CODEC,UUID.randomUUID());source.store("Actor", UUIDUtil.CODEC,worker.getCharacterId());source.store("Owner", UUIDUtil.CODEC,worker.getCharacterId());source.store("Target", UUIDUtil.CODEC,sheep.getUUID());source.putString("Kind","SHEAR_NATURAL");source.putString("Dimension",level.dimension().identifier().toString());source.put("Output",new CompoundTag());data.putSource(source);
   h.assertTrue(NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"known original animal admitted inside bounded local area");
   h.assertTrue(NaturalIndustrySources.discoverSheep(worker,data)==sheep,"revisit same loaded UUID beyond16 without new anonymous discovery");
   sheep.setCustomName(Component.literal("private"));h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"named sheep remains protected");sheep.setCustomName(null);
   sheep.setBaby(true);h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"child remains protected");sheep.setBaby(false);
   sheep.setColor(DyeColor.BLACK);h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"unsupported wool remains denied");sheep.setColor(DyeColor.WHITE);
   sheep.setSheared(true);h.assertTrue(NaturalIndustrySources.discoverSheep(worker,data)!=sheep,"no wool means no harvest opportunity");sheep.setSheared(false);
   level.setBlockAndUpdate(point.offset(2,0,0),Blocks.OAK_FENCE.defaultBlockState());h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"managed habitat remains protected");level.setBlockAndUpdate(point.offset(2,0,0),Blocks.AIR.defaultBlockState());
   var beyond=NaturalVillageLayout.at(village,new BlockPos(0,1,65));sheep.snapTo(beyond.getX()+.5,beyond.getY(),beyond.getZ()+.5,0,0);h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"known target beyond64 remains denied");
   sheep.snapTo(point.getX()+.5,point.getY(),point.getZ()+.5,0,0);var managed=source.copy();managed.store("Id", UUIDUtil.CODEC,UUID.randomUUID());managed.putString("Kind","SHEAR");data.putSource(managed);h.assertTrue(!NaturalIndustrySources.wildSheepAllowed(worker,sheep,data),"registered managed title cannot be bypassed by old wild history");
   Sagadyssey.LOGGER.info("KNOWN_SHEEP_BOUNDARIES_PASS componentOnly=true noProductionOutput=true unknownDenied=true knownBeyond16=true local64Enforced=true managedDenied=true");
   // This is a physical navigation component, not a manufactured business plan or harvest.
   moving[0]=worker;origin[0]=begin;destination[0]=point;preparedAt[0]=level.getGameTime();
  });
 }
}
