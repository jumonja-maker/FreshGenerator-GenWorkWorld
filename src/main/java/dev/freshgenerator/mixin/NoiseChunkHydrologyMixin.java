package dev.freshgenerator.mixin;

import dev.freshgenerator.hydrology.HydrologyAccess;
import dev.freshgenerator.hydrology.HydrologyAquifer;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
abstract class NoiseChunkHydrologyMixin {
    @Shadow @Final @Mutable private Aquifer aquifer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void freshgenerator$surfaceWater(RandomState state, Beardifier beard, NoiseGeneratorSettings settings,
                                             Aquifer.FluidPicker picker, Blender blender, DensityVolume volume, CallbackInfo callback) {
        var sampler = ((HydrologyAccess) (Object) state).freshgenerator$drainage();
        if (sampler != null && blender.isEmpty()) aquifer = new HydrologyAquifer(aquifer, sampler);
    }
}
