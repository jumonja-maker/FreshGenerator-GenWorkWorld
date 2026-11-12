package dev.freshgenerator.core;

import java.util.Arrays;

/** Bounded, allocation-free lookup for pure column samples. Owned by one generation task,
 * never shared across threads/worlds. Full 64-bit keys distinguish negative coordinates.
 * Four-way sets replace one entry on collision instead of flushing the entire cache.
 * NaN means a miss; only finite values are accepted, including both signed zeroes.
 */
public final class ColumnFloatCache {
    private static final int WAYS = 4;
    private final long[] keys;
    private final float[] values;
    private final byte[] next;
    private final int setMask;

    public ColumnFloatCache(int capacity) {
        if (capacity < WAYS || capacity > (1 << 20) || (capacity & (capacity - 1)) != 0)
            throw new IllegalArgumentException("Capacity must be a power of two from 4 through 1048576");
        keys = new long[capacity];
        values = new float[capacity];
        Arrays.fill(values, Float.NaN);
        next = new byte[capacity / WAYS];
        setMask = next.length - 1;
    }

    public float get(long key) {
        int base = base(key);
        for (int way = 0; way < WAYS; way++) {
            int slot = base + way;
            if (keys[slot] == key && !Float.isNaN(values[slot])) return values[slot];
        }
        return Float.NaN;
    }

    public void put(long key, float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Column sample must be finite");
        int base = base(key), empty = -1;
        for (int way = 0; way < WAYS; way++) {
            int slot = base + way;
            if (!Float.isNaN(values[slot]) && keys[slot] == key) {
                values[slot] = value;
                return;
            }
            if (empty < 0 && Float.isNaN(values[slot])) empty = slot;
        }
        if (empty < 0) {
            int set = base / WAYS;
            empty = base + next[set];
            next[set] = (byte)((next[set] + 1) & (WAYS - 1));
        }
        keys[empty] = key;
        values[empty] = value;
    }

    public int capacity() { return values.length; }

    private int base(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return ((int)h & setMask) * WAYS;
    }
}
