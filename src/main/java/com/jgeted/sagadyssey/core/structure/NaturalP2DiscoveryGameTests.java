package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Candidate-specific normal-terrain callback check, not a replacement for human arrival. */
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalP2DiscoveryGameTests {
    private static BlockPos located;
    private static long started;
    private static boolean finished;
    @SubscribeEvent public static void start(ServerStartedEvent e){
        if(!Boolean.getBoolean("sagadyssey.p2.discovery"))return;
        var s=e.getServer();var l=s.overworld();
        if(l.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource||!s.getWorldData().worldGenOptions().generateStructures()||s.getWorldData().worldGenOptions().seed()!=7019L)throw new IllegalStateException("P2 frozen seed7019 ordinary terrain required");
        var holder=l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,NaturalVillageBuilding.SUPPORTED));
        var found=l.getChunkSource().getGenerator().findNearestMapStructure(l,HolderSet.direct(holder),BlockPos.ZERO,24,false);
        if(found==null)throw new IllegalStateException("P2 natural site not found within frozen radius");
        located=found.getFirst();var cp=new ChunkPos(located);
        for(int x=cp.x-2;x<=cp.x+2;x++)for(int z=cp.z-2;z<=cp.z+2;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
        started=l.getGameTime();finished=false;
        Sagadyssey.LOGGER.info("P2_DISCOVERY ordinary terrain seed=7019 located={} radiusChunks=24 noManualPlacement=true",located);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        if(!Boolean.getBoolean("sagadyssey.p2.discovery")||located==null||finished)return;
        var s=e.getServer();var l=s.overworld();long elapsed=l.getGameTime()-started;
        if(elapsed<200||elapsed%20!=0)return;
        var data=NaturalVillageLifeData.existing(s);
        if(data!=null)for(var v:data.villages()){
            var b=NaturalVillageLifeData.decode(v);if(!v.getBooleanOr("Ready", false)||b.settlementCenter().distSqr(located)>128*128)continue;
            NaturalMemberP2GameTests.verify(l,b);
            try{var marker=new CompoundTag();marker.putLong("Seed",7019);marker.putLong("Located",located.asLong());marker.put("Village",v);marker.putString("Evidence","ordinary terrain automatic callback; only observed template, no manual init or item injection");NbtIo.writeCompressed(marker,s.getWorldPath(LevelResource.ROOT).resolve("p2-natural-discovery.nbt"));s.saveEverything(true,true,true);}
            catch(Exception ex){throw new IllegalStateException(ex);}
            finished=true;Sagadyssey.LOGGER.info("P2_DISCOVERY_ACCEPT ordinaryTerrain=true template={} household=3 formalKnowledgeRightsTarget=true elapsed={}",b.templateId(),elapsed);s.halt(false);return;
        }
        if(elapsed>=1200){finished=true;Sagadyssey.LOGGER.error("P2_DISCOVERY_FAILED elapsed={} intents={}",elapsed,data==null?"none":data.villages());s.halt(false);}
    }
}
