package dev.freshgenerator;
import dev.freshgenerator.core.ColumnFloatCache;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ColumnFloatCacheTest {
    static float value(long key) { return Float.intBitsToFloat((int)(key^(key>>>32)) & 0x7f7fffff); }
    @Test void invalidCapacitiesAreRejected() {
        for(int n:new int[]{Integer.MIN_VALUE,-1,0,1,2,3,5,1000,(1<<20)+1})
            assertThrows(IllegalArgumentException.class,()->new ColumnFloatCache(n));
        assertEquals(4,new ColumnFloatCache(4).capacity());
        assertEquals(1024,new ColumnFloatCache(1024).capacity());
    }
    @Test void emptyCacheDoesNotInventAZeroKeyHit() {
        var c=new ColumnFloatCache(4);
        for(long k:new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE}) assertTrue(Float.isNaN(c.get(k)));
    }
    @Test void allLongKeysAreUsableIncludingCoordinateExtremes() {
        var c=new ColumnFloatCache(4);
        for(long k:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE,0xffffffffL,0xffffffff00000000L}) {
            c.put(k,value(k));assertEquals(Float.floatToRawIntBits(value(k)),Float.floatToRawIntBits(c.get(k)));
        }
    }
    @Test void signedZeroSurvivesUpdates() {
        var c=new ColumnFloatCache(4);c.put(0,-0.0f);assertEquals(0x80000000,Float.floatToRawIntBits(c.get(0)));
        c.put(0,0.0f);assertEquals(0,Float.floatToRawIntBits(c.get(0)));
    }
    @Test void invalidValuesDoNotDestroyExistingSamples() {
        var c=new ColumnFloatCache(4);c.put(17,99);
        for(float v:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->c.put(17,v));
        assertEquals(99,c.get(17));
    }
    @Test void fourWayCollisionEvictsOnlyOneEntry() {
        var c=new ColumnFloatCache(4);for(int i=0;i<4;i++)c.put(i,i+1);
        c.put(4,5);int retained=0;
        for(int i=0;i<4;i++)if(!Float.isNaN(c.get(i))){assertEquals(i+1,c.get(i));retained++;}
        assertEquals(3,retained);assertEquals(5,c.get(4));
    }
    @Test void updatingPresentKeyDoesNotEvictNeighbours() {
        var c=new ColumnFloatCache(4);for(int i=0;i<4;i++)c.put(i,i);
        for(int i=0;i<100;i++)c.put(1,i);
        assertEquals(99,c.get(1));assertEquals(0,c.get(0));assertEquals(2,c.get(2));assertEquals(3,c.get(3));
    }
    @Test void collisionsNeverReturnAnotherColumnsHeight() {
        var c=new ColumnFloatCache(64);var r=new Random(7131);long[] keys=new long[4096];
        for(int i=0;i<keys.length;i++)keys[i]=r.nextLong();
        for(int i=0;i<100000;i++){
            long k=keys[r.nextInt(keys.length)];float hit=c.get(k),expected=value(k);
            if(!Float.isNaN(hit))assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(hit));
            c.put(k,expected);assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(c.get(k)));
        }
    }
    @Test void evictionCannotChangeTheResultOfPureSampling() {
        var c=new ColumnFloatCache(1024);var r=new Random(42);
        for(int i=0;i<100000;i++){
            int x=r.nextInt(256)-128,z=r.nextInt(256)-128;long k=((long)x<<32)|(z&0xffffffffL);
            float h=c.get(k);if(Float.isNaN(h)){h=value(k);c.put(k,h);}
            assertEquals(Float.floatToRawIntBits(value(k)),Float.floatToRawIntBits(h));
        }
    }
    @Test void separateTaskCachesDoNotLeakWorldValues()throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<Callable<Boolean>>();
            for(int world=0;world<8;world++){int w=world;jobs.add(()->{
                var c=new ColumnFloatCache(64);
                for(long k=0;k<10000;k++){c.put(k,w);if(c.get(k)!=w)return false;}return true;
            });}
            for(var result:pool.invokeAll(jobs))assertTrue(result.get());
        }
    }
}
