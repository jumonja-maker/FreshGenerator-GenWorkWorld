package dev.freshgenerator;

import dev.freshgenerator.core.ColumnFloatCache;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnFloatCacheGrowthTest {
    private static int slots(ColumnFloatCache cache)throws Exception {
        var f=ColumnFloatCache.class.getDeclaredField("keys");f.setAccessible(true);
        var keys=(long[])f.get(cache);return keys==null?0:keys.length;
    }
    @Test void sparseTasksDoNotAllocateTheFullCapacity()throws Exception {
        var c=new ColumnFloatCache(1024);assertEquals(0,slots(c));
        for(int i=0;i<8;i++)c.put(i,i);assertEquals(32,slots(c));
    }
    @Test void maximumSupportedCapacityStillStartsSmall()throws Exception {
        var c=new ColumnFloatCache(1<<20);c.put(0,0);assertEquals(32,slots(c));
    }
    @Test void smallCapacityNeverGetsOversizedInitialTable()throws Exception {
        var c=new ColumnFloatCache(4);c.put(0,0);assertEquals(8,slots(c));
    }
    @Test void growthPreservesEveryPreviouslyCachedBit()throws Exception {
        var c=new ColumnFloatCache(1024);
        for(int i=0;i<1024;i++){
            float v=i==0?-0f:i%2==0?Float.MIN_VALUE*i:-64-i;c.put(i,v);
            assertTrue(slots(c)<=2048);assertTrue((i+1)*2<=slots(c));
            for(int j=0;j<=i;j++){
                float expected=j==0?-0f:j%2==0?Float.MIN_VALUE*j:-64-j;
                assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(c.get(j)));
            }
        }
        assertEquals(2048,slots(c));
    }
    @Test void anUpdateAtGrowthThresholdDoesNotGrow()throws Exception {
        var c=new ColumnFloatCache(1024);for(int i=0;i<16;i++)c.put(i,i);
        c.put(7,-12);assertEquals(32,slots(c));assertEquals(-12,c.get(7));
        c.put(16,16);assertEquals(64,slots(c));
    }
    @Test void growingDoesNotReinsertStaleGenerationEntries()throws Exception {
        var c=new ColumnFloatCache(1024);c.put(55555,55);
        var e=ColumnFloatCache.class.getDeclaredField("epoch");e.setAccessible(true);e.setInt(c,2);
        var n=ColumnFloatCache.class.getDeclaredField("size");n.setAccessible(true);n.setInt(c,0);
        for(int i=0;i<17;i++)c.put(i,i);assertEquals(64,slots(c));assertTrue(Float.isNaN(c.get(55555)));
        for(int i=0;i<17;i++)assertEquals(i,c.get(i));
    }
    @Test void fullLogicalRolloverDoesNotGrowBeyondTheBound()throws Exception {
        var c=new ColumnFloatCache(1024);for(int i=0;i<1024;i++)c.put(i,i);
        c.put(1024,1024);assertEquals(2048,slots(c));assertTrue(Float.isNaN(c.get(0)));
        assertEquals(1024,c.get(1024));
    }
}
