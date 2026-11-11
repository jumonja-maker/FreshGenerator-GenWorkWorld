package dev.freshgenerator;
import dev.freshgenerator.core.EcotoneModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
class EcotoneModelTest {
 @Test void lowlandsAndShorelinesRemainOutsideTheNewSurfacePolicy(){for(int x=-100;x<=100;x++)for(int y:new int[]{-48,0,63,70,90,104})assertFalse(EcotoneModel.sample(42,x,x*3,y,-1).active());}
 @Test void highlandPolicyIsFullyActive(){for(int x=-100;x<=100;x++)assertTrue(EcotoneModel.sample(42,x,x*3,144,0).active());}
 @Test void seededFieldsAreBoundedAndFinite(){Random r=new Random(4);for(int i=0;i<20000;i++){var f=EcotoneModel.sample(r.nextLong(),r.nextInt(60000000)-30000000,r.nextInt(60000000)-30000000,r.nextDouble()*352-48,r.nextDouble()*4-2);assertTrue(f.patch()>=-1&&f.patch()<=1);assertTrue(f.snowCover()>=0&&f.snowCover()<=1);assertTrue(f.rockPatch()>=-1&&f.rockPatch()<=1);}}
 @Test void orderAndParallelismDoNotAlterTheMask(){var xs=IntStream.range(-1000,1000).boxed().toList();var serial=xs.stream().map(x->EcotoneModel.sample(42,x,x*7,170,0)).toList();var parallel=xs.parallelStream().map(x->EcotoneModel.sample(42,x,x*7,170,0)).toList();assertEquals(serial,parallel);}
 @Test void differentSeedsChangePatches(){assertNotEquals(EcotoneModel.sample(42,81,-191,170,0),EcotoneModel.sample(1337,81,-191,170,0));}
 @Test void continuousNoiseAcrossPositiveAndNegativeLatticeBoundaries(){for(int n=-8;n<=8;n++){double a=EcotoneModel.noise(7,n-1e-7,-2.25),b=EcotoneModel.noise(7,n+1e-7,-2.25);assertEquals(a,b,1e-10);}}
 @Test void patchIsCorrelatedRatherThanPerBlockWhiteNoise(){double largest=0;for(int x=-512;x<512;x++){var a=EcotoneModel.sample(77,x,91,170,0);var b=EcotoneModel.sample(77,x+1,91,170,0);largest=Math.max(largest,Math.abs(a.patch()-b.patch()));}assertTrue(largest<0.18,"step="+largest);}
 @Test void warmerClimateCannotAddSnowAtFixedPosition(){for(int x=-30;x<=30;x++){boolean bare=false;for(int t=-100;t<=100;t++){boolean snow=EcotoneModel.sample(42,x,73,180,t/100.0).snow();if(!snow)bare=true;if(bare)assertFalse(snow);}}}
 @Test void risingAltitudeCannotRemoveSnowAtFixedPosition(){boolean covered=false;for(int y=64;y<=304;y++){boolean snow=EcotoneModel.sample(42,93,-73,y,0.2).snow();if(snow)covered=true;if(covered)assertTrue(snow);}}
 @Test void biomeZoomSurfaceMarginCannotBecomeUnderground(){for(int d=0;d<=24;d++)assertEquals(0,EcotoneModel.biomeDepth(240,240-d,0.6),0);}
 @Test void aboveSurfaceDepthStaysNegative(){assertEquals(-0.125,EcotoneModel.biomeDepth(128,144,1),0);}
 @Test void deepClimateIsExactlyPreserved(){for(int d=88;d<350;d++)for(double original:new double[]{-.2,0,.3,1.2})assertEquals(original,EcotoneModel.biomeDepth(240,240-d,original),0);}
 @Test void depthBlendHasNoStep(){for(double d:new double[]{0,24,40,88})assertEquals(EcotoneModel.biomeDepth(240,240-d-1e-6,.5),EcotoneModel.biomeDepth(240,240-d+1e-6,.5),1e-6);}
 @Test void smoothTransitionHasFlatEnds(){assertEquals(0,EcotoneModel.smooth(0,1,0),0);assertEquals(1,EcotoneModel.smooth(0,1,1),0);assertTrue(EcotoneModel.smooth(0,1,1e-4)<1e-9);}
 @Test void badInputsAreRejected(){assertThrows(IllegalArgumentException.class,()->EcotoneModel.sample(1,0,0,Double.NaN,0));assertThrows(IllegalArgumentException.class,()->EcotoneModel.biomeDepth(1,2,Double.NaN));assertThrows(IllegalArgumentException.class,()->EcotoneModel.smooth(1,1,0));}
}
