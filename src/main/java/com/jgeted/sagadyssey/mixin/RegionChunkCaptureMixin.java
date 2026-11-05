package com.jgeted.sagadyssey.mixin;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
/** Non-loading view of already retained chunks, including the unload queue's final physical objects. */
@Mixin(ChunkMap.class)
public abstract class RegionChunkCaptureMixin implements NaturalVillageEconomyData.RetainedChunks {
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
 @Override public LevelChunk sagadyssey$retained(long position){
  var h=updatingChunkMap.get(position);if(h==null)h=pendingUnloads.get(position);
  return h!=null&&h.getLatestChunk() instanceof LevelChunk chunk?chunk:null;
 }
}
