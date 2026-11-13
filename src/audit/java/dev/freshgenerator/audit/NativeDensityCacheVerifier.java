package dev.freshgenerator.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Mandatory real-API equivalence check, not included in the game JAR.
 * The exact pre-cache root is the cache's input. Compare float bits, rather than
 * allowing a density tolerance that could move a near-zero terrain boundary.
 */
public final class NativeDensityCacheVerifier {
    private NativeDensityCacheVerifier() { }

    public static JsonObject verify(MinecraftServer server, RandomState state) throws Exception {
        var registry = server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var results = new JsonArray();
        int comparisons = 0;
        for (String name : List.of("surface_density", "native_cave_body")) {
            var function = registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
                    Identifier.parse("freshgenerator:highlands_v2/coherent/" + name))).value();
            if (!(function instanceof CacheFunction cached))
                throw new AssertionError("Expected transparent root cache: " + name);
            var original = state.getSampler(cached.input());
            var optimized = state.getSampler(cached);
            var tasks = new ArrayList<Callable<JsonObject>>();
            // Two forward and two reverse traversals, each with its own contexts.
            // Their interleaving must not share mutable cache contents.
            for (int mode = 0; mode < 4; mode++) {
                final int order = mode;
                tasks.add(() -> compare(name, original, optimized, order));
            }
            try (var executor = Executors.newFixedThreadPool(4)) {
                for (var task : executor.invokeAll(tasks)) {
                    var result = task.get();
                    comparisons += result.get("float_bit_comparisons").getAsInt();
                    results.add(result);
                }
            }
        }
        var report = new JsonObject();
        report.addProperty("seed", state.seed());
        report.addProperty("float_bit_comparisons", comparisons);
        report.addProperty("different_float_bits", 0);
        report.addProperty("scope", "Real native sampler roots against their exact unwrapped inputs; finite sample, not whole-world proof");
        report.add("comparisons", results);
        System.out.println("FRESHGENERATOR_NATIVE_CACHE_PASS " + state.seed() + " " + comparisons);
        return report;
    }

    private static JsonObject compare(String name, DensitySampler original, DensitySampler optimized, int mode)
            throws Exception {
        var originalVolumeContext = SamplerContext.builder().enableCaches().build();
        var optimizedVolumeContext = SamplerContext.builder().enableCaches().build();
        var originalPointContext = SamplerContext.builder().enableCaches().build();
        var optimizedPointContext = SamplerContext.builder().enableCaches().build();
        int comparisons = 0;
        var digest = MessageDigest.getInstance("SHA-256");
        var origins = new ArrayList<int[]>();
        origins.addAll(List.of(new int[]{224, -800}, new int[]{704, -1488}, new int[]{3940, -631},
                new int[]{-1000, 434}, new int[]{-6656, -7056}, new int[]{-129, -129},
                new int[]{127, 127}, new int[]{0, 0}));
        var random = new Random(0x43414348454cL);
        for (int i = 0; i < 8; i++)
            origins.add(new int[]{random.nextInt(32768) - 16384, random.nextInt(32768) - 16384});
        if ((mode & 1) != 0) Collections.reverse(origins);
        for (var origin : origins) {
            // Full height range, tile/chunk borders and non-unit volume strides.
            var volume = new DensityVolume(5, 25, 5, origin[0] - 4, -64, origin[1] - 4, 3, 16, 3);
            var oldValues = DensityBuffer.createUnpooled(volume.size());
            var newValues = DensityBuffer.createUnpooled(volume.size());
            original.sampleVolume(originalVolumeContext, oldValues, volume);
            optimized.sampleVolume(optimizedVolumeContext, newValues, volume);
            for (int i = 0; i < volume.size(); i++) {
                requireSameBits(oldValues.get(i), newValues.get(i), name + " volume " + Arrays.toString(origin) + ":" + i);
                comparisons++;
            }
            newValues.fill(Float.NaN);
            optimized.sampleVolume(optimizedVolumeContext, newValues, volume);
            for (int i = 0; i < volume.size(); i++) {
                requireSameBits(oldValues.get(i), newValues.get(i), name + " reused volume " + i);
                comparisons++;
            }
            for (int y = -64; y < 320; y += 7) for (int i = 0; i < 7; i++) {
                int x = origin[0] + i - 3, z = origin[1] - i + 3;
                float before = original.sampleValue(originalPointContext, x, y, z);
                float after = optimized.sampleValue(optimizedPointContext, x, y, z);
                requireSameBits(before, after, name + " scalar " + x + "," + y + "," + z);
                comparisons++;
                int bits = Float.floatToRawIntBits(after);
                digest.update((byte) (bits >>> 24)); digest.update((byte) (bits >>> 16));
                digest.update((byte) (bits >>> 8)); digest.update((byte) bits);
                requireSameBits(original.sampleValue(originalVolumeContext, x, y, z),
                        optimized.sampleValue(optimizedVolumeContext, x, y, z),
                        name + " mixed " + x + "," + y + "," + z);
                comparisons++;
            }
        }
        var result = new JsonObject();
        result.addProperty("function", name);
        result.addProperty("order", mode);
        result.addProperty("float_bit_comparisons", comparisons);
        result.addProperty("scalar_sha256", HexFormat.of().formatHex(digest.digest()));
        result.addProperty("original_sampler", original.getClass().getName());
        result.addProperty("cached_sampler", optimized.getClass().getName());
        return result;
    }

    static void requireSameBits(float original, float optimized, String where) {
        if (!Float.isFinite(original) || Float.floatToRawIntBits(original) != Float.floatToRawIntBits(optimized))
            throw new AssertionError("Native density cache changed " + where + ": " + original + " != " + optimized);
    }
}
