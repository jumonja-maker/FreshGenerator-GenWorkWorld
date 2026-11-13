package dev.freshgenerator.hydrology;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillageTreeGuardTest {
    @Test
    void nativeStreetFootprintProtectsBothColumnsOfWideTrunksWithoutClearingItsNeighbors() {
        var street = new BoundingBox(-30, 50, -10, -26, 52, 12);
        assertTrue(VillageTreeGuard.intersectsTrunk(street, -30, 0));
        assertTrue(VillageTreeGuard.intersectsTrunk(street, -31, 0));
        assertTrue(VillageTreeGuard.intersectsTrunk(street, -26, 0));
        assertTrue(VillageTreeGuard.intersectsTrunk(street, -31, -11));
        assertFalse(VillageTreeGuard.intersectsTrunk(street, -32, 0));
        assertFalse(VillageTreeGuard.intersectsTrunk(street, -25, 0));
        assertFalse(VillageTreeGuard.intersectsTrunk(street, -28, -12));
        assertFalse(VillageTreeGuard.intersectsTrunk(street, -28, 13));
    }
}
