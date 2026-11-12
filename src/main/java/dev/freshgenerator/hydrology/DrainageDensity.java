package dev.freshgenerator.hydrology;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.DrainageModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.CachingDensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** The same compiled, seed-bound sampler supplies the terrain cut and surface water. */
public record DrainageDensity(DensityFunction terrain, DensityFunction continents, Optional<DensityFunction> moisture,
                              int seaLevel, int heightGrid) implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:drainage");
    public static final String LABEL = "FreshGenerator drainage";
    public static final MapCodec<DrainageDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.CODEC.fieldOf("terrain").forGetter(DrainageDensity::terrain),
            DensityFunction.CODEC.fieldOf("continents").forGetter(DrainageDensity::continents),
            DensityFunction.CODEC.optionalFieldOf("moisture").forGetter(DrainageDensity::moisture),
            Codec.INT.fieldOf("sea_level").forGetter(DrainageDensity::seaLevel),
            Codec.intRange(0, 1024).optionalFieldOf("height_grid", 0).forGetter(DrainageDensity::heightGrid)
    ).apply(instance, DrainageDensity::new));

    public static Sampler from(RandomState state, NoiseGeneratorSettings settings) {
        for (var debug : settings.debugFunctions().functions()) {
            if (!debug.label().equals(LABEL)) continue;
            DensitySampler sampler = state.getSampler(debug.function());
            while (true) {
                switch (sampler) {
                    case Sampler drainage -> { return drainage; }
                    case CachingDensitySampler cache -> sampler = cache.input();
                    case SliceFunction.YSampler horizontal -> sampler = horizontal.input();
                    default -> throw new IllegalStateException("Unexpected FreshGenerator drainage sampler: " + sampler.getClass().getName());
                }
            }
        }
        return null;
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        long seed = context.createRandom(ID).nextLong();
        return new Sampler(terrain.compileSampler(context), continents.compileSampler(context),
                moisture.map(input -> input.compileSampler(context)).orElse(null), seaLevel, seed, heightGrid);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return new DrainageDensity(rule.rewrite(terrain), rule.rewrite(continents), moisture.map(rule::rewrite), seaLevel, heightGrid);
    }

    @Override public Interval range() { return Interval.of(-DrainageModel.MAX_BANK_RAISE, DrainageModel.MAX_CUT); }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<DrainageDensity> codec() { return CODEC; }

    public static final class Sampler implements DensitySampler {
        private final OriginalHeight terrain;
        private final DrainageModel model;
        private final int seaLevel;

        private Sampler(DensitySampler terrain, DensitySampler continents, DensitySampler moisture, int seaLevel, long seed, int heightGrid) {
            this.terrain = new OriginalHeight(terrain, heightGrid);
            this.seaLevel = seaLevel;
            model = new DrainageModel(seed, seaLevel, () -> {
                SamplerContext context = SamplerContext.builder().enableCaches().build();
                return new DrainageModel.Fields(
                        (x, z) -> this.terrain.at(context, x, z),
                        (x, z) -> continents.sampleValue(context, x, 0, z),
                        (x, z) -> moisture == null ? 0 : moisture.sampleValue(context, x, 0, z));
            });
        }

        public int seaLevel() { return seaLevel; }

        private DrainageModel.Tile tile(int x, int z) {
            return model.tile(Math.floorDiv(x, DrainageModel.TILE_SIZE), Math.floorDiv(z, DrainageModel.TILE_SIZE));
        }

        public int waterAt(int x, int z) {
            return tile(x, z).waterAt(x, z);
        }

        public float originalHeightAt(SamplerContext context, int x, int z) {
            return terrain.at(context, x, z);
        }

        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return tile(x, z).cutAt(x, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            for (int x = 0; x < volume.sizeX(); x++) {
                for (int z = 0; z < volume.sizeZ(); z++) {
                    float cut = sampleValue(context, volume.blockX(x), 0, volume.blockZ(z));
                    for (int y = 0; y < volume.sizeY(); y++) output.set(volume.indexUnchecked(x, y, z), cut);
                }
            }
        }
    }

    /** Cache the corners of the native bilinear exterior, using its same interpolation order. */
    private static final class OriginalHeight {
        private static final int CACHE_SIZE = 16384;
        private record Corner(int x, int z, float height) { }
        private final DensitySampler sampler;
        private final int spacing;
        private final AtomicReferenceArray<Corner> corners = new AtomicReferenceArray<>(CACHE_SIZE);

        OriginalHeight(DensitySampler sampler, int spacing) {
            this.sampler = sampler;
            this.spacing = spacing;
        }

        float at(SamplerContext context, int x, int z) {
            if (spacing == 0) return sampler.sampleValue(context, x, 0, z);
            int dx = Math.floorMod(x, spacing), dz = Math.floorMod(z, spacing);
            int x0 = x - dx, z0 = z - dz;
            float first = corner(context, x0, z0);
            if ((dx | dz) == 0) return first;
            return Mth.lerp2((float) dx / spacing, (float) dz / spacing,
                    first, corner(context, x0 + spacing, z0),
                    corner(context, x0, z0 + spacing), corner(context, x0 + spacing, z0 + spacing));
        }

        private float corner(SamplerContext context, int x, int z) {
            int hash = x * 0x9e3779b9 ^ Integer.rotateLeft(z * 0x85ebca6b, 13);
            int index = (hash ^ (hash >>> 16)) & (CACHE_SIZE - 1);
            Corner entry = corners.get(index);
            if (entry != null && entry.x == x && entry.z == z) return entry.height;
            float height = sampler.sampleValue(context, x, 0, z);
            corners.set(index, new Corner(x, z, height));
            return height;
        }
    }
}
