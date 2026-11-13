package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;
import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Flow is added at its junction, not linearly borrowed along the incoming reach. */
class DrainageConfluenceTest {
    private static Tile reach(int upstreamQ, int downstreamQ, float top, float endTop) {
        var raster = new Raster(-1, -1, (x, z) -> 102, 63);
        var a = new Cell(1, 10, 50, 102, 3, false, 2);
        var b = new Cell(2, 30, 50, 92, 3, false, 3);
        raster.reach(a, b, top, endTop, upstreamQ, downstreamQ);
        return raster.finish();
    }

    @Test void aSmallTributaryDoesNotBorrowTheReceiversDischargeOrDepth() {
        var reference = reach(MIN_FLOW, MIN_FLOW, 100, 100);
        var junction = reach(MIN_FLOW, MAX_FLOW, 100, 100);
        int wet = 0;
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            assertEquals(reference.waterAt(x, z), junction.waterAt(x, z), "Premature tributary widening/deepening");
            assertEquals(reference.cutAt(x, z), junction.cutAt(x, z), 0);
            if (junction.waterAt(x, z) != DRY) wet++;
        }
        assertTrue(wet > 0);
        assertEquals(DRY, junction.waterAt(20, 52), "Two blocks sideways is outside this small tributary");
    }

    @Test void theReceiverRetainsItsCombinedWidthOnItsOwnOutgoingReach() {
        var trunk = reach(MAX_FLOW, MAX_FLOW, 100, 100);
        var tributary = reach(MIN_FLOW, MAX_FLOW, 100, 100);
        assertNotEquals(DRY, trunk.waterAt(20, 54));
        assertEquals(DRY, tributary.waterAt(20, 54));
    }

    @Test void anUnestablishedBranchCannotAppearJustBecauseItMeetsALargeRiver() {
        var tile = reach(MIN_FLOW - 1, MAX_FLOW, 100, 100);
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            assertEquals(DRY, tile.waterAt(x, z));
            assertEquals(0, tile.cutAt(x, z), 0);
        }
    }

    @Test void stageMatchesTheExpandedReceivingBankBeforeTheAbstractNode() {
        var tile = reach(MIN_FLOW, MAX_FLOW, 100, 90);
        assertEquals(90, waterTop(tile.waterAt(28, 50)), "Avoid a high-water cap overlapping the lower trunk");
        assertEquals(90, waterTop(tile.waterAt(30, 50)));
        int previous = Integer.MAX_VALUE;
        for (int x = 10; x <= 30; x++) {
            int p = tile.waterAt(x, 50);
            assertNotEquals(DRY, p);
            assertTrue(waterTop(p) <= previous);
            assertTrue(floor(p) < waterTop(p));
            assertTrue(tile.cutAt(x, 50) <= MAX_CUT);
            previous = waterTop(p);
        }
    }

    @Test void equalWidthLinksDoNotIntroduceAnArtificialPlateauAtEveryNode() {
        var tile = reach(300, 300, 100, 90);
        assertEquals(91, waterTop(tile.waterAt(28, 50)));
        assertEquals(95, waterTop(tile.waterAt(20, 50)));
        assertEquals(90, waterTop(tile.waterAt(30, 50)));
    }
}
