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
        int[] profiles = {source.applyAsInt(x - 1, z), source.applyAsInt(x + 1, z), source.applyAsInt(x, z - 1), source.applyAsInt(x, z + 1)};
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
                (x, z) -> x == -1 && z == 0 ? profile(99, 102) : DrainageModel.DRY, SEA);
        assertFalse(aquifer.protects(0, 96, 0));
        assertFalse(aquifer.protects(0, 99, 0));
        assertTrue(aquifer.protects(0, 100, 0));
        assertTrue(aquifer.protects(0, 102, 0));
        assertTrue(aquifer.protects(-1, 96, 0));
    }

    @Test
    void aSeaLevelChannelDoesNotFillAnUnmodifiedUnderwaterPassageWithSoil() {
        var aquifer = new HydrologyAquifer(NATIVE,
                (x, z) -> x == 1 && z == 0 ? profile(55, 62) : DrainageModel.DRY, (x, z) -> 0, SEA);
        for (int y = 48; y <= 64; y++) assertFalse(aquifer.protects(0, y, 0));
        assertTrue(aquifer.protects(1, 53, 0), "The channel's own bed remains sealed");
        assertTrue(aquifer.protects(1, 60, 0));
    }

    @Test
    void aModeledLowLakeApronKeepsItsWaterlineClosedWithoutBlockingTheCaveBelow() {
        var aquifer = new HydrologyAquifer(NATIVE,
                (x,z) -> x == 1 && z == 0 ? profile(60,62) : DrainageModel.DRY,
                (x,z) -> x == 0 && z == 0 ? 1 : 0, SEA);
        assertFalse(aquifer.protects(0,60,0));
        assertTrue(aquifer.protects(0,61,0));
        assertTrue(aquifer.protects(0,62,0));
        assertFalse(aquifer.protects(0,63,0));
        assertFalse(aquifer.protects(2,61,0), "Unaltered native water passage must remain open");
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
