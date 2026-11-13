package dev.freshgenerator.audit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlpineExcavationContractTest {
    @Test void nativeExcavatedSampleMatchesTheExactDeclaredCap() {
        // Reproduced on Minecraft 26.3, seed 2037157180504249802, (-6656,196,-7056).
        double old=0.3369164764881134, relief=0.00032363366335630417, cut=2.1959648;
        assertDoesNotThrow(()->CoherentEnvelopeVerifier.requireAlpineCorrection(old,old,relief,cut));
        assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(old,old+relief,relief,cut));
    }
    @Test void boundsAreExactAndDoNotWaiveDryOrRaisedColumns() {
        for(double cut:new double[]{-2.25,0,Math.nextDown(0.25),19,20}) {
            assertEquals(.08,CoherentEnvelopeVerifier.expectedAlpineCorrection(.08,cut));
            assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(.2,.2,.08,cut));
        }
        for(double cut:new double[]{.25,1,18,Math.nextDown(19.)})
            assertEquals(0,CoherentEnvelopeVerifier.expectedAlpineCorrection(.08,cut));
    }
    @Test void negativeDetailAndWrongGeometryAreStillChecked() {
        assertDoesNotThrow(()->CoherentEnvelopeVerifier.requireAlpineCorrection(.2,.12,-.08,3));
        assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(.2,.2,-.08,3));
        assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(.2,.1,.08,3));
        assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(.2,.2,.2,3));
    }
    @Test void nonfiniteInputsNeverPass() {
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.expectedAlpineCorrection(invalid,0));
            assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.expectedAlpineCorrection(0,invalid));
            assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(invalid,0,0,0));
            assertThrows(AssertionError.class,()->CoherentEnvelopeVerifier.requireAlpineCorrection(0,invalid,0,0));
        }
    }
}
