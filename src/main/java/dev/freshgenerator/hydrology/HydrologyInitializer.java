package dev.freshgenerator.hydrology;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class HydrologyInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, DrainageDensity.ID, DrainageDensity.CODEC);
    }
}
