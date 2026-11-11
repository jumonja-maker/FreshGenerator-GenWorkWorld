package dev.freshgenerator;

import dev.freshgenerator.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class NaturalTerrainTest {
    private static final String PREFIX = "freshgenerator:test_v2/";
    private static NaturalTerrainProgram program() { return NaturalTerrainProgram.compile(PREFIX, TerrainProfile.HIGHLANDS); }
    private static float delta(float c, float e, float p) { return program().heightDelta(c, e, p, 0, .7f); }
    @Test void smoothTransitionHasCorrectEndpointsAndRange() {
        var f = NaturalTerrainProgram.smootherstep(0, 1, DensityExpr.ref("test:x"));
        float previous = 0;
        for (int i = -100; i <= 1100; i++) {
            float x = i / 1000f;
            float v = f.evaluate(id -> x);
            assertTrue(v >= 0 && v <= 1); assertTrue(v + 2e-6f >= previous); previous = v;
        }
        assertEquals(0, f.evaluate(id -> -1)); assertEquals(1, f.evaluate(id -> 2));
    }
    @Test void transitionHasFlatFirstAndSecondDerivativesAtEnds() {
        var f = NaturalTerrainProgram.smootherstep(0, 1, DensityExpr.ref("test:x"));
        double h = .005;
        for (double edge : new double[]{0, 1}) {
            double a=f.evaluate(id->edge-h), b=f.evaluate(id->edge), c=f.evaluate(id->edge+h);
            assertTrue(Math.abs((c-a)/(2*h)) < .002);
            assertTrue(Math.abs((c-2*b+a)/(h*h)) < .1);
        }
    }
    @Test void invalidVersionsAndIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NaturalTerrainProgram.compile("freshgenerator:test_v1/", TerrainProfile.BALANCED));
        assertThrows(IllegalArgumentException.class, () -> NaturalTerrainProgram.smootherstep(1,1,DensityExpr.constant(0)));
        assertThrows(IllegalArgumentException.class, () -> NaturalTerrainProgram.smootherstep(Double.NaN,1,DensityExpr.constant(0)));
    }
    @Test void coastsAreNotRaisedOrDeepened() {
        for (float c : new float[]{-.18f,-.15f,-.11f}) assertEquals(0, delta(c,-1,1), 1e-5);
    }
    @Test void mushroomRegionIsProtected() {
        for (float c : new float[]{-2,-1.2f,-1.05f}) assertEquals(0,delta(c,-1,1),1e-5);
    }
    @Test void riverCorridorDoesNotGetAnUpliftDam() {
        for (float p : new float[]{-1.2f,-1,-.95f,-.92f}) assertEquals(0,delta(.8f,-1,p),1e-5);
    }
    @Test void plainsDoNotBecomeMountains() {
        assertEquals(0,delta(.8f,.1f,1),1e-5);
        assertEquals(0,delta(.8f,1,1),1e-5);
    }
    @Test void foothillsShouldersAndCrestsAreDistinct() {
        float foot=delta(.8f,-1,-.4f), shoulder=delta(.8f,-1,.4f), summit=delta(.8f,-1,1);
        assertTrue(foot>0); assertTrue(shoulder>foot); assertTrue(summit>shoulder+10);
        assertTrue(delta(.8f,-1,.99f)<summit); // no broad saturated flat summit
    }
    @Test void boundedGulliesDoNotChangeRiversOrSummits() {
        var p=program();
        assertEquals(p.heightDelta(.8f,-1,1,0,0),p.heightDelta(.8f,-1,1,0,.8f),1e-5);
        assertTrue(p.heightDelta(.8f,-1,.4f,0,0)<p.heightDelta(.8f,-1,.4f,0,.8f));
        assertEquals(0,p.heightDelta(.8f,-1,-1,0,0),1e-5);
    }
    @Test void seededMassifInputChangesHeightWithoutChangingSign() {
        var p=program();
        assertTrue(p.heightDelta(.8f,-1,1,1,.8f)>p.heightDelta(.8f,-1,1,-1,.8f));
        assertTrue(p.heightDelta(.8f,-1,1,-1,.8f)>40);
    }
    @Test void finiteAndBoundedAcrossExtremeClimateValues() {
        var p=program();var r=new Random(417);
        for(int i=0;i<15000;i++) {
            float v=p.heightDelta(r.nextFloat()*4-2,r.nextFloat()*4-2,r.nextFloat()*4-2,r.nextFloat()*4-2,r.nextFloat()*4-2);
            assertTrue(Float.isFinite(v));assertTrue(v>=-24.001 && v<=64.001,"Out of range "+v);
        }
    }
    @Test void orderAndParallelSamplingCannotChangeOutput() {
        var p=program();float[] expected=new float[1024],actual=new float[1024];
        for(int i=0;i<1024;i++)expected[i]=p.heightDelta(.4f,i/512f-1,i/700f-.6f,.2f,.1f);
        IntStream.range(0,1024).parallel().forEach(i->actual[i]=p.heightDelta(.4f,i/512f-1,i/700f-.6f,.2f,.1f));
        assertArrayEquals(expected,actual);
    }
    @Test void graphCannotBeMutatedAndBadInputsAreNotSilentlyAccepted() {
        var p=program();assertThrows(UnsupportedOperationException.class,()->p.nodes().clear());
        assertThrows(IllegalArgumentException.class,()->p.heightDelta(Float.NaN,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->p.sample("missing",id->0));
    }
}
