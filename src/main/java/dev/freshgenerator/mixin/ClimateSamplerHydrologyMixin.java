package dev.freshgenerator.mixin;

import dev.freshgenerator.hydrology.DrainageDensity;
import dev.freshgenerator.hydrology.HydrologyAccess;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Climate.Sampler.class)
abstract class ClimateSamplerHydrologyMixin implements HydrologyAccess {
    @Unique private DrainageDensity.Sampler freshgenerator$drainage;

    @Override public DrainageDensity.Sampler freshgenerator$drainage() { return freshgenerator$drainage; }
    @Override public void freshgenerator$drainage(DrainageDensity.Sampler sampler) { freshgenerator$drainage = sampler; }
}
