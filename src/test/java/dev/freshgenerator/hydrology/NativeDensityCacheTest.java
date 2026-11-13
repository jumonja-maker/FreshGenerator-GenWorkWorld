package dev.freshgenerator.hydrology;

import net.minecraft.world.level.levelgen.densityfunction.CachingDensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real native cache used by the generated graph, not a stub. */
class NativeDensityCacheTest {
    private static final class CountingSampler implements DensitySampler {
        final AtomicInteger pointCalls = new AtomicInteger();
        final AtomicInteger volumeCalls = new AtomicInteger();
        final float bias;
        CountingSampler(float bias) { this.bias = bias; }
        float value(int x, int y, int z) { return bias + x * 0.03125f - y * 0.125f + z * 0.0625f; }
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            pointCalls.incrementAndGet();
            return value(x, y, z);
        }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            volumeCalls.incrementAndGet();
            DensitySampler.sampleVolumeNaive(context, buffer, volume, this);
        }
    }

    @Test void repeatedPointUsesTheSameFloatBitsWithoutResampling() {
        var input = new CountingSampler(-0.25f);
        var cached = new CachingDensitySampler(0, input);
        var context = SamplerContext.builder().enableCaches().build();
        for (int i = 0; i < 50; i++)
            assertEquals(Float.floatToRawIntBits(input.value(-129, 67, 128)),
                    Float.floatToRawIntBits(cached.sampleValue(context, -129, 67, 128)));
        assertEquals(1, input.pointCalls.get());
        for (int y : new int[]{-64, 63, 64, 319, 0})
            assertEquals(input.value(-129, y, 128), cached.sampleValue(context, -129, y, 128), 0);
    }

    @Test void volumeReuseRetainsEveryCoordinateAndNonunitStride() {
        var input = new CountingSampler(2.5f);
        var cached = new CachingDensitySampler(0, input);
        var context = SamplerContext.builder().enableCaches().build();
        var volume = new DensityVolume(4, 5, 3, -129, -31, 127, 2, 3, 5);
        var buffer = DensityBuffer.createUnpooled(volume.size());
        cached.sampleVolume(context, buffer, volume);
        verify(input, buffer, volume);
        buffer.fill(Float.NaN);
        cached.sampleVolume(context, buffer, volume);
        verify(input, buffer, volume);
        assertEquals(1, input.volumeCalls.get());
        var moved = new DensityVolume(4, 5, 3, 1024, 180, -2049, 2, 3, 5);
        cached.sampleVolume(context, buffer, moved);
        verify(input, buffer, moved);
        cached.sampleVolume(context, buffer, volume);
        verify(input, buffer, volume);
    }

    @Test void cachesDoNotLeakBetweenContextsOrWorldSamplers() {
        var a = new CountingSampler(1);
        var b = new CountingSampler(7);
        var ac = new CachingDensitySampler(0, a);
        var bc = new CachingDensitySampler(0, b);
        var ca = SamplerContext.builder().enableCaches().build();
        var cb = SamplerContext.builder().enableCaches().build();
        assertEquals(a.value(32, 64, -32), ac.sampleValue(ca, 32, 64, -32), 0);
        assertEquals(b.value(32, 64, -32), bc.sampleValue(cb, 32, 64, -32), 0);
        assertEquals(a.value(32, 64, -32), ac.sampleValue(ca, 32, 64, -32), 0);
        assertEquals(1, a.pointCalls.get());
        assertEquals(1, b.pointCalls.get());
    }

    @Test void oneImmutableSamplerSupportsIndependentParallelContexts() {
        var input = new CountingSampler(0.75f);
        var cached = new CachingDensitySampler(0, input);
        IntStream.range(0, 32).parallel().forEach(task -> {
            var context = SamplerContext.builder().enableCaches().build();
            for (int i = 0; i < 100; i++) {
                int x = task * 128 - i, y = i - 64, z = -task * 128 + i * 3;
                assertEquals(Float.floatToRawIntBits(input.value(x, y, z)),
                        Float.floatToRawIntBits(cached.sampleValue(context, x, y, z)));
            }
        });
    }

    private static void verify(CountingSampler input, DensityBuffer buffer, DensityVolume volume) {
        for (int x = 0; x < volume.sizeX(); x++) for (int y = 0; y < volume.sizeY(); y++)
            for (int z = 0; z < volume.sizeZ(); z++)
                assertEquals(Float.floatToRawIntBits(input.value(volume.blockX(x), volume.blockY(y), volume.blockZ(z))),
                        Float.floatToRawIntBits(buffer.get(volume.indexUnchecked(x, y, z))));
    }
}
