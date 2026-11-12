package dev.freshgenerator.core;

import java.util.Arrays;

/** Primitive task-owned cache preserving the previous height HashMap's hit/miss trace.
 * A new key at capacity starts the same logical empty generation as the old map. Epoch
 * stamps make that operation O(1) without clearing an array or allocating boxed entries.
 * Crucially, an earlier collision must NOT evict a value and reorder native sampling.
 * Backing arrays are lazy: unsupported forest features pay no fixed buffer allocation.
 * Not shared across generation tasks, threads or worlds. NaN is the miss sentinel.
 */
public final class ColumnFloatCache {
    private final int capacity, mask;
    private long[] keys;
    private float[] values;
    private int[] epochs;
    private int epoch = 1, size;

    public ColumnFloatCache(int capacity) {
        if (capacity < 4 || capacity > (1 << 20) || (capacity & (capacity - 1)) != 0)
            throw new IllegalArgumentException("Capacity must be a power of two from 4 through 1048576");
        this.capacity = capacity;
        mask = capacity * 2 - 1;
    }

    public float get(long key) {
        if (keys == null) return Float.NaN;
        int slot = find(key);
        return epochs[slot] == epoch ? values[slot] : Float.NaN;
    }

    public void put(long key, float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Column sample must be finite");
        if (keys == null) {
            keys = new long[mask + 1];
            values = new float[mask + 1];
            epochs = new int[mask + 1];
        }
        int slot = find(key);
        if (epochs[slot] == epoch) {
            values[slot] = value;
            return;
        }
        if (size == capacity) {
            if (epoch == Integer.MAX_VALUE) {
                Arrays.fill(epochs, 0);
                epoch = 1;
            } else epoch++;
            size = 0;
            slot = find(key);
        }
        keys[slot] = key;
        values[slot] = value;
        epochs[slot] = epoch;
        size++;
    }

    public int capacity() { return capacity; }

    private int find(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        int slot = (int)h & mask;
        // At most half the table is live, so an empty slot always exists.
        while (epochs[slot] == epoch && keys[slot] != key) slot = (slot + 1) & mask;
        return slot;
    }
}
