package dev.freshgenerator.mixin;

import dev.freshgenerator.core.DrainageModel;
import dev.freshgenerator.hydrology.DrainageDensity;
import dev.freshgenerator.hydrology.HydrologyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Do not let a tree's below-trunk soil replace a modeled surface source. */
@Mixin(TreeFeature.class)
abstract class TreeHydrologyMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void freshgenerator$keepWater(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                            BlockPos origin, CallbackInfoReturnable<Boolean> callback) {
        var drainage = freshgenerator$drainage(level);
        if (drainage == null || origin.getY() < drainage.seaLevel() - 16) return;

        // Wide native trunks can set their ground in the 2 x 2 area from the origin.
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int water = drainage.waterAt(origin.getX() + dx, origin.getZ() + dz);
                if (water != DrainageModel.DRY && origin.getY() > DrainageModel.floor(water)
                        && origin.getY() <= DrainageModel.waterTop(water) + 1) {
                    callback.setReturnValue(false);
                    return;
                }
            }
        }
    }

    // Native fallen logs share this predicate for every block of the log.
    @Inject(method = "validTreePos", at = @At("HEAD"), cancellable = true)
    private static void freshgenerator$keepWaterUnderLogs(LevelSimulatedReader level, BlockPos pos,
                                                          CallbackInfoReturnable<Boolean> callback) {
        var drainage = freshgenerator$drainage(level);
        if (drainage == null || pos.getY() < drainage.seaLevel() - 16) return;
        int water = drainage.waterAt(pos.getX(), pos.getZ());
        if (water != DrainageModel.DRY && pos.getY() > DrainageModel.floor(water)
                && pos.getY() <= DrainageModel.waterTop(water)) callback.setReturnValue(false);
    }

    @Unique
    private static DrainageDensity.Sampler freshgenerator$drainage(LevelSimulatedReader level) {
        if (!(level instanceof WorldGenRegion region)) return null;
        return ((HydrologyAccess) (Object) region.getLevel().getChunkSource().randomState()).freshgenerator$drainage();
    }
}
