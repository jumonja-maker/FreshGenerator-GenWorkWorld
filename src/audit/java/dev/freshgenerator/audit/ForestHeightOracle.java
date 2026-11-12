package dev.freshgenerator.audit;

import java.util.HashMap;
import java.util.Map;

/** Test-only shadow of the published 0.3.2 height-cache protocol.
 * Consumes actual native sample results; never issues a second sampler/biome query.
 * Each instance follows ONE ForestEnvironment, including its exact eviction point.
 * Aggregates completed calls atomically; background decoration counts are not a
 * cache-correctness oracle and are reported separately by ForestTelemetry.
 */
public final class ForestHeightOracle {
    private static long calls, hits, misses, mismatches;
    private final Map<Long, Float> reference = new HashMap<>();
    private boolean pending, sampled;
    private int x, z;
    private long key;
    private Float expected;
    private float observed;

    public void begin(int x, int z) {
        if (pending) fail("Overlapping/reentrant height-cache call");
        pending = true;
        sampled = false;
        this.x = x;
        this.z = z;
        key = ((long)x << 32) | (z & 0xffffffffL);
        expected = reference.get(key);
    }

    public void sampled(int x, int y, int z, float value) {
        if (!pending || sampled || expected != null)
            fail("Native sampler called on a cache hit or more than once");
        if (x != this.x || z != this.z || y != 0)
            fail("Native sampler received a different column");
        if (!Float.isFinite(value)) fail("Nonfinite native height");
        sampled = true;
        observed = value;
    }

    public void finish(float actual) {
        if (!pending) fail("Height return without matching call");
        boolean hit = expected != null;
        if (!hit && !sampled) fail("Cache miss did not call native sampler");
        float wanted = hit ? expected : observed;
        if (!Float.isFinite(actual) || Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(wanted))
            fail("Height bits differ from the published cache");
        if (!hit) {
            if (reference.size() >= 1024) reference.clear();
            reference.put(key, actual);
        }
        pending = false;
        complete(hit);
    }

    private static synchronized void complete(boolean hit) {
        calls++;
        if (hit) hits++; else misses++;
    }

    private static synchronized void fail(String message) {
        mismatches++;
        throw new IllegalStateException("Forest height oracle: " + message);
    }

    public static synchronized Map<String, Long> snapshot() {
        return Map.of("verified_calls", calls, "hits", hits, "misses", misses, "mismatches", mismatches);
    }
}
