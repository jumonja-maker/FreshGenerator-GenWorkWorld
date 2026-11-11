package dev.freshgenerator.surface;

import java.util.Set;
import net.minecraft.resources.Identifier;

/** Deliberately excludes water, shores, deserts, wetlands, rare special palettes and unknown mod biomes. */
public final class EcotonePolicy {
    public static final String SETTINGS="freshgenerator:highlands_v2";
    public static final Identifier SEED_ID=Identifier.parse("freshgenerator:ecotone_surface");
    public static final Set<String> BIOMES=Set.of("minecraft:plains","minecraft:sunflower_plains",
        "minecraft:forest","minecraft:flower_forest","minecraft:birch_forest","minecraft:old_growth_birch_forest",
        "minecraft:dark_forest","minecraft:meadow","minecraft:cherry_grove","minecraft:windswept_forest",
        "minecraft:windswept_hills","minecraft:windswept_gravelly_hills","minecraft:grove",
        "minecraft:snowy_slopes","minecraft:jagged_peaks","minecraft:frozen_peaks","minecraft:stony_peaks",
        "minecraft:taiga","minecraft:snowy_taiga","minecraft:snowy_plains");
    public static final Set<String> COLD_PALETTES=Set.of("minecraft:frozen_peaks","minecraft:snowy_slopes","minecraft:grove");
    private EcotonePolicy() { }
}
