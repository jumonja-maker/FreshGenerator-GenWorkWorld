package dev.freshgenerator.core;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/** Seed-local drainage on the original, unblended elevation model. */
public final class DrainageModel {
    public static final int TILE_SIZE = 128;
    public static final float MAX_CUT = 18;
    public static final int DRY = 0;
    public static final int SPACING = 16;
    public static final int MAX_FLOW = 256;
    public static final int MIN_FLOW = 16;
    static final long NO_CELL = Long.MIN_VALUE;
    private static final int CELLS = TILE_SIZE / SPACING;
    private static final int SIDE = TILE_SIZE + 1;
    private static final int REACH_MARGIN = 3;

    private final long seed;
    private final int sea;
    private final Supplier<Fields> fields;
    private final Tiles<Grid> grids = new Tiles<>(512, this::buildGrid);
    private final Tiles<List<DrainageBasin>> basins = new Tiles<>(256, this::buildBasins);
    private final Tiles<Tile> rasters = new Tiles<>(128, this::buildTile);

    @FunctionalInterface
    public interface Field {
        float at(int x, int z);
    }

    public record Fields(Field terrain, Field continents, Field moisture) { }

    public DrainageModel(long seed, int sea, Supplier<Fields> fields) {
        this.seed = seed;
        this.sea = sea;
        this.fields = fields;
    }

    public Tile tile(int tileX, int tileZ) {
        return rasters.get(tileX, tileZ);
    }

    public static int waterTop(int profile) { return (short) (profile >>> 16); }
    public static int floor(int profile) { return (profile << 16) >> 18; }
    public static boolean isLake(int profile) { return (profile & 1) != 0; }
    public static boolean hasSpillway(int profile) { return (profile & 2) != 0; }

    public int discharge(int gridX, int gridZ) {
        return flow(key(gridX, gridZ), new long[MAX_FLOW], true);
    }

    public static final class Tile {
        private final int x0;
        private final int z0;
        private final float[] cut;
        private final int[] water;

        private Tile(int x0, int z0, float[] cut, int[] water) {
            this.x0 = x0;
            this.z0 = z0;
            this.cut = cut;
            this.water = water;
        }

        public float cutAt(int x, int z) { return cut[(z - z0) * SIDE + x - x0]; }
        public int waterAt(int x, int z) { return water[(z - z0) * SIDE + x - x0]; }
    }

    record Cell(long key, int x, int z, float height, int runoff, boolean ocean, long receiver) { }

    Cell cell(int gx, int gz) {
        return grid(gx, gz).cell(gx, gz);
    }

    Cell cell(long key) { return cell(x(key), z(key)); }

    int sea() { return sea; }

    float riverLevel(Cell cell) {
        return Math.max(sea - 1, cell.height - 0.8f);
    }

    private Grid grid(int gx, int gz) {
        return grids.get(Math.floorDiv(gx, CELLS), Math.floorDiv(gz, CELLS));
    }

