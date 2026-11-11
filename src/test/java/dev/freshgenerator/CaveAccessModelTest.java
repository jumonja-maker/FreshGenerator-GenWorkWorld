package dev.freshgenerator;
import dev.freshgenerator.core.CaveAccessModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CaveAccessModelTest {
 @Test void deepCavesKeepExactlyTheirOriginalWeight(){for(int h=-48;h<320;h++)for(double g:new double[]{0,.25,.5,.9,1})for(int d=42;d<200;d+=7)assertEquals(1,CaveAccessModel.multiplier(h,d,g),0);}
 @Test void underwaterEnvelopeIsNotCapped(){for(int h=-48;h<=63;h++)for(int d=-20;d<100;d++){assertEquals(1,CaveAccessModel.multiplier(h,d,0),0);assertEquals(0,CaveAccessModel.roof(h,0),0);assertTrue(CaveAccessModel.permit(h,h-d,0));}}
 @Test void openRegionsRetainOriginalSurfaceCaves(){for(int h=63;h<304;h++)for(int d=-20;d<100;d++){assertEquals(1,CaveAccessModel.multiplier(h,d,1),0);assertEquals(0,CaveAccessModel.roof(h,1),0);}}
 @Test void closedRegionsProtectTheTopButNotAllUndergroundSpace(){assertEquals(0,CaveAccessModel.multiplier(85,6,0),0);assertTrue(CaveAccessModel.multiplier(85,26,0)>0);assertEquals(14,CaveAccessModel.roof(85,0),0);assertFalse(CaveAccessModel.permit(85,80,14));assertTrue(CaveAccessModel.permit(85,71,14));}
 @Test void blendingNeverAddsCaveWeight(){for(int h=-48;h<=320;h+=4)for(int d=-20;d<=100;d+=2)for(int g=0;g<=10;g++){double m=CaveAccessModel.multiplier(h,d,g/10.0);assertTrue(Double.isFinite(m)&&m>=0&&m<=1);}}
 @Test void allDeepCarverCoordinatesAreForwarded(){for(int h=-48;h<320;h++)for(int g=0;g<=100;g++)assertTrue(CaveAccessModel.permit(h,h-14,CaveAccessModel.roof(h,g/100.0)));}
 @Test void accessIsContinuousAcrossThresholds(){for(double h:new double[]{63,69})assertEquals(CaveAccessModel.multiplier(h-1e-6,8,.3),CaveAccessModel.multiplier(h+1e-6,8,.3),1e-6);for(double d:new double[]{12,42})assertEquals(CaveAccessModel.multiplier(90,d-1e-6,.2),CaveAccessModel.multiplier(90,d+1e-6,.2),1e-6);}
 @Test void fewerEntrancesCannotMakeNewEmptySpaceInCappedBlend(){for(double surface=-1;surface<=1;surface+=.05)for(double cave=-1;cave<=1;cave+=.05)for(double w=0;w<=1;w+=.1){double before=Math.min(surface,surface+(cave-surface)*w);double after=Math.min(surface,surface+(cave-surface)*(w*.3));assertTrue(after>=before-1e-12);}}
 @Test void portalGateHasOpenAndClosedRanges(){assertEquals(0,CaveAccessModel.gate(-.2),0);assertEquals(1,CaveAccessModel.gate(.5),0);assertTrue(CaveAccessModel.gate(.2)>0&&CaveAccessModel.gate(.2)<1);}
 @Test void invalidValuesAreRejected(){assertThrows(IllegalArgumentException.class,()->CaveAccessModel.roof(Double.NaN,0));assertThrows(IllegalArgumentException.class,()->CaveAccessModel.multiplier(90,8,2));assertThrows(IllegalArgumentException.class,()->CaveAccessModel.permit(90,80,20));}
}
