package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Geometric regressions motivated by reported canals, sea dikes and water sheets.
 * Synthetic terrain is intentional: these are not claimed to be the user's world. */
class DrainageMorphologyTest {
    private static final long[] SEEDS = {42, 1337, -20261111, 2037157180504249802L, 801086821803359396L};

    private static DrainageModel model(long seed, Field height) {
        return new DrainageModel(seed, 63, () -> new Fields(height, (x, z) -> 0.2f, (x, z) -> 0.3f));
    }

    @Test
    void quantizedLakeLipAndLowerStreamShareAnActualWetVoxel() {
        var raster = new DrainageModel.Raster(-1, -1, (x, z) -> 100, 63);
        // lakeColumn is used here to exercise the same raster's stage seam.
        raster.lakeColumn(10, 10, 101, true);
        raster.lakeColumn(11, 10, 100, true);
        var tile = raster.finish();
        int a = tile.waterAt(10, 10), b = tile.waterAt(11, 10);
        assertEquals(101, waterTop(a));
        assertEquals(100, waterTop(b));
        assertTrue(Math.min(waterTop(a), waterTop(b)) > Math.max(floor(a), floor(b)),
                "Neighboring water at different integer stages must not be separated by their beds");
        assertTrue(isLake(a) && hasSpillway(a), "Floor reconciliation must retain ownership flags");
        assertTrue(tile.cutAt(10, 10) <= MAX_CUT);
    }

    @Test
    void stageReconciliationDoesNotMoveAFlatLakeSurfaceOrDeepenAnIsolatedColumn() {
        var raster = new DrainageModel.Raster(-1, -1, (x, z) -> 100, 63);
        raster.lakeColumn(10, 10, 101, false);
        raster.lakeColumn(11, 10, 101, false);
        var tile = raster.finish();
        for (int x : new int[]{10, 11}) {
            assertEquals(101, waterTop(tile.waterAt(x, 10)));
            assertEquals(99, floor(tile.waterAt(x, 10)));
            assertEquals(0.25f, tile.cutAt(x, 10));
        }
    }

    @Test
    void anUnconfinedPlanarSlopeIsNotAFieldOfParallelCanals() {
        Field plane = (x, z) -> 100 - 0.06f * z;
        for (long seed : SEEDS) {
            var m = model(seed, plane);
            for (int tz = -1; tz <= 1; tz++) for (int tx = -1; tx <= 1; tx++) {
                var tile = m.tile(tx, tz);
                for (int z = tz * 128; z < tz * 128 + 128; z++) for (int x = tx * 128; x < tx * 128 + 128; x++) {
                    assertEquals(DRY, tile.waterAt(x, z), "Unconfined plane: seed=" + seed);
                    assertEquals(0, tile.cutAt(x, z), 0, "No cosmetic dry trench either");
                }
            }
        }
    }

    @Test
    void nativeSeaLevelWaterIsNotRecutOrRaisedIntoThinDikes() {
        Field shallow = (x, z) -> 61.2f + 0.003f * x + 0.002f * z;
        int checked = 0;
        for (long seed : SEEDS) {
            var m = model(seed, shallow);
            for (int tz = -1; tz <= 1; tz++) for (int tx = -1; tx <= 1; tx++) {
                var tile = m.tile(tx, tz);
                for (int z = tz * 128; z < tz * 128 + 128; z++) for (int x = tx * 128; x < tx * 128 + 128; x++) {
                    if (shallow.at(x, z) >= 62) continue;
                    checked++;
                    assertEquals(0, tile.cutAt(x, z), 0, "Native water must not become a levee or extra canal");
                    assertEquals(DRY, tile.waterAt(x, z), "DRY here means use native water, not erase it");
                }
            }
        }
        assertTrue(checked > 500_000);
    }

