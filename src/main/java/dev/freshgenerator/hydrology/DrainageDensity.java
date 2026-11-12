package dev.freshgenerator.hydrology;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.DrainageModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** A two-dimensional, seed-bound terrain cut with a bounded, immutable tile cache. */
public record DrainageDensity(DensityFunction terrain, DensityFunction continents, int seaLevel)
        implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:drainage");
    public static final MapCodec<DrainageDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.CODEC.fieldOf("terrain").forGetter(DrainageDensity::terrain),
            DensityFunction.CODEC.fieldOf("continents").forGetter(DrainageDensity::continents),
            Codec.INT.fieldOf("sea_level").forGetter(DrainageDensity::seaLevel)
    ).apply(instance, DrainageDensity::new));

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        long seed = context.createRandom(ID).nextLong();
        return new Sampler(terrain.compileSampler(context), continents.compileSampler(context), seaLevel, seed);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction rewrittenTerrain = rule.rewrite(terrain);
        DensityFunction rewrittenContinents = rule.rewrite(continents);
        if (rewrittenTerrain == terrain && rewrittenContinents == continents) return this;
        return new DrainageDensity(rewrittenTerrain, rewrittenContinents, seaLevel);
    }

    @Override public Interval range() { return Interval.of(0, DrainageModel.MAX_CUT); }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<DrainageDensity> codec() { return CODEC; }

    private static final class Sampler implements DensitySampler {
        private static final int CACHE_SIZE = 128;
        private final DensitySampler terrain;
        private final DensitySampler continents;
        private final int seaLevel;
        private final long seed;
        private final AtomicReferenceArray<DrainageModel.Tile> tiles = new AtomicReferenceArray<>(CACHE_SIZE);

        private Sampler(DensitySampler terrain, DensitySampler continents, int seaLevel, long seed) {
            this.terrain = terrain;
            this.continents = continents;
            this.seaLevel = seaLevel;
            this.seed = seed;
        }

        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            int tileX = Math.floorDiv(x, DrainageModel.TILE_SIZE);
            int tileZ = Math.floorDiv(z, DrainageModel.TILE_SIZE);
            int hash = tileX * 0x9E3779B9 ^ tileZ * 0x85EBCA6B;
            int index = (hash ^ (hash >>> 16)) & (CACHE_SIZE - 1);
            DrainageModel.Tile cached = tiles.get(index);
            if (cached != null && cached.isAt(tileX, tileZ)) return cached.cutAt(x, z);
            // Build from the unblended terrain in absolute coordinates. A cache miss
            // or a neighbouring chunk using a different tile cannot change the result.
            SamplerContext local = SamplerContext.builder().enableCaches().build();
            DrainageModel.Tile built = DrainageModel.build(tileX, tileZ, seed, seaLevel,
                    (bx, bz) -> terrain.sampleValue(local, bx, 0, bz),
                    (bx, bz) -> continents.sampleValue(local, bx, 0, bz));
            tiles.compareAndSet(index, cached, built);
            return built.cutAt(x, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            for (int x = 0; x < volume.sizeX(); x++) {
                for (int z = 0; z < volume.sizeZ(); z++) {
                    float cut = sampleValue(context, volume.blockX(x), 0, volume.blockZ(z));
                    for (int y = 0; y < volume.sizeY(); y++) {
                        output.set(volume.indexUnchecked(x, y, z), cut);
                    }
                }
            }
        }
    }
}
