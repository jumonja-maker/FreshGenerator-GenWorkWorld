package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;
import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class DrainageBackwaterTest {
    private static final Field BOWL = (x,z) -> 83 + (float)Math.hypot(.22*(x-64), .34*(z-64));
    private static DrainageModel model(long seed, Field terrain) {
        return new DrainageModel(seed,63,()->new Fields(terrain,(x,z)->.2f,(x,z)->.5f));
    }
    @Test void lakeStageIsAnUpstreamBoundaryNotARawTerrainOffset() {
        var model=model(42,BOWL);
        var sink=model.cell(4,4);
        assertEquals(NO_CELL,sink.receiver());
        var basin=DrainageBasin.find(model,sink,model.discharge(4,4),BOWL);
        assertNotNull(basin);assertFalse(basin.footprint().isEmpty());
        assertTrue(basin.top()>model.riverLevel(sink)+2,"Fixture must exercise the old low inlet");
        assertEquals(basin.top(),model.waterLevel(sink),0);
        int backwater=0,free=0;
        for(int z=1;z<=8;z++)for(int x=1;x<=8;x++) {
            var node=model.cell(x,z);var current=node;
            for(int n=0;n<50 && current.receiver()!=NO_CELL;n++)current=model.cell(current.receiver());
            if(current.key()!=sink.key())continue;
            assertEquals(Math.max(basin.top(),model.riverLevel(node)),model.waterLevel(node),0);
            if(model.riverLevel(node)<basin.top())backwater++;else free++;
        }
        assertTrue(backwater>3 && free>3,"Need both backed-up and free-flowing reaches");
    }
    @Test void seaAndLakeFreeValleyKeepTheNaturalProfile() {
        Field valley=(x,z)->105-.055f*z+.18f*Math.abs(x-64);
        var model=model(1337,valley);
        for(int z=-8;z<45;z++){
            var node=model.cell(4,z);
            // This smooth valley may have tiny jitter minima; they do not form a lake.
            assertEquals(model.riverLevel(node),model.waterLevel(node),0);
        }
    }
    @Test void backwaterDoesNotMoveTheLakeStageOrItsSpillThreshold() {
        Field terrain=(x,z)->Math.min(BOWL.at(x,z),110-.25f*x+.4f*Math.abs(z-64));
        var model=model(42,terrain);var sink=model.cell(4,4);
        var before=DrainageBasin.find(model,sink,model.discharge(4,4),terrain);
        assertNotNull(before);assertNotEquals(NO_CELL,before.outlet());
        for(int z=-8;z<16;z++)for(int x=-8;x<16;x++)model.waterLevel(model.cell(x,z));
        var after=DrainageBasin.find(model,sink,model.discharge(4,4),terrain);
        assertEquals(before.top(),after.top());assertEquals(before.footprint(),after.footprint());
        assertArrayEquals(before.path(),after.path());
        assertTrue(model.waterLevel(model.cell(before.outlet()))<before.top(),"Do not flatten the outlet to the reservoir");
    }
    @Test void aRemoteLowBasinCannotRaiseAnAlreadyHigherUpstreamStage() {
        var samples = new java.util.concurrent.atomic.AtomicInteger();
        Field downhill = (x,z) -> { samples.incrementAndGet(); return 250 - .1f*z + .25f*Math.abs(x-64); };
        var model=model(42,downhill);
        var start=model.cell(4,0);
        assertEquals(model.riverLevel(start),model.waterLevel(start),0);
        assertTrue(samples.get()<1000,"Must not follow an irrelevant remote downstream basin to sea");
        // An early exit must not stamp its high start level into intermediate nodes.
        var lower=model.cell(4,8);
        assertEquals(model.riverLevel(lower),model.waterLevel(lower),0);
        assertTrue(model.waterLevel(lower)<model.waterLevel(start));
    }
    @Test void aMaximumLakeStillFitsTheSharedStageBound() {
        for (float h : new float[]{62.001f,63,83.999f,150.1f,303.9f}) {
            float maximum=(float)Math.floor(h)+11;
            assertEquals(maximum,DrainageBasin.maximumStage(h,63),0);
        }
        assertEquals(62,DrainageBasin.maximumStage(-20,63),0);
    }
    @Test void stageCacheAndReverseLookupDoNotChangeTheWaterSurface() {
        var a=model(1337,BOWL);var b=model(1337,BOWL);
        float[] expected=new float[256];int i=0;
        for(int z=-4;z<12;z++)for(int x=-4;x<12;x++)expected[i++]=a.waterLevel(a.cell(x,z));
        for(int z=11;z>=-4;z--)for(int x=11;x>=-4;x--)
            assertEquals(expected[(z+4)*16+x+4],b.waterLevel(b.cell(x,z)),0);
    }
}
