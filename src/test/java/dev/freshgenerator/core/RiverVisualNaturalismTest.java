package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Visual-model guards: sparse headwater activation, width scaling and shorelines. */
class RiverVisualNaturalismTest {
    private static final int SEA = 63;

    private static int widthAt(DrainageModel.Raster raster, int x, int centerZ) {
        var result = raster.finish();
        int wet = 0;
        for (int z = centerZ - 15; z <= centerZ + 15; z++) {
            if (result.waterAt(x, z) != DrainageModel.DRY) wet++;
        }
        return wet;
    }

    @Test
    void newRiversRequireEstablishedCatchments() {
        assertTrue(DrainageModel.MIN_FLOW >= 48,
                "A few damp grid cells must not create a wide permanent river");
        assertTrue(DrainageModel.MAX_FLOW > 4 * DrainageModel.MIN_FLOW);
    }

    @Test
    void smallRiversAreNarrowerThanLargeLowlandChannels() {
        var small = new DrainageModel.Raster(-1, -1, (x, z) -> 95, SEA, 42);
        var large = new DrainageModel.Raster(-1, -1, (x, z) -> 95, SEA, 42);
        var a = new DrainageModel.Cell(0, 24, 60, 97, 4, false, 0);
        var b = new DrainageModel.Cell(1, 88, 60, 97, 4, false, 0);
        small.reach(a, b, 94, 94, DrainageModel.MIN_FLOW, DrainageModel.MIN_FLOW);
        large.reach(a, b, 94, 94, DrainageModel.MAX_FLOW, DrainageModel.MAX_FLOW);
        int headwater = widthAt(small, 56, 60);
        int major = widthAt(large, 56, 60);
        assertTrue(headwater >= 3 && headwater <= 9, "Overwide minor river: " + headwater);
        assertTrue(major >= headwater + 2, "Large rivers must be visibly wider: " + major);
    }

    @Test
    void steepReachesFormNarrowCascadesInsteadOfWideWaterCurtains() {
        var flat = new DrainageModel.Raster(-1, -1, (x, z) -> 95, SEA, 42);
        var steep = new DrainageModel.Raster(-1, -1, (x, z) -> 95, SEA, 42);
        var a = new DrainageModel.Cell(0, 24, 60, 97, 4, false, 0);
        var b = new DrainageModel.Cell(1, 88, 60, 97, 4, false, 0);
        flat.reach(a, b, 94, 94, DrainageModel.MAX_FLOW, DrainageModel.MAX_FLOW);
        steep.reach(a, b, 99, 87, DrainageModel.MAX_FLOW, DrainageModel.MAX_FLOW);
        int ordinary = widthAt(flat, 56, 60);
        int cascade = widthAt(steep, 56, 60);
        assertTrue(cascade <= ordinary - 2, "Steep channel remained a wide water curtain");
    }

    @Test
    void submergedOceanShoreCannotRaiseAnArtificialDryBerm() {
        var raster = new DrainageModel.Raster(-1, -1, (x, z) -> 61, SEA, 42);
        raster.lakeColumn(30, 30, 62, false);
        var result = raster.finish();
        assertNotEquals(DrainageModel.DRY, result.waterAt(30, 30));
        assertEquals(0, result.cutAt(31, 30), 0,
                "Native sea water already covers the adjacent dry profile");
    }
}
