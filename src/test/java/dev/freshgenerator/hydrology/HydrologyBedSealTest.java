package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyBedSealTest {
    private static final int SEA = 63;
    private static int profile(int floor, int top) {
        return (top << 16) | ((floor & 0x3fff) << 2);
    }

    @Test
    void alpineReliefCannotLeaveAirAtTheExactModeledBed() {
        var nativeCalls = new AtomicInteger();
        Aquifer tracking = new Aquifer() {
            @Override public BlockState computeSubstance(int x, int y, int z, double density) {
                nativeCalls.incrementAndGet(); return null;
            }
            @Override public boolean shouldScheduleFluidUpdate() { return true; }
        };
        var aquifer = new HydrologyAquifer(tracking, (x, z) -> profile(159, 164), SEA);
        // Native seed 801086821803359396, (-6670,159,1058), before decoration.
        assertNull(aquifer.computeSubstance(-6670, 159, 1058, -0.0012462041));
        assertEquals(0, nativeCalls.get(), "Null must select default terrain, not the native air aquifer");
        assertFalse(aquifer.shouldScheduleFluidUpdate());
        assertNull(aquifer.computeSubstance(-6670, 158, 1058, -1));
        assertEquals(1, nativeCalls.get(), "No change below the single modeled bed voxel");
        assertTrue(aquifer.shouldScheduleFluidUpdate());
    }

    @Test
    void floorSealLeavesDryColumnsSolidDensitiesAndAboveWaterNative() {
        var nativeCalls = new AtomicInteger();
        Aquifer tracking = new Aquifer() {
            @Override public BlockState computeSubstance(int x, int y, int z, double density) {
                nativeCalls.incrementAndGet(); return null;
            }
            @Override public boolean shouldScheduleFluidUpdate() { return true; }
        };
        var aquifer = new HydrologyAquifer(tracking,
                (x, z) -> x == 0 ? profile(159, 164) : DrainageModel.DRY, SEA);
        assertNull(aquifer.computeSubstance(0, 159, 0, 0.01));
        assertNull(aquifer.computeSubstance(1, 159, 0, -1));
        assertNull(aquifer.computeSubstance(0, 165, 0, -1));
        assertNull(aquifer.computeSubstance(0, 40, 0, -1));
        assertEquals(4, nativeCalls.get());
        assertTrue(aquifer.shouldScheduleFluidUpdate());
        assertNull(aquifer.computeSubstance(0, 159, 0, 0));
        assertEquals(4, nativeCalls.get());
        assertFalse(aquifer.shouldScheduleFluidUpdate());
    }
}
