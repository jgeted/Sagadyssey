package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Isolated normal dedicated-world acceptance, excluded from the production jar. */
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalVillageDiscoveryGameTests {
    private static BlockPos located;
    private static long started;
    private static boolean finished;
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.society.discovery");}
    @SubscribeEvent public static void start(ServerStartedEvent e){
        if(!enabled())return;var server=e.getServer();var l=server.overworld();
        if(l.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource||!server.getWorldData().worldGenOptions().generateStructures())throw new IllegalStateException("Normal terrain with structure generation required");
        var registry=l.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var holder=registry.getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,NaturalVillageBuilding.SUPPORTED));
        var found=l.getChunkSource().getGenerator().findNearestMapStructure(l,HolderSet.direct(holder),BlockPos.ZERO,24,false);
        if(found==null)throw new IllegalStateException("Supported natural village not found in frozen discovery region");
        located=found.getFirst();var cp=new ChunkPos(located);
        // The audit emulates a player visiting only the found site's footprint, not a world scan.
        for(int x=cp.x-2;x<=cp.x+2;x++)for(int z=cp.z-2;z<=cp.z+2;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
        started=l.getGameTime();finished=false;
        Sagadyssey.LOGGER.info("R1_DISCOVERY_VISIT seed={} location={} generator={}",server.getWorldData().worldGenOptions().seed(),located,l.getChunkSource().getGenerator().getClass().getSimpleName());
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        if(!enabled()||located==null||finished)return;var server=e.getServer();var l=server.overworld();long elapsed=l.getGameTime()-started;
        if(elapsed<200||elapsed%20!=0)return;
        var data=NaturalVillageLifeData.existing(server);
        if(data!=null)for(var v:data.villages()){
            var b=NaturalVillageLifeData.decode(v);if(b.settlementCenter().distSqr(located)>128*128||!v.getBooleanOr("Ready", false))continue;
            if(SettlementRegistryData.get(server).building(b.buildingKey())==null)throw new IllegalStateException("Natural identity absent");
            for(int i=0;i<3;i++)if(!(l.getEntity(NaturalVillageLifeData.body(b,i)) instanceof com.jgeted.sagadyssey.npc.entity.NpcBase n)||!n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)))throw new IllegalStateException("Natural resident missing");
            try{var marker=new CompoundTag();marker.putLong("Seed",server.getWorldData().worldGenOptions().seed());marker.putLong("Located",located.asLong());marker.put("Village",v);NbtIo.writeCompressed(marker,server.getWorldPath(LevelResource.ROOT).resolve("society-r1-natural-discovery.nbt"));server.saveEverything(true,true,true);}
            catch(Exception x){throw new IllegalStateException(x);}
            finished=true;Sagadyssey.LOGGER.info("R1_DISCOVERY_ACCEPT ordinaryTerrain=true naturalStart=true people=3 settlement={} origin={}",b.settlementId(),b.buildingOrigin());server.halt(false);return;
        }
        if(elapsed>=1200){finished=true;Sagadyssey.LOGGER.error("R1_DISCOVERY_FAILED no ready natural household at {} intents={}",located,data==null?"none":data.villages());server.halt(false);}
    }
}
