package dev.freshgenerator.mixin;

import dev.freshgenerator.hydrology.HydrologyAccess;
import dev.freshgenerator.hydrology.HydrologyBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiNoiseBiomeSource.class)
abstract class BiomeSourceHydrologyMixin {
    @Unique private volatile HydrologyBiomes freshgenerator$hydrologyBiomes;

    @Shadow private Climate.ParameterList<Holder<Biome>> parameters() { throw new AssertionError(); }

    @Inject(method = "createResolver", at = @At("RETURN"), cancellable = true)
    private void freshgenerator$pointBiomes(Climate.Sampler climate, CallbackInfoReturnable<BiomeResolver> callback) {
        freshgenerator$wrap(climate, callback);
    }

    @Inject(method = "createResolverForChunk", at = @At("RETURN"), cancellable = true)
    private void freshgenerator$chunkBiomes(Climate.Sampler climate, int x, int y, int z, int sx, int sy, int sz,
                                            CallbackInfoReturnable<BiomeResolver> callback) {
        freshgenerator$wrap(climate, callback);
    }

    @Unique
    private void freshgenerator$wrap(Climate.Sampler climate, CallbackInfoReturnable<BiomeResolver> callback) {
        var sampler = ((HydrologyAccess) (Object) climate).freshgenerator$drainage();
        if (sampler == null) return;
        HydrologyBiomes biomes = freshgenerator$hydrologyBiomes;
        if (biomes == null) freshgenerator$hydrologyBiomes = biomes = new HydrologyBiomes(parameters());
        callback.setReturnValue(biomes.wrap(callback.getReturnValue(), climate, sampler));
    }
}