    @Test
    void theActualValleyKeepsAContinuousRiverWithoutFloodingTheAdjacentPlateau() {
        Field valley = (x, z) -> 105 - 0.055f * z + Math.min(5f, 0.18f * Math.abs(x - 64));
        for (long seed : SEEDS) {
            var m = model(seed, valley);
            int wet = 0;
            for (int z = -128; z < 256; z++) {
                int row = 0;
                for (int x = -128; x < 256; x++) {
                    int w = m.tile(Math.floorDiv(x, 128), Math.floorDiv(z, 128)).waterAt(x, z);
                    if (w != DRY) { wet++; if (x >= 40 && x <= 88) row++; }
                }
                assertTrue(row > 0, "The rework may not obtain low density by deleting the real river, z=" + z);
            }
            assertTrue(wet > 1_000 && wet < 15_000, "Expected a river corridor, not a flooded plane: " + wet);
        }
    }

    @Test
    void aCurvingValleyHasOneFourConnectedMainStemAcrossSeveralTiles() {
        Field valley = (x, z) -> 130 - 0.018f * z + 0.22f * Math.abs(x - (64 + 20 * (float) Math.sin(z / 110.0)));
        final int width = 128, height = 512;
        for (long seed : SEEDS) {
            var m = model(seed, valley);
            boolean[] wet = new boolean[width * height];
            for (int z = 0; z < height; z++) for (int x = 0; x < width; x++)
                wet[z * width + x] = m.tile(0, Math.floorDiv(z - 256, 128)).waterAt(x, z - 256) != DRY;
            boolean[] seen = new boolean[wet.length];
            var pending = new ArrayDeque<Integer>();
            for (int x = 0; x < width; x++) if (wet[x]) { pending.add(x); seen[x] = true; }
            int last = 0;
            while (!pending.isEmpty()) {
                int p = pending.remove(), x = p % width, z = p / width;
                last = Math.max(last, z);
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx < 0 || nx >= width || nz < 0 || nz >= height) continue;
                    int n = nz * width + nx;
                    if (wet[n] && !seen[n]) { seen[n] = true; pending.add(n); }
                }
            }
            assertEquals(height - 1, last, "Broken main stem, seed=" + seed);
        }
    }

    @Test
    void widthSeparatesHeadwatersMainStemsAndSteepCascades() {
        float head = DrainageModel.Raster.width(MIN_FLOW, 0);
        float river = DrainageModel.Raster.width(MAX_FLOW, 0);
        float steep = DrainageModel.Raster.width(MAX_FLOW, 0.65f);
        assertTrue(head >= 0.72f && head < 1, "No blunt full-width disc at the source threshold");
        assertTrue(river > 4 && river < 6, "Maximum radius is an explicit bounded policy");
        assertTrue(steep < river * 0.4f, "Steep rivers must contract, not make broad water ramps");
        float previous = 0;
        for (int flow = MIN_FLOW; flow <= MAX_FLOW; flow++) {
            float radius = DrainageModel.Raster.width(flow, 0.03f);
            assertTrue(radius >= previous && Float.isFinite(radius));
            previous = radius;
        }
    }

    @Test
    void cubicLinksHaveExactEndpointsFiniteArcLengthAndNoBacktracking() {
        var a = new Cell(1, 0, 0, 100, 3, false, 2);
        var b = new Cell(2, 16, 12, 99, 3, false, 3);
        var tangent = new DrainageModel.Direction(0.8f, 0.6f);
        var curve = DrainageModel.Curve.between(a, b, new DrainageModel.Direction(1, 0), tangent);
        assertEquals(0, curve.x[0], 0); assertEquals(0, curve.z[0], 0);
        int n = DrainageModel.Curve.SEGMENTS;
        assertEquals(16, curve.x[n], 0); assertEquals(12, curve.z[n], 0);
        assertTrue(curve.length >= 20 && curve.length < 24);
        for (int i = 1; i <= n; i++) {
            assertTrue(curve.distance[i] > curve.distance[i - 1]);
            assertTrue(curve.x[i] >= curve.x[i - 1]);
            assertTrue(curve.z[i] >= curve.z[i - 1]);
        }
        assertNotEquals(12f / 16, curve.z[1] / curve.x[1], "Curve is not a renamed straight segment");
    }
}
