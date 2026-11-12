package dev.freshgenerator;

import dev.freshgenerator.core.DrainageModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrainageModelTest {
    private static final int SEA = 63;
    private static final long SEED = 42;
    private static final DrainageModel.Field LAND = (x, z) -> 0.2f;
    private static final DrainageModel.Field VALLEY = (x, z) -> 83 - 0.11f * z + 0.18f * Math.abs(x - 64);

    @Test
    void runoffConvergesIntoTheDownhillValley() {
        var tile = DrainageModel.build(0, 0, SEED, SEA, VALLEY, LAND);
        for (int z : new int[]{16, 40, 64, 88, 112}) {
            float channel = 0;
            for (int x = 59; x <= 69; x++) channel = Math.max(channel, tile.cutAt(x, z));
            assertTrue(channel > 1.5f, "No downstream channel at z=" + z);
            assertTrue(tile.cutAt(36, z) < channel, "Bank was cut as much as the valley at z=" + z);
        }
    }

    @Test
    void aClosedBasinCanHoldALakeAndAnOpenValleyDoesNotReceiveTheSameBasinCut() {
        DrainageModel.Field bowl = (x, z) -> 72 + 0.18f * (float) Math.hypot(x - 64, z - 64);
        DrainageModel.Field open = (x, z) -> 72 + 0.18f * Math.abs(x - 64) + 0.08f * (z - 64);
        var lake = DrainageModel.build(0, 0, SEED, SEA, bowl, LAND);
        var outlet = DrainageModel.build(0, 0, SEED, SEA, open, LAND);
        assertTrue(bowl.at(64, 64) - lake.cutAt(64, 64) < SEA - 1);
        assertTrue(lake.cutAt(64, 64) > outlet.cutAt(64, 64) + 2);
        assertTrue(bowl.at(100, 64) - lake.cutAt(100, 64) >= SEA);
    }

    @Test
    void neighbouringTilesAgreeExactlyOnTheirSharedEdgesAtNegativeCoordinates() {
        DrainageModel.Field valley = (x, z) -> 74 - 0.06f * z + 0.13f * Math.abs(x + 8);
        var left = DrainageModel.build(-1, -1, SEED, SEA, valley, LAND);
        var right = DrainageModel.build(0, -1, SEED, SEA, valley, LAND);
        var below = DrainageModel.build(-1, 0, SEED, SEA, valley, LAND);
        for (int i = -DrainageModel.TILE_SIZE; i <= 0; i++) {
            assertEquals(left.cutAt(0, i), right.cutAt(0, i), 0);
            assertEquals(left.cutAt(i, 0), below.cutAt(i, 0), 0);
        }
    }

    @Test
    void cutsAreBoundedAndDoNotReachTheOpenOceanOrHighSummits() {
        var land = DrainageModel.build(0, 0, SEED, SEA, VALLEY, LAND);
        var ocean = DrainageModel.build(0, 0, SEED, SEA, VALLEY, (x, z) -> -0.4f);
        var high = DrainageModel.build(0, 0, SEED, SEA, (x, z) -> VALLEY.at(x, z) + 90, LAND);
        for (int x = 0; x < 128; x += 4) {
            for (int z = 0; z < 128; z += 4) {
                assertTrue(land.cutAt(x, z) >= 0 && land.cutAt(x, z) <= DrainageModel.MAX_CUT);
                assertEquals(0, ocean.cutAt(x, z), 0);
                assertEquals(0, high.cutAt(x, z), 0);
            }
        }
    }
}
