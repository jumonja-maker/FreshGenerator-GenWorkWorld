package dev.freshgenerator;

import dev.freshgenerator.audit.ForestHeightOracle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForestHeightOracleTest {
    private static void miss(ForestHeightOracle o,int x,int z,float v) {
        o.begin(x,z);o.sampled(x,0,z,v);o.finish(v);
    }
    @Test void realMissThenHitNeedsNoSecondSample() {
        var o=new ForestHeightOracle();miss(o,12,-44,80.25f);o.begin(12,-44);o.finish(80.25f);
    }
    @Test void missingNativeSampleFails() {
        var o=new ForestHeightOracle();o.begin(1,2);assertThrows(IllegalStateException.class,()->o.finish(3));
    }
    @Test void extraNativeSampleOnHitFails() {
        var o=new ForestHeightOracle();miss(o,1,2,3);o.begin(1,2);
        assertThrows(IllegalStateException.class,()->o.sampled(1,0,2,3));
    }
    @Test void changedValueFailsBitwise() {
        var o=new ForestHeightOracle();o.begin(1,2);o.sampled(1,0,2,3);
        assertThrows(IllegalStateException.class,()->o.finish(Math.nextUp(3f)));
    }
    @Test void signedZeroIsNotCollapsed() {
        var o=new ForestHeightOracle();miss(o,0,0,-0f);o.begin(0,0);
        assertThrows(IllegalStateException.class,()->o.finish(0f));
    }
    @Test void wrongColumnFails() {
        var o=new ForestHeightOracle();o.begin(-1,3);
        assertThrows(IllegalStateException.class,()->o.sampled(1,0,3,4));
    }
    @Test void wrongHeightAxisFails() {
        var o=new ForestHeightOracle();o.begin(1,3);
        assertThrows(IllegalStateException.class,()->o.sampled(1,1,3,4));
    }
    @Test void duplicateSampleFails() {
        var o=new ForestHeightOracle();o.begin(1,3);o.sampled(1,0,3,4);
        assertThrows(IllegalStateException.class,()->o.sampled(1,0,3,4));
    }
    @Test void incompleteAndOverlappingCallsFail() {
        assertThrows(IllegalStateException.class,()->new ForestHeightOracle().finish(1));
        var o=new ForestHeightOracle();o.begin(0,0);
        assertThrows(IllegalStateException.class,()->o.begin(1,1));
    }
    @Test void evictionMatchesPublishedCapacityNotHashCollision() {
        var o=new ForestHeightOracle();
        for(int i=0;i<1024;i++)miss(o,i,-i,i);
        o.begin(0,0);o.finish(0); // A hit at capacity must not clear the map.
        miss(o,1024,-1024,1024); // Only a new key clears the map.
        miss(o,0,0,0);
        o.begin(1024,-1024);o.finish(1024);
    }
    @Test void nonfiniteValuesFail() {
        var o=new ForestHeightOracle();o.begin(1,2);
        assertThrows(IllegalStateException.class,()->o.sampled(1,0,2,Float.NaN));
    }
    @Test void environmentsDoNotShareCachedColumns() {
        var a=new ForestHeightOracle();var b=new ForestHeightOracle();
        miss(a,Integer.MIN_VALUE,Integer.MAX_VALUE,4);miss(b,Integer.MIN_VALUE,Integer.MAX_VALUE,5);
        a.begin(Integer.MIN_VALUE,Integer.MAX_VALUE);a.finish(4);
        b.begin(Integer.MIN_VALUE,Integer.MAX_VALUE);b.finish(5);
    }
}
