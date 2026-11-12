package dev.freshgenerator.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Local D8 drainage of the unmodified terrain. Every tile has the same finite upstream halo. */
public final class DrainageModel {
    public static final int TILE_SIZE = 128;
    public static final float MAX_CUT = 18;
    private static final int SPACING = 16;
    private static final int CELLS = TILE_SIZE / SPACING;
    private static final int FLOW_STEPS = 9;
    private static final int LAKE_SEARCH = 5;
    private static final int REACH = 3;
    private static final int HALO = FLOW_STEPS + REACH + 2;
    private static final int SIDE = CELLS + 2 * HALO + 1;
    private static final int AREA = SIDE * SIDE;
    private static final int RASTER_STEP = 4;
    private static final int RASTER_SIDE = TILE_SIZE / RASTER_STEP + 1;
    private static final int MIN_RIVER_FLOW = 7;

    private DrainageModel() { }

    @FunctionalInterface
    public interface Field {
        float at(int x, int z);
    }

    public static Tile build(int tileX, int tileZ, long seed, int sea, Field terrain, Field continents) {
        float[] cut = new float[RASTER_SIDE * RASTER_SIDE];
        float[] elevation = new float[cut.length];
        int originX = tileX * TILE_SIZE;
        int originZ = tileZ * TILE_SIZE;
        boolean eligible = false;
        for (int rz = 0; rz < RASTER_SIDE; rz++) {
            for (int rx = 0; rx < RASTER_SIDE; rx++) {
                int i = rz * RASTER_SIDE + rx;
                int x = originX + rx * RASTER_STEP;
                int z = originZ + rz * RASTER_STEP;
                float height = terrain.at(x, z);
                if (height <= sea - 8 || height >= sea + 72) continue;
                float land = smooth(-0.24f, -0.14f, continents.at(x, z));
                if (land == 0) continue;
                elevation[i] = height;
                cut[i] = land * smooth(sea - 8, sea - 5, height) * (1 - smooth(sea + 56, sea + 72, height));
                eligible |= cut[i] > 0;
            }
        }
        if (!eligible) return new Tile(tileX, tileZ, cut);
        var grid = new Grid(tileX, tileZ, seed, terrain);
        grid.route();
        grid.findLakes(sea, continents);
        for (int i = 0; i < cut.length; i++) {
            if (cut[i] == 0) continue;
            int x = originX + i % RASTER_SIDE * RASTER_STEP;
            int z = originZ + i / RASTER_SIDE * RASTER_STEP;
            cut[i] *= grid.cut(x, z, elevation[i], sea);
        }
        return new Tile(tileX, tileZ, cut);
    }

    public static final class Tile {
        private final int x;
        private final int z;
        private final float[] cut;

        private Tile(int x, int z, float[] cut) {
            this.x = x;
            this.z = z;
            this.cut = cut;
        }

        public boolean isAt(int tileX, int tileZ) {
            return x == tileX && z == tileZ;
        }

        public float cutAt(int blockX, int blockZ) {
            int dx = blockX - x * TILE_SIZE;
            int dz = blockZ - z * TILE_SIZE;
            int ix = Math.min(dx / RASTER_STEP, RASTER_SIDE - 2);
            int iz = Math.min(dz / RASTER_STEP, RASTER_SIDE - 2);
            float fx = (float) (dx - ix * RASTER_STEP) / RASTER_STEP;
            float fz = (float) (dz - iz * RASTER_STEP) / RASTER_STEP;
            int i = iz * RASTER_SIDE + ix;
            return lerp(fz, lerp(fx, cut[i], cut[i + 1]),
                    lerp(fx, cut[i + RASTER_SIDE], cut[i + RASTER_SIDE + 1]));
        }
    }

    private static final class Grid {
        private final int gridX;
        private final int gridZ;
        private final int[] x = new int[AREA];
        private final int[] z = new int[AREA];
        private final float[] height = new float[AREA];
        private final int[] downstream = new int[AREA];
        private int[] flow = new int[AREA];
        private final float[] spill = new float[AREA];

        private Grid(int tileX, int tileZ, long seed, Field terrain) {
            gridX = tileX * CELLS - HALO;
            gridZ = tileZ * CELLS - HALO;
            for (int gz = 0; gz < SIDE; gz++) {
                for (int gx = 0; gx < SIDE; gx++) {
                    int i = gz * SIDE + gx;
                    int worldX = gridX + gx;
                    int worldZ = gridZ + gz;
                    long hash = mix(seed + (long) worldX * 0x9E3779B97F4A7C15L + (long) worldZ * 0xC2B2AE3D27D4EB4FL);
                    x[i] = worldX * SPACING + (int) (hash & 7) - 3;
                    z[i] = worldZ * SPACING + (int) ((hash >>> 8) & 7) - 3;
                    height[i] = terrain.at(x[i], z[i]);
                }
            }
        }

