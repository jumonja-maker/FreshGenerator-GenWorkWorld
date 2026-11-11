package dev.freshgenerator;

import dev.freshgenerator.surface.EcotoneSurfaceRule;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Standalone native registry-driven generator. No third-party generator dependency. */
public final class FreshGenerator implements ModInitializer {
    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE,Identifier.parse("freshgenerator:ecotone_surface"),EcotoneSurfaceRule.CODEC);
        System.out.println("[FreshGenerator] One world; coherent terrain, envelope-aware biome depth and shared surface transitions.");
    }
}
