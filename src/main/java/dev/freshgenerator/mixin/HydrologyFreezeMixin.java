package dev.freshgenerator.mixin;

import dev.freshgenerator.core.DrainageModel;
import dev.freshgenerator.hydrology.HydrologyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freeze the modeled surface, not a neighbor-dependent canopy heightmap.
 * Only existing full sources/ice in this feature's own 16x16 footprint are touched.
 * No chunk requests, structure replacement, per-tick water simulation or RNG.
 */
@Mixin(SnowAndFreezeFeature.class)
abstract class HydrologyFreezeMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void freshgenerator$stableSurface(WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> callback) {
        if (!(level instanceof WorldGenRegion region)) return;
        var drainage = ((HydrologyAccess) (Object) region.getLevel().getChunkSource().randomState())
                .freshgenerator$drainage();
        if (drainage == null) return;
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            int profile = drainage.waterAt(x, z);
            if (profile == DrainageModel.DRY) continue;
            pos.set(x, DrainageModel.waterTop(profile), z);
            var current = level.getBlockState(pos);
            if (!(current.is(Blocks.ICE) || current.is(Blocks.WATER) && current.getFluidState().isSource())) continue;
            // Native altitude/temperature calculation, independent of canopy and
            // uninitialized lighting. Later gameplay freezing/melting stays native.
            boolean frozen = level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel());
            var wanted = (frozen ? Blocks.ICE : Blocks.WATER).defaultBlockState();
            if (!current.equals(wanted)) level.setBlock(pos, wanted, 2);
        }
    }
}