        private void route() {
            Arrays.fill(downstream, -1);
            for (int gz = 1; gz < SIDE - 1; gz++) {
                for (int gx = 1; gx < SIDE - 1; gx++) {
                    int i = gz * SIDE + gx;
                    float steepest = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = i + dz * SIDE + dx;
                            float drop = height[i] - height[j];
                            if (drop <= 0.05f) continue;
                            int distance2 = squaredDistance(x[i] - x[j], z[i] - z[j]);
                            float slope2 = drop * drop / distance2;
                            if (slope2 > steepest) {
                                steepest = slope2;
                                downstream[i] = j;
                            }
                        }
                    }
                }
            }
            Arrays.fill(flow, 1);
            int[] next = new int[AREA];
            for (int step = 0; step < FLOW_STEPS; step++) {
                Arrays.fill(next, 1);
                for (int i = 0; i < AREA; i++) {
                    int j = downstream[i];
                    if (j >= 0) next[j] += flow[i];
                }
                int[] previous = flow;
                flow = next;
                next = previous;
            }
        }

        private void findLakes(int sea, Field continents) {
            Arrays.fill(spill, Float.NEGATIVE_INFINITY);
            for (int gz = HALO - REACH; gz <= HALO + CELLS + REACH; gz++) {
                for (int gx = HALO - REACH; gx <= HALO + CELLS + REACH; gx++) {
                    int i = gz * SIDE + gx;
                    float floor = height[i];
                    if (downstream[i] >= 0 || flow[i] < 5 || floor < sea - 2 || floor > sea + 12
                            || continents.at(x[i], z[i]) <= -0.07f) continue;
                    float rim = spillHeight(i, gx, gz);
                    if (rim >= Math.max(sea + 3, floor + 2.5f)) spill[i] = rim;
                }
            }
        }

        private float spillHeight(int center, int gx, int gz) {
            var pending = new PriorityQueue<Integer>(Comparator.<Integer>comparingDouble(i -> height[i]).thenComparingInt(i -> i));
            var visited = new boolean[(2 * LAKE_SEARCH + 1) * (2 * LAKE_SEARCH + 1)];
            int stride = 2 * LAKE_SEARCH + 1;
            pending.add(center);
            visited[LAKE_SEARCH * stride + LAKE_SEARCH] = true;
            float level = height[center];
            while (!pending.isEmpty()) {
                int i = pending.remove();
                int dx = i % SIDE - gx;
                int dz = i / SIDE - gz;
                level = Math.max(level, height[i]);
                if (Math.abs(dx) == LAKE_SEARCH || Math.abs(dz) == LAKE_SEARCH) return level;
                for (int nz = -1; nz <= 1; nz++) {
                    for (int nx = -1; nx <= 1; nx++) {
                        int key = (dz + nz + LAKE_SEARCH) * stride + dx + nx + LAKE_SEARCH;
                        if (visited[key]) continue;
                        visited[key] = true;
                        pending.add(i + nz * SIDE + nx);
                    }
                }
            }
            throw new IllegalStateException("Drainage search has no boundary");
        }

        private float cut(int blockX, int blockZ, float terrain, int sea) {
            int gx = Math.floorDiv(blockX, SPACING) - gridX;
            int gz = Math.floorDiv(blockZ, SPACING) - gridZ;
            float cut = 0;
            for (int dz = -REACH; dz <= REACH; dz++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    int i = (gz + dz) * SIDE + gx + dx;
                    if (Float.isFinite(spill[i])) cut = Math.max(cut, lakeCut(i, blockX, blockZ, terrain, sea));
                    int j = downstream[i];
                    if (j >= 0 && flow[i] >= MIN_RIVER_FLOW && Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                        cut = Math.max(cut, riverCut(i, j, blockX, blockZ, terrain, sea));
                    }
                }
            }
            return Math.min(MAX_CUT, cut);
        }

        private float lakeCut(int i, int blockX, int blockZ, float terrain, int sea) {
            float radius = Math.min(35, 18 + 2.5f * (float) Math.sqrt(flow[i]));
            float distance = (float) Math.sqrt(squaredDistance(blockX - x[i], blockZ - z[i]));
            float edge = 1 - smooth(radius * 0.30f, radius, distance);
            float belowRim = smooth(0.6f, 3, spill[i] - terrain);
            return edge * belowRim * Math.max(0, terrain - (sea - 5));
        }

        private float riverCut(int i, int j, int blockX, int blockZ, float terrain, int sea) {
            int dx = x[j] - x[i], dz = z[j] - z[i];
            int px = blockX - x[i], pz = blockZ - z[i];
            float t = Math.clamp((float) (px * dx + pz * dz) / squaredDistance(dx, dz), 0, 1);
            float width = Math.min(12, 3 + 1.1f * (float) Math.sqrt(lerp(t, flow[i], flow[j])));
            float ex = px - t * dx, ez = pz - t * dz;
            float distance2 = ex * ex + ez * ez;
            if (distance2 >= width * width) return 0;
            float bank = 1 - smooth(0.12f, 1, distance2 / (width * width));
            float slopeHeight = lerp(t, height[i], height[j]);
            float aroundBed = 1 - smooth(2, 9, terrain - slopeHeight);
            float lowland = 1 - smooth(sea + 4, sea + 17, slopeHeight);
            float upperCut = Math.min(4.5f, 1 + 0.3f * (float) Math.sqrt(flow[i]));
            float wetCut = Math.clamp(terrain - (sea - 4), 0, 13);
            return bank * aroundBed * lerp(lowland, upperCut, wetCut);
        }
    }

    private static int squaredDistance(int dx, int dz) {
        return dx * dx + dz * dz;
    }

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
