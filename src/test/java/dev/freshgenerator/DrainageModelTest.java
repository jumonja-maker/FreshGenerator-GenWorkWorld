package dev.freshgenerator;

import dev.freshgenerator.core.DrainageModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class DrainageModelTest {
    private static final int SEA = 63;
    private static final long SEED = 42;
    private static final Field LAND = (x, z) -> 0.2f;
    private static final Field VALLEY = (x, z) -> 105 - 0.055f * z + Math.min(5f, 0.18f * Math.abs(x - 64));

    private static DrainageModel model(Field terrain, Field continents, float moisture) {
        return new DrainageModel(SEED, SEA, () -> new Fields(terrain, continents, (x, z) -> moisture));
    }

    @Test
    void waterFollowsTheHighValleyAndDischargeIncludesMoreThanNineSteps() {
        var model = model(VALLEY, LAND, 0);
        assertTrue(model.discharge(4, 24) > 80, "Long upstream valley did not contribute");
        assertEquals(MAX_FLOW, model.discharge(4, 160));
        Tile tile = model.tile(0, 0);
        int last = 1000;
        for (int z = 8; z <= 120; z += 8) {
            int top = Integer.MIN_VALUE;
            for (int x = 58; x <= 70; x++) {
                int profile = tile.waterAt(x, z);
                if (profile != DRY) {
                    assertTrue(floor(profile) < waterTop(profile));
                    top = Math.max(top, waterTop(profile));
                }
            }
            assertTrue(top > 90, "No high channel at z=" + z);
            assertTrue(top <= last + 1, "Uphill stage at z=" + z);
            last = top;
            assertEquals(DRY, tile.waterAt(34, z));
        }
    }

    @Test
    void aNaturalDepressionHasAFlatWaterSurfaceAboveSeaLevel() {
        Field bowl = (x, z) -> 83 + (float) Math.hypot(0.22 * (x - 64), 0.34 * (z - 64));
        Tile lake = model(bowl, LAND, 0.5f).tile(0, 0);
        int profile = lake.waterAt(64, 64);
        assertTrue(isLake(profile));
        assertTrue(waterTop(profile) > SEA + 10);
        assertEquals(waterTop(profile), waterTop(lake.waterAt(67, 65)));
        assertTrue(floor(profile) < waterTop(profile) - 1);
        assertFalse(isLake(lake.waterAt(20, 20)));
        Tile valley = model(VALLEY, LAND, 0.5f).tile(0, 0);
        assertFalse(isLake(valley.waterAt(64, 64)));
    }

    @Test
    void wetBasinSpillsIntoTheLowerCatchmentWhileTheClosedBasinRetainsItsWater() {
        Field bowl = (x, z) -> 83 + (float) Math.hypot(0.18 * (x - 64), 0.32 * (z - 64));
        Field saddle = (x, z) -> Math.min(bowl.at(x, z), 110 - 0.25f * x + 0.4f * Math.abs(z - 64));
        var network = model(saddle, LAND, 0.5f);
        Tile tile = network.tile(0, 0);
        int lake = tile.waterAt(64, 64);
        assertTrue(isLake(lake) && hasSpillway(lake));
        assertTrue(waterTop(lake) > SEA);
        assertEquals(MAX_FLOW, network.discharge(8, 4), "Spill discharge must continue beyond the outlet");
        // River bends are now allowed: verify an actual connected four-neighbor
        // outlet path rather than assuming the stream follows the exact z=64 line.
        final int side = TILE_SIZE;
        boolean[] seen = new boolean[side * side];
        int[] queue = new int[side * side];
        int read = 0, count = 1;
        queue[0] = 64 * side + 64;
        seen[queue[0]] = true;
        boolean reachedLowerOutlet = false;
        while (read < count) {
            int cell = queue[read++], x = cell % side, z = cell / side;
            int current = tile.waterAt(x, z);
            if (current == DRY) continue;
            if (x >= 120 && waterTop(current) < waterTop(lake)) {
                reachedLowerOutlet = true;
                break;
            }
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + dir[0], nz = z + dir[1];
                if (nx < 0 || nx >= side || nz < 0 || nz >= side) continue;
                int index = nz * side + nx;
                if (seen[index]) continue;
                int next = tile.waterAt(nx, nz);
                if (next == DRY || waterTop(next) > waterTop(current) + 1) continue;
                seen[index] = true;
                queue[count++] = index;
            }
        }
        assertTrue(reachedLowerOutlet, "Lake has no continuous spillway to the lower catchment");
        assertFalse(hasSpillway(model(bowl, LAND, -0.5f).tile(0, 0).waterAt(64, 64)));
    }

    @Test
    void neighbouringTilesAgreeOnBothWaterAndTerrainInParallel() throws Exception {
        Field valley = (x, z) -> 92 - 0.06f * z + 0.13f * Math.abs(x + 8);
        var model = model(valley, LAND, 0);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var a = workers.submit(() -> model.tile(-1, -1));
            var b = workers.submit(() -> model.tile(0, -1));
            var c = workers.submit(() -> model.tile(-1, 0));
            Tile left = a.get(15, TimeUnit.SECONDS);
            Tile right = b.get(15, TimeUnit.SECONDS);
            Tile below = c.get(15, TimeUnit.SECONDS);
            Tile rebuilt = model(valley, LAND, 0).tile(-1, -1);
            for (int i = -TILE_SIZE; i <= 0; i++) {
                assertEquals(left.cutAt(0, i), right.cutAt(0, i), 0);
                assertEquals(left.cutAt(i, 0), below.cutAt(i, 0), 0);
                assertEquals(left.waterAt(0, i), right.waterAt(0, i));
                assertEquals(left.waterAt(i, 0), below.waterAt(i, 0));
                assertEquals(left.waterAt(-8, i), rebuilt.waterAt(-8, i));
                assertEquals(left.cutAt(-8, i), rebuilt.cutAt(-8, i), 0);
            }
        }
    }

    @Test
    void cutsAreBoundedAndAnOpenOceanUsesItsNativeWater() {
        Tile land = model(VALLEY, LAND, 0).tile(0, 0);
        Tile ocean = model(VALLEY, (x, z) -> -0.4f, 0).tile(0, 0);
        for (int x = 0; x < TILE_SIZE; x += 4) {
            for (int z = 0; z < TILE_SIZE; z += 4) {
                assertTrue(land.cutAt(x, z) >= -MAX_BANK_RAISE && land.cutAt(x, z) <= MAX_CUT);
                assertEquals(0, ocean.cutAt(x, z), 0);
                assertEquals(DRY, ocean.waterAt(x, z));
            }
        }
    }
}
