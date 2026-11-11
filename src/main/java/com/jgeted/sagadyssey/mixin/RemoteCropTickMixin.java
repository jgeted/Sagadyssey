package com.jgeted.sagadyssey.mixin;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Freeze a loaded physical mirror until the remote owner hands it back. */
@Mixin({CropBlock.class,FarmBlock.class})
public class RemoteCropTickMixin {
 @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
 private void sagadyssey$remoteMirror(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(NaturalVillageEconomyData.protects(level.getServer(),level.dimension().identifier().toString(),pos))ci.cancel();}
}