    private Grid buildGrid(int tx, int tz) {
        Fields samples = fields.get();
        int gx0 = tx * CELLS, gz0 = tz * CELLS;
        int side = CELLS + 2;
        int[] x = new int[side * side];
        int[] z = new int[x.length];
        float[] h = new float[x.length];
        for (int iz = 0; iz < side; iz++) {
            for (int ix = 0; ix < side; ix++) {
                int gx = gx0 + ix - 1, gz = gz0 + iz - 1;
                int i = iz * side + ix;
                long hash = mix(seed + gx * 0x9E3779B97F4A7C15L + gz * 0xC2B2AE3D27D4EB4FL);
                x[i] = gx * SPACING + (int) (hash & 7) - 3;
                z[i] = gz * SPACING + (int) ((hash >>> 8) & 7) - 3;
                h[i] = samples.terrain.at(x[i], z[i]);
            }
        }
        Cell[] cells = new Cell[CELLS * CELLS];
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                int i = (iz + 1) * side + ix + 1;
                float continentalness = samples.continents.at(x[i], z[i]);
                boolean ocean = continentalness <= -0.24f || (continentalness < -0.16f && h[i] <= sea);
                int runoff = 1 + Math.round(3 * Math.clamp(0.5f + samples.moisture.at(x[i], z[i]), 0, 1));
                long receiver = NO_CELL;
                float steepest = 0;
                if (!ocean) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = i + dz * side + dx;
                            float drop = h[i] - h[j];
                            if (drop <= 0.015f) continue;
                            float slope = drop * drop / distance2(x[j] - x[i], z[j] - z[i]);
                            if (slope > steepest) {
                                receiver = key(gx0 + ix + dx, gz0 + iz + dz);
                                steepest = slope;
                            }
                        }
                    }
                }
                long key = key(gx0 + ix, gz0 + iz);
                cells[iz * CELLS + ix] = new Cell(key, x[i], z[i], h[i], runoff, ocean, receiver);
            }
        }
        return new Grid(gx0, gz0, cells);
    }

    private List<DrainageBasin> buildBasins(int tx, int tz) {
        List<DrainageBasin> result = new ArrayList<>();
        long[] pending = new long[MAX_FLOW];
        Fields samples = fields.get();
        for (Cell center : grids.get(tx, tz).cells) {
            if (center.ocean || center.receiver != NO_CELL || center.height < sea - 6) continue;
            int raw = flow(center.key, pending, false);
            if (raw < MIN_FLOW) continue;
            DrainageBasin basin = DrainageBasin.find(this, center, raw, samples.terrain);
            if (basin != null) result.add(basin);
        }
        return List.copyOf(result);
    }

    /**
     * Reverse traversal of a single-receiver DAG. Every visited cell contributes at
     * least one unit, so discovering MAX_FLOW distinct ancestors is sufficient even
     * for an infinite upslope area. Below that cap the entire contributing area is visited.
     * A spill edge skips its lake's saddle and ends strictly below its original sink.
     */
    private int flow(long start, long[] pending, boolean includeSpills) {
        int gx = x(start), gz = z(start);
        Grid origin = grid(gx, gz);
        int slot = origin.index(gx, gz);
        AtomicIntegerArray memo = includeSpills ? origin.flow : origin.rawFlow;
        int saved = memo.get(slot);
        if (saved != 0) return saved;

        pending[0] = start;
        int count = 1;
        int total = 0;
        for (int read = 0; read < count && total < MAX_FLOW; read++) {
            long current = pending[read];
            int cx = x(current), cz = z(current);
            Grid local = grid(cx, cz);
            int index = local.index(cx, cz);
            AtomicIntegerArray partial = includeSpills ? local.flow : local.rawFlow;
            int known = current == start ? 0 : partial.get(index);
            if (known != 0) {
                total += known;
                continue;
            }
            Cell cell = local.cells[index];
            total += cell.runoff;
            for (int dz = -1; dz <= 1 && count < MAX_FLOW; dz++) {
                for (int dx = -1; dx <= 1 && count < MAX_FLOW; dx++) {
                    int nx = cx + dx, nz = cz + dz;
                    Cell other = local.contains(nx, nz) ? local.cell(nx, nz) : cell(nx, nz);
                    if (other.receiver == current) pending[count++] = other.key;
                }
            }
            if (includeSpills && count < MAX_FLOW && total < MAX_FLOW && !cell.ocean) {
                int radius = DrainageBasin.SEARCH;
                for (int tz = Math.floorDiv(cz - radius, CELLS); tz <= Math.floorDiv(cz + radius, CELLS); tz++) {
                    for (int tx = Math.floorDiv(cx - radius, CELLS); tx <= Math.floorDiv(cx + radius, CELLS); tx++) {
                        for (DrainageBasin basin : basins.get(tx, tz)) {
                            if (basin.outlet() == current && count < MAX_FLOW) pending[count++] = basin.center().key;
                        }
                    }
                }
            }
            if (count == MAX_FLOW) total = MAX_FLOW;
        }
        total = Math.min(MAX_FLOW, total);
        memo.compareAndSet(slot, 0, total);
        return total;
    }

    private Tile buildTile(int tx, int tz) {
        Fields samples = fields.get();
        Raster raster = new Raster(tx * TILE_SIZE, tz * TILE_SIZE, samples.terrain);
        int gx0 = tx * CELLS, gz0 = tz * CELLS;
        long[] pending = new long[MAX_FLOW];
        for (int gz = gz0 - REACH_MARGIN; gz <= gz0 + CELLS + REACH_MARGIN; gz++) {
            for (int gx = gx0 - REACH_MARGIN; gx <= gx0 + CELLS + REACH_MARGIN; gx++) {
                Cell start = cell(gx, gz);
                if (start.receiver == NO_CELL) continue;
                int flow = flow(start.key, pending, true);
                if (flow < MIN_FLOW) continue;
                Cell end = cell(start.receiver);
                int nextFlow = end.ocean ? flow : flow(end.key, pending, true);
                raster.reach(start, end, riverLevel(start), riverLevel(end), flow, nextFlow);
            }
        }
        int extent = DrainageBasin.SEARCH + REACH_MARGIN + 1;
        for (int bz = Math.floorDiv(gz0 - extent, CELLS); bz <= Math.floorDiv(gz0 + CELLS + extent, CELLS); bz++) {
            for (int bx = Math.floorDiv(gx0 - extent, CELLS); bx <= Math.floorDiv(gx0 + CELLS + extent, CELLS); bx++) {
                for (DrainageBasin basin : basins.get(bx, bz)) {
                    if (!basin.isNear(raster.x0, raster.z0, TILE_SIZE + SPACING)) continue;
                    if (basin.outlet() != NO_CELL) {
                        int flow = flow(basin.center().key, pending, true);
                        long[] path = basin.path();
                        float level = basin.top();
                        float endLevel = riverLevel(cell(basin.outlet()));
                        for (int i = 1; i < path.length; i++) {
                            Cell a = cell(path[i - 1]), b = cell(path[i]);
                            float next = i <= basin.saddleIndex() ? level
                                    : Math.min(level, Math.max(endLevel, riverLevel(b)));
                            raster.reach(a, b, level, next, flow, flow);
                            level = next;
                        }
                    }
                    basin.paint(raster);
                }
            }
        }
        return raster.finish();
    }

    static final class Raster {
        private final int x0, z0;
        private final Field terrain;
        private final float[] height = new float[SIDE * SIDE];
        private final float[] cut = new float[height.length];
        private final int[] water = new int[height.length];

        Raster(int x0, int z0, Field terrain) {
            this.x0 = x0;
            this.z0 = z0;
            this.terrain = terrain;
            Arrays.fill(height, Float.NaN);
        }

        Tile finish() { return new Tile(x0, z0, cut, water); }

        void lakeColumn(int x, int z, int top, boolean spilling) {
            if (x < x0 || x > x0 + TILE_SIZE || z < z0 || z > z0 + TILE_SIZE) return;
            int i = (z - z0) * SIDE + x - x0;
            wet(i, original(i, x, z), top, top - 0.2f, spilling ? 3 : 1);
        }

        private float original(int i, int x, int z) {
            float h = height[i];
            if (Float.isNaN(h)) height[i] = h = terrain.at(x, z);
            return h;
        }

        private void wet(int i, float h, int top, float target, int flags) {
            boolean lake = (flags & 1) != 0;
            float proposed = Math.max(0.25f, h - target);
            if (proposed > MAX_CUT) return;
            int floor = (int) Math.ceil(h - proposed) - 1;
            if (floor >= top) return;
            int old = water[i];
            if (old != DRY && (isLake(old) && !lake
                    || isLake(old) == lake && (waterTop(old) < top || (waterTop(old) == top && cut[i] >= proposed)))) return;
            water[i] = (top << 16) | ((floor & 0x3fff) << 2) | flags;
            cut[i] = proposed;
        }

        void reach(Cell a, Cell b, float upstreamY, float downstreamY, int upstreamQ, int downstreamQ) {
            int dx = b.x - a.x, dz = b.z - a.z;
            float length = (float) Math.sqrt(distance2(dx, dz));
            float grade = Math.max(0, upstreamY - downstreamY) / length;
            float maxWidth = width(downstreamQ, grade);
            int margin = (int) Math.ceil(2 * maxWidth + 5);
            int minX = Math.max(x0, Math.min(a.x, b.x) - margin);
            int maxX = Math.min(x0 + TILE_SIZE, Math.max(a.x, b.x) + margin);
            int minZ = Math.max(z0, Math.min(a.z, b.z) - margin);
            int maxZ = Math.min(z0 + TILE_SIZE, Math.max(a.z, b.z) + margin);
            if (minX > maxX || minZ > maxZ) return;

            // On gentle slopes a curve is allowed where the sampled valley is lower.
            // At steeper grades the route contracts toward the original descent.
            int mx = a.x + dx / 2, mz = a.z + dz / 2;
            float bend = (1 - smooth(0.045f, 0.22f, grade)) * Math.min(4.5f, maxWidth * 0.7f);
            float offX = -dz * bend / length, offZ = dx * bend / length;
            float straight = terrain.at(mx, mz);
            float left = terrain.at(Math.round(mx + offX), Math.round(mz + offZ));
            float right = terrain.at(Math.round(mx - offX), Math.round(mz - offZ));
            float turn = Math.min(left, right) < straight - 0.12f ? (left <= right ? 1 : -1) : 0;

            float[] vx = {0, dx / 3f + offX * turn * 0.89f, 2 * dx / 3f + offX * turn * 0.89f, dx};
            float[] vz = {0, dz / 3f + offZ * turn * 0.89f, 2 * dz / 3f + offZ * turn * 0.89f, dz};
            float[] invLength = new float[3];
            for (int s = 0; s < 3; s++) invLength[s] = 1 / distance2(vx[s + 1] - vx[s], vz[s + 1] - vz[s]);

            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    float best = Float.POSITIVE_INFINITY, t = 0;
                    for (int s = 0; s < 3; s++) {
                        float sx = vx[s + 1] - vx[s], sz = vz[s + 1] - vz[s];
                        float px = x - a.x - vx[s], pz = z - a.z - vz[s];
                        float projection = Math.clamp((px * sx + pz * sz) * invLength[s], 0, 1);
                        float d2 = distance2(px - projection * sx, pz - projection * sz);
                        if (d2 < best) {
                            best = d2;
                            t = (s + projection) / 3;
                        }
                    }
                    float flow = lerp(t, upstreamQ, downstreamQ);
                    float width = width(flow, grade);
                    if (best > 3.24f * width * width) continue;
                    float r = (float) Math.sqrt(best) / width;
                    float level = lerp(t, upstreamY, downstreamY);
                    int top = (int) Math.floor(level + 0.0001f);
                    int i = (z - z0) * SIDE + x - x0;
                    float h = original(i, x, z);
                    if (a.ocean || b.ocean && h < downstreamY - 3) continue;
                    float depth = Math.min(6.5f, 1.55f + 0.25f * (float) Math.sqrt(flow) + 2.4f * grade);
                    if (r <= 1 || (r <= 1.18f && h < top + 0.35f)) {
                        wet(i, h, top, top - Math.max(0.25f, depth * (1 - r * r * 0.88f)), 0);
                    } else if (water[i] == DRY) {
                        float bank = top + 1 + (r - 1) * Math.max(3, 0.8f * width);
                        float amount = Math.max(0, h - bank) * (1 - smooth(1, 1.8f, r));
                        cut[i] = Math.max(cut[i], Math.min(MAX_CUT, amount));
                    }
                }
            }
        }

        private static float width(float flow, float grade) {
            return (2.2f + 0.46f * (float) Math.sqrt(flow)) * (1 - 0.33f * smooth(0.05f, 0.35f, grade));
        }
    }

    private static final class Grid {
        private final int x0, z0;
        private final Cell[] cells;
        private final AtomicIntegerArray rawFlow = new AtomicIntegerArray(CELLS * CELLS);
        private final AtomicIntegerArray flow = new AtomicIntegerArray(CELLS * CELLS);

        Grid(int x0, int z0, Cell[] cells) { this.x0 = x0; this.z0 = z0; this.cells = cells; }

        int index(int x, int z) { return (z - z0) * CELLS + x - x0; }
        Cell cell(int x, int z) { return cells[index(x, z)]; }
        boolean contains(int x, int z) { return x >= x0 && x < x0 + CELLS && z >= z0 && z < z0 + CELLS; }
    }

    @FunctionalInterface
    private interface TileFactory<T> { T build(int x, int z); }

    private static final class Tiles<T> {
        private record Entry<T>(long key, T value) { }

        private final AtomicReferenceArray<Entry<T>> slots;
        private final Object[] locks = new Object[32];
        private final TileFactory<T> factory;

        Tiles(int size, TileFactory<T> factory) {
            slots = new AtomicReferenceArray<>(size);
            Arrays.setAll(locks, i -> new Object());
            this.factory = factory;
        }

        T get(int x, int z) {
            long key = key(x, z);
            int slot = (int) mix(key) & (slots.length() - 1);
            Entry<T> current = slots.get(slot);
            if (current != null && current.key == key) return current.value;
            // Locks are acquired only in raster -> basin -> grid order.
            synchronized (locks[slot & (locks.length - 1)]) {
                current = slots.get(slot);
                if (current != null && current.key == key) return current.value;
                T value = factory.build(x, z);
                slots.set(slot, new Entry<>(key, value));
                return value;
            }
        }
    }

    static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    static int x(long key) { return (int) (key >> 32); }
    static int z(long key) { return (int) key; }

    private static float distance2(float x, float z) { return x * x + z * z; }

    private static float smooth(float a, float b, float value) {
        float t = Math.clamp((value - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float t, float a, float b) {
        return t == 1 ? b : a + t * (b - a);
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
