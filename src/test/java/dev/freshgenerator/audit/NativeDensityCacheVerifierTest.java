package dev.freshgenerator.audit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDensityCacheVerifierTest {
    @Test void acceptsOnlyIdenticalFiniteBits() {
        for (float value : new float[]{-1, -Float.MIN_VALUE, -0.0f, 0.0f, Float.MIN_VALUE, 1})
            assertDoesNotThrow(() -> NativeDensityCacheVerifier.requireSameBits(value, value, "control"));
    }
    @Test void rejectsEvenOneUlpOrChangedZeroSign() {
        for (float value : new float[]{-0.1f, 0.1f, 1})
            assertThrows(AssertionError.class, () -> NativeDensityCacheVerifier.requireSameBits(value, Math.nextUp(value), "mutation"));
        assertThrows(AssertionError.class, () -> NativeDensityCacheVerifier.requireSameBits(-0.0f, 0.0f, "zero"));
    }
    @Test void rejectsMatchingNonfiniteValues() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(AssertionError.class, () -> NativeDensityCacheVerifier.requireSameBits(value, value, "nonfinite"));
    }
}
