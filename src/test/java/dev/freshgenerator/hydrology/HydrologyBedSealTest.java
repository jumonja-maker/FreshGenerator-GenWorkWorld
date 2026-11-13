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
    void alpineReliefCannotLeaveAirWithinTheShallowModeledBed() {
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
        assertNull(aquifer.computeSubstance(-6670, 154, 1058, -1));
        assertEquals(1, nativeCalls.get(), "No change below the existing four-block bed seal");
        assertTrue(aquifer.shouldScheduleFluidUpdate());
    }

    @Test
    void sealedBedCannotHangAboveASecondNegativeAlpineSample() {
        var calls = new AtomicInteger();
        Aquifer nativeAquifer = new Aquifer() {
            @Override public BlockState computeSubstance(int x, int y, int z, double density) {
                calls.incrementAndGet(); return null;
            }
            @Override public boolean shouldScheduleFluidUpdate() { return true; }
        };
        var aquifer = new HydrologyAquifer(nativeAquifer, (x, z) -> profile(194, 198), SEA);
        // Native seed 801086821803359396: the previous exact-floor repair put
        // stone at (10721,194,12272) but left air at Y193 (density -0.0033893809).
        for (int y = 194; y >= 194 - HydrologyAquifer.BED_SEAL_DEPTH; y--) {
            assertNull(aquifer.computeSubstance(10721, y, 12272, -0.12));
            assertEquals(0, calls.get(), "Every protected shallow floor sample must be solid");
            assertFalse(aquifer.shouldScheduleFluidUpdate());
            assertTrue(aquifer.protects(10721, y, 12272));
        }
        assertNull(aquifer.computeSubstance(10721, 189, 12272, -0.12));
        assertEquals(1, calls.get(), "The cave immediately below the seal remains native");
        assertTrue(aquifer.shouldScheduleFluidUpdate());
    }

    @Test
    void sealDepthCoversTheMaximumBoundedAlpineDisplacement() {
        double input = 0.08 * HydrologyAquifer.BED_SEAL_DEPTH;
        double squeezed = input / 2.0 - input * input * input / 24.0;
        assertTrue(squeezed > 0.12, "The seal must reach the supported height envelope");
        assertEquals(4, HydrologyAquifer.BED_SEAL_DEPTH,
                "Do not expand the existing shallow-carver footprint into deep caves");
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
