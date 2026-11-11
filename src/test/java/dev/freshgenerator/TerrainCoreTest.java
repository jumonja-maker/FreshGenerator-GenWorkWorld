package dev.freshgenerator;

import dev.freshgenerator.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static dev.freshgenerator.core.DensityExpr.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainCoreTest {
    private static final String PREFIX = "freshgenerator:test_v1/";
    private static TerrainProgram program(TerrainProfile p) { return TerrainProgram.compile(PREFIX, p); }
    @Test void identityHasZeroHeightChange() {
        var p = program(TerrainProfile.IDENTITY);
        for (float c=-1.2f;c<=1.2f;c+=.05f) for(float e=-1;e<=1;e+=.13f)
            assertEquals(0, p.heightDelta(c,e,.7f), 0);
    }
    @Test void interiorMountainHasRequestedUplift() { assertEquals(64, program(TerrainProfile.HIGHLANDS).heightDelta(.8f,-.9f,1), .001); }
    @Test void deepOceanHasRequestedDeepening() { assertEquals(-24, program(TerrainProfile.HIGHLANDS).heightDelta(-.8f,.8f,0), .001); }
    @Test void coastIsNotAnArtificialCliff() { assertEquals(0, program(TerrainProfile.HIGHLANDS).heightDelta(-.11f,-1,1), .001); }
    @Test void mushroomIslandContinentalnessIsNotDeepened() { assertEquals(0, program(TerrainProfile.HIGHLANDS).heightDelta(-1.1f,0,0), .001); }
    @Test void flatErodedLandIsNotRaisedIntoMountains() { assertEquals(0, program(TerrainProfile.HIGHLANDS).heightDelta(.8f,.8f,1), .001); }
    @Test void valleysAreNotRaisedIntoPeaks() { assertEquals(0, program(TerrainProfile.HIGHLANDS).heightDelta(.8f,-1,-1), .001); }
    @Test void noNaNOrOutOfBoundsInLargeClimateSample() {
        Random r=new Random(12451);var p=program(TerrainProfile.HIGHLANDS);
        for(int i=0;i<25000;i++) {float value=p.heightDelta(r.nextFloat()*4-2,r.nextFloat()*4-2,r.nextFloat()*4-2);
            assertTrue(Float.isFinite(value));assertTrue(value>=-24.001f&&value<=64.001f);}
    }
    @Test void smoothstepHasExactEndpointsAndMonotonicInterior() {
        var s=smoothstep(0,1,ref("test:x"));float last=-1;
        for(int i=-100;i<=200;i++){final float x=i/100f;float v=s.evaluate(id->x);assertTrue(v>=last-1e-6f);last=v;}
        assertEquals(0,s.evaluate(id->-1));assertEquals(1,s.evaluate(id->2));
    }
    @Test void nonfiniteParametersAreRejected() {
        for(double x:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new TerrainProfile(1,x,1,1,0,0));
        assertThrows(IllegalArgumentException.class,()->constant(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->ref("test:x").evaluate(id->Double.NaN));
    }
    @Test void unsupportedVersionsAndExtremeVerticalSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new TerrainProfile(2,1,1,1,0,0));
        assertThrows(IllegalArgumentException.class,()->new TerrainProfile(1,1,1,1,1024,0));
        assertThrows(IllegalArgumentException.class,()->new TerrainProfile(1,1,1,1,0,128));
        assertThrows(IllegalArgumentException.class,()->new TerrainProfile(1,0,1,1,0,0));
    }
    @Test void invalidIdsCannotEscapeResourceNamespace() {
        assertThrows(IllegalArgumentException.class,()->TerrainProgram.compile("minecraft:",TerrainProfile.BALANCED));
        assertThrows(IllegalArgumentException.class,()->ref("bad identifier"));
        assertThrows(IllegalArgumentException.class,()->smoothstep(1,1,constant(0)));
    }
    @Test void publishedProgramIsImmutable() { assertThrows(UnsupportedOperationException.class,()->program(TerrainProfile.BALANCED).nodes().clear()); }
    @Test void parallelEvaluationMatchesSerialBitForBit() throws Exception {
        var p=program(TerrainProfile.BALANCED);List<Callable<Float>> tasks=new ArrayList<>();
        for(int i=0;i<1000;i++){final float c=(i%113-56)/56f,e=(i%53-26)/26f,w=(i%31-15)/15f;tasks.add(()->p.heightDelta(c,e,w));}
        try(var executor=Executors.newFixedThreadPool(4)){var results=executor.invokeAll(tasks);
            for(int i=0;i<tasks.size();i++)assertEquals(Float.floatToIntBits(tasks.get(i).call()),Float.floatToIntBits(results.get(i).get()));}
    }
    @Test void profileHasOnlyGeographicalParameters() {
        assertEquals(Set.of("formatVersion","continentScale","biomeScale","terrainScale","mountainUplift","oceanDeepening"),
                new HashSet<>(Arrays.stream(TerrainProfile.class.getRecordComponents()).map(x->x.getName()).toList()));
    }
}
