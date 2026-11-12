package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyAquiferTest {
    private static final int SEA = 63;
    private static final Aquifer NATIVE = new Aquifer() {
        @Override public BlockState computeSubstance(int x, int y, int z, double density) { return null; }
        @Override public boolean shouldScheduleFluidUpdate() { return true; }
    };

    private static int profile(int floor, int top) {
        return (top << 16) | ((floor & 0x3fff) << 2);
    }

    private static boolean uncachedProtection(IntBinaryOperator source, int x, int y, int z) {
        if (y < SEA - 23) return false;
        int own = source.applyAsInt(x, z);
        if (own != DrainageModel.DRY && y >= DrainageModel.floor(own) - 4 && y <= DrainageModel.waterTop(own) + 1) return true;
        int[] profiles = {source.applyAsInt(x - 1, z),
                source.applyAsInt(x + 1, z), source.applyAsInt(x, z - 1), source.applyAsInt(x, z + 1)};
        for (int p : profiles) {
            if (p != DrainageModel.DRY && y > DrainageModel.floor(p) && y <= DrainageModel.waterTop(p)) return true;
        }
        return false;
    }

    @Test
    void cachedProtectionExactlyMatchesFiveIndependentIntervals() {
        IntBinaryOperator source = (x, z) -> {
            int n = Math.floorMod(x * 31 + z * 17, 11);
            return n == 0 ? DrainageModel.DRY : profile(45 + 15 * n, 48 + 15 * n);
        };
        var aquifer = new HydrologyAquifer(NATIVE, source, SEA);
        for (int z = -18; z <= 18; z++) {
            for (int x = -18; x <= 18; x++) {
                for (int y = -64; y <= 320; y++) {
                    assertEquals(uncachedProtection(source, x, y, z), aquifer.protects(x, y, z));
                }
            }
        }
    }

    @Test
    void aDryBankProtectsItsWaterlineAndRetainsTheNativeCaveBelow() {
        var aquifer = new HydrologyAquifer(NATIVE,
                (x, z) -> x == -1 && z == 0 ? profile(59, 62) : DrainageModel.DRY, SEA);
        assertFalse(aquifer.protects(0, 56, 0));
        assertFalse(aquifer.protects(0, 59, 0));
        assertTrue(aquifer.protects(0, 60, 0));
        assertTrue(aquifer.protects(0, 62, 0));
        assertTrue(aquifer.protects(-1, 56, 0));
    }

    @Test
    void aVerticalScanLoadsOnlyFiveProfilesAndRetainsGapsBetweenLevels() {
        var calls = new AtomicInteger();
        var aquifer = new HydrologyAquifer(NATIVE, (x, z) -> {
            calls.incrementAndGet();
            if (z != 0) return DrainageModel.DRY;
            return x == 0 ? profile(65, 70) : x == -1 ? profile(105, 110) : DrainageModel.DRY;
        }, SEA);
        for (int y = -64; y < SEA - 23; y++) assertFalse(aquifer.protects(0, y, 0));
        assertEquals(0, calls.get());
        for (int y = SEA - 23; y <= 320; y++) aquifer.protects(0, y, 0);
        assertEquals(5, calls.get());
        assertTrue(aquifer.protects(0, 67, 0));
        assertFalse(aquifer.protects(0, 90, 0));
        assertTrue(aquifer.protects(0, 107, 0));
        assertEquals(5, calls.get());
        aquifer.protects(1, 67, 0);
        assertEquals(10, calls.get());
    }

    @Test
    void nativeFluidCallsDoNotOverwriteTheProtectionCache() {
        var calls = new AtomicInteger();
        IntBinaryOperator source = (x, z) -> {
            calls.incrementAndGet();
            return x == 0 && z == 0 ? profile(65, 70) : DrainageModel.DRY;
        };
        var aquifer = new HydrologyAquifer(NATIVE, source, SEA);
        assertTrue(aquifer.protects(0, 67, 0));
        assertEquals(5, calls.get());
        assertNull(aquifer.computeSubstance(1000, 65, 1000, -1));
        assertTrue(aquifer.shouldScheduleFluidUpdate());
        assertEquals(6, calls.get());
        assertTrue(aquifer.protects(0, 67, 0));
        assertEquals(6, calls.get());
    }

    @Test
    void failedProfileLookupIsNotPublishedAsAValidCacheEntry() {
        var fail = new AtomicInteger(1);
        var aquifer = new HydrologyAquifer(NATIVE, (x, z) -> {
            if (x == 1 && z == 0 && fail.getAndSet(0) == 1) throw new IllegalStateException("sampling failed");
            return x == 1 && z == 0 ? profile(65, 70) : DrainageModel.DRY;
        }, SEA);
        assertThrows(IllegalStateException.class, () -> aquifer.protects(0, 67, 0));
        assertTrue(aquifer.protects(0, 67, 0));
        assertFalse(aquifer.protects(0, 90, 0));
    }
}
