package dev.freshgenerator;
import dev.freshgenerator.core.ForestEcology;
import static dev.freshgenerator.core.ForestEcology.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
class ForestEcologyTest {
 static final Site FLAT=new Site(90,63,.1,0,.1,.3,0,0);
 @Test void allSeedsAndFamiliesStayFiniteBounded(){Random r=new Random(21);for(int i=0;i<20000;i++){var s=new Site(r.nextDouble()*370-64,63,r.nextDouble()*5,r.nextDouble()*10-5,r.nextDouble()*2-1,r.nextDouble()*2-1,r.nextDouble(),r.nextDouble());var f=sample(r.nextLong(),r.nextInt(60000000)-30000000,r.nextInt(60000000)-30000000,Family.values()[i%3],s);for(double v:new double[]{f.retention(),f.opening(),f.stand(),f.stress()})assertTrue(Double.isFinite(v)&&v>=0&&v<=1);}}
 @Test void noGlobalMemoryOrGenerationOrder(){var xs=IntStream.range(-1500,1500).boxed().toList();assertEquals(xs.stream().map(x->sample(42,x,x*3,Family.CONIFER,FLAT)).toList(),xs.parallelStream().map(x->sample(42,x,x*3,Family.CONIFER,FLAT)).toList());}
 @Test void worldsDiffer(){assertNotEquals(sample(42,4,7,Family.TEMPERATE,FLAT),sample(1337,4,7,Family.TEMPERATE,FLAT));}
 @Test void moderateMosaicDoesNotTurnOrdinaryForestIntoSparseIslands(){double sum=0;int n=0,open=0;for(int x=-1024;x<=1024;x+=8)for(int z=-1024;z<=1024;z+=8){var f=sample(42,x,z,Family.TEMPERATE,FLAT);sum+=f.retention();n++;if(f.opening()>.5)open++;}assertTrue(sum/n>.82&&sum/n<.98);assertTrue(open>50&&open<n*.12);}
 @Test void noWhiteNoiseClearingBoundaries(){double max=0;for(int x=-2000;x<2000;x++){double a=sample(73,x,49,Family.TEMPERATE,FLAT).retention(),b=sample(73,x+1,49,Family.TEMPERATE,FLAT).retention();max=Math.max(max,Math.abs(a-b));}assertTrue(max<.35,"largest step="+max);}
 @Test void negativeNoiseLatticeIsContinuous(){for(int x=-10;x<10;x++)assertEquals(noise(42,x-1e-8,.3),noise(42,x+1e-8,.3),1e-12);}
 @Test void slopesReduceRecruitmentNotCreateMoreTrees(){for(int x=-50;x<50;x++){double p=1;for(int i=0;i<=40;i++){var s=new Site(90,63,i*.05,0,.1,.3,0,0);double v=sample(42,x,17,Family.CONIFER,s).retention();assertTrue(v<=p+1e-12);p=v;}}}
 @Test void floodedLowBankIsNotBlindlyDenser(){var dry=new Site(64,63,.1,0,.1,.3,0,0);var wet=new Site(64,63,.1,0,.1,.3,1,0);assertTrue(sample(42,5,9,Family.CONIFER,wet).retention()<sample(42,5,9,Family.CONIFER,dry).retention());}
 @Test void highBankDoesNotGetFalseFloodPenalty(){var dry=new Site(80,63,.1,0,.1,.3,0,0);var wet=new Site(80,63,.1,0,.1,.3,1,0);assertEquals(sample(42,5,9,Family.TEMPERATE,dry),sample(42,5,9,Family.TEMPERATE,wet));}
 @Test void tropicalMatrixRetainsMoreCanopyThanCopyingSingleSpeciesGaps(){for(int x=-1000;x<1000;x+=7)assertTrue(sample(42,x,19,Family.TROPICAL,FLAT).retention()>=sample(42,x,19,Family.TEMPERATE,FLAT).retention());}
 @Test void exposedSummitsLoseRecruitment(){var top=new Site(300,63,1,3,-.4,.3,0,0);assertTrue(sample(42,5,9,Family.CONIFER,top).retention()<.08);}
 @Test void invalidInputsRejected(){assertThrows(IllegalArgumentException.class,()->new Site(Double.NaN,63,0,0,0,0,0,0));assertThrows(IllegalArgumentException.class,()->new Site(70,63,-1,0,0,0,0,0));assertThrows(IllegalArgumentException.class,()->noise(42,Double.NaN,0));}
 @Test void deterministicRecruitmentCanBeRepeated(){for(int x=-300;x<300;x++){var f=sample(77,x,x*2,Family.TEMPERATE,FLAT);assertEquals(retain(77,x,x*2,f),retain(77,x,x*2,f));}}
}
