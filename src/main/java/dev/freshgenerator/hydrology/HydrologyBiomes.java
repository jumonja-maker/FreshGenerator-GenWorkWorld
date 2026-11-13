package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.Set;

/** Adapts the surface band while retaining the native regional climate lookup. */
public final class HydrologyBiomes {
    private static final Set<ResourceKey<Biome>> BANK_BIOMES = Set.of(
            Biomes.PLAINS, Biomes.SNOWY_PLAINS, Biomes.FOREST, Biomes.BIRCH_FOREST,
            Biomes.DARK_FOREST, Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.SAVANNA, Biomes.DESERT, Biomes.JUNGLE,
            Biomes.SPARSE_JUNGLE, Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_FOREST);

    private final Climate.ParameterList<Holder<Biome>> land;
    private final Holder<Biome> river;
    private final Holder<Biome> frozenRiver;

    public HydrologyBiomes(Climate.ParameterList<Holder<Biome>> original) {
        var entries = original.values();
        var landEntries = entries.stream().filter(entry -> !isRiver(entry.getSecond())).toList();
        land = landEntries.isEmpty() ? null : new Climate.ParameterList<>(landEntries);
        river = entries.stream().map(entry -> entry.getSecond()).filter(b -> b.is(Biomes.RIVER)).findFirst().orElse(null);
        frozenRiver = entries.stream().map(entry -> entry.getSecond()).filter(b -> b.is(Biomes.FROZEN_RIVER)).findFirst().orElse(river);
    }

    public BiomeResolver wrap(BiomeResolver nativeResolver, Climate.Sampler climate, DrainageDensity.Sampler drainage) {
        return (qx, qy, qz) -> {
            Holder<Biome> before = nativeResolver.getNoiseBiome(qx, qy, qz);
            boolean oldRiver = isRiver(before);
            if (!oldRiver && (river == null || before.unwrapKey().filter(BANK_BIOMES::contains).isEmpty())) return before;
            int x = QuartPos.toBlock(qx), y = QuartPos.toBlock(qy), z = QuartPos.toBlock(qz);
            if (y < drainage.seaLevel() - 5) return before;
            float originalHeight = drainage.originalHeightAt(climate.depth().context(), x, z);
            if (y < originalHeight - 22 || y > originalHeight + 15) return before;
            int profile = drainage.waterAt(x, z);
            if (profile != DrainageModel.DRY) {
                if (!oldRiver && !DrainageModel.isLake(profile) && Math.abs(y - DrainageModel.waterTop(profile)) <= 4) {
                    return before.value().getBaseTemperature() < 0.15f ? frozenRiver : river;
                }
                return before;
            }
            if (!oldRiver || land == null) return before;
            float ground = originalHeight - drainage.sampleValue(climate.depth().context(), x, 0, z);
            if (ground < drainage.seaLevel() - 0.5f || Math.abs(y - ground) > 8) return before;
            // The native indexed lookup uses a thread-local previous match for ties.
            // The small surface-only fallback needs a fixed tie order.
            return land.findValueBruteForce(climate.sample(qx, qy, qz));
        };
    }

    private static boolean isRiver(Holder<Biome> biome) {
        return biome.is(Biomes.RIVER) || biome.is(Biomes.FROZEN_RIVER);
    }
}
