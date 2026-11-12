package dev.freshgenerator.mixin;

import dev.freshgenerator.hydrology.DrainageDensity;
import dev.freshgenerator.hydrology.HydrologyAccess;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomState.class)
abstract class RandomStateHydrologyMixin implements HydrologyAccess {
    @Unique private DrainageDensity.Sampler freshgenerator$drainage;

    @Override public DrainageDensity.Sampler freshgenerator$drainage() { return freshgenerator$drainage; }
    @Override public void freshgenerator$drainage(DrainageDensity.Sampler sampler) { freshgenerator$drainage = sampler; }

    @Inject(method = "create(Lnet/minecraft/core/HolderGetter;JLnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;)Lnet/minecraft/world/level/levelgen/RandomState;", at = @At("RETURN"))
    private static void freshgenerator$bind(HolderGetter<NormalNoise> noises, long seed, NoiseGeneratorSettings settings,
                                             CallbackInfoReturnable<RandomState> callback) {
        RandomState state = callback.getReturnValue();
        ((HydrologyAccess) (Object) state).freshgenerator$drainage(DrainageDensity.from(state, settings));
    }

    @Inject(method = "createClimateSampler", at = @At("RETURN"))
    private void freshgenerator$bindClimate(SamplerContext context, CallbackInfoReturnable<Climate.Sampler> callback) {
        if (freshgenerator$drainage != null) {
            ((HydrologyAccess) (Object) callback.getReturnValue()).freshgenerator$drainage(freshgenerator$drainage);
        }
    }
}
