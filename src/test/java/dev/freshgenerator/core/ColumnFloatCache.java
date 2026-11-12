package dev.freshgenerator.core;

import java.util.Arrays;

/** Primitive task-owned cache preserving the previous height HashMap's hit/miss trace.
 * A new key at capacity starts the same logical empty generation as the old map. Epoch
 * stamps make that operation O(1) without clearing an array or allocating boxed entries.
 * Crucially, an earlier collision must NOT evict a value and reorder native sampling.
 * Backing arrays start at 32 slots and grow only when half full; sparse forest tasks
 * do not allocate the full 2048-slot buffer. Unsupported features allocate no arrays.
 * Not shared across generation tasks, threads or worlds. NaN is the miss sentinel.
 */
public final class ColumnFloatCache {
    private final int capacity;
    private int mask;
    private long[] keys;
    private float[] values;
    private int[] epochs;
    private int epoch = 1, size;

    public ColumnFloatCache(int capacity) {
        if (capacity < 4 || capacity > (1 << 20) || (capacity & (capacity - 1)) != 0)
            throw new IllegalArgumentException("Capacity must be a power of two from 4 through 1048576");
        this.capacity = capacity;
    }

    public float get(long key) {
        if (keys == null) return Float.NaN;
        int slot = find(key);
        return epochs[slot] == epoch ? values[slot] : Float.NaN;
    }

    public void put(long key, float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Column sample must be finite");
        if (keys == null) allocate(Math.min(capacity * 2, 32));
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
        } else if ((size + 1) * 2 > keys.length) {
            grow();
            slot = find(key);
        }
        keys[slot] = key;
        values[slot] = value;
        epochs[slot] = epoch;
        size++;
    }

    public int capacity() { return capacity; }

    private void allocate(int length) {
        keys = new long[length];
        values = new float[length];
        epochs = new int[length];
        mask = length - 1;
    }

    private void grow() {
        long[] previousKeys = keys;
        float[] previousValues = values;
        int[] previousEpochs = epochs;
        allocate(previousKeys.length * 2);
        // Rehash LIVE entries only. Growing must not create a miss or call a sampler.
        for (int i = 0; i < previousKeys.length; i++) if (previousEpochs[i] == epoch) {
            int slot = find(previousKeys[i]);
            keys[slot] = previousKeys[i];
            values[slot] = previousValues[i];
            epochs[slot] = epoch;
        }
    }

    private int find(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        int slot = (int)h & mask;
        while (epochs[slot] == epoch && keys[slot] != key) slot = (slot + 1) & mask;
        return slot;
    }
}
