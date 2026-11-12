package dev.freshgenerator;

import dev.freshgenerator.village.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VillageTerrainModelTest {
    private static VillageTerrainModel.Site house(int x,int y){return new VillageTerrainModel.Site(x,0,x+8,8,y,true);}
    @Test void emptyPlanIsExactIdentity(){var m=new VillageTerrainModel(List.of());var c=m.column(-17,32,78,63);assertEquals(78,c.height());assertEquals(0,c.influence());assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(c.density(-0.0,78)));}
    @Test void noInfluenceBeyondTwentyFourBlocks(){var m=new VillageTerrainModel(List.of(house(0,80)));for(int x=32;x<100;x++){var c=m.column(x,4,78,63);assertEquals(0,c.influence());assertEquals(-0.25,c.density(-0.25,65));}}
    @Test void rigidGroundIsFlatButDistinctHousesKeepTheirOwnHeight(){var m=new VillageTerrainModel(List.of(house(0,80),house(40,91)));for(int x=0;x<=8;x++)for(int z=0;z<=8;z++){assertEquals(80,m.column(x,z,75+x,63).height());assertEquals(91,m.column(x+40,z,75+x,63).height());}}
    @Test void roadsRetainTheSlope(){var m=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(0,0,24,4,75,false)));for(int x=0;x<24;x++){var c=m.column(x,2,75+x*.3,63);assertEquals(75+x*.3,c.height());assertEquals(1,c.influence());}}
    @Test void protectedRoofClosesNoiseHolesAndLaterCarvers(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,76,63);for(int y=56;y<80;y++){assertTrue(c.density(-0.4,y)>0);assertFalse(c.permitsCarving(y));}}
    @Test void houseHeadroomIsNotBuried(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,92,63);for(int y=80;y<96;y++)assertTrue(c.density(.4,y)<0);}
    @Test void deepCavesAreBitwiseUnchanged(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,72,63);for(int y=-64;y<=32;y++){assertEquals(-.123456789,c.density(-.123456789,y));assertTrue(c.permitsCarving(y));}}
    @Test void highSkyRemainsUnchanged(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,72,63);assertEquals(-.7,c.density(-.7,100));assertTrue(c.permitsCarving(100));}
    @Test void waterOutsideActualRigidPlotsIsNotDammed(){var m=new VillageTerrainModel(List.of(house(0,80),new VillageTerrainModel.Site(10,0,35,4,75,false)));for(int x=12;x<35;x++){var c=m.column(x,2,58,63);assertEquals(0,c.influence());assertEquals(58,c.height());}}
    @Test void orderDuplicatesAndChunkTraversalDoNotChangeThePlan(){var a=house(-20,81);var b=house(20,86);var p=new VillageTerrainModel(List.of(a,b));var q=new VillageTerrainModel(List.of(b,a,a));for(int x=-64;x<64;x++)for(int z=-32;z<32;z++)assertEquals(p.column(x,z,74+x*.1,63),q.column(x,z,74+x*.1,63));}
    @Test void modelCopiesMutableInputs(){var input=new ArrayList<VillageTerrainModel.Site>();input.add(house(0,80));var m=new VillageTerrainModel(input);input.clear();assertEquals(1,m.sites().size());assertThrows(UnsupportedOperationException.class,()->m.sites().clear());}
    @Test void smoothFalloffHasFlatEnds(){double e=1e-4;assertEquals(0,VillageTerrainModel.smooth(0,24,0));assertEquals(1,VillageTerrainModel.smooth(0,24,24));assertTrue(VillageTerrainModel.smooth(0,24,e)<1e-12);assertTrue(1-VillageTerrainModel.smooth(0,24,24-e)<1e-12);}
    @Test void noRectangularCliffAtFeatherBoundary(){var m=new VillageTerrainModel(List.of(house(0,90)));double previous=m.column(28,4,74,63).height();for(int x=29;x<35;x++){double h=m.column(x,4,74,63).height();assertTrue(Math.abs(h-previous)<.5);previous=h;}}
    @Test void negativeCoordinatesTranslateExactly(){var p=new VillageTerrainModel(List.of(house(0,80)));var q=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(-64,-64,-56,-56,80,true)));for(int x=-30;x<40;x++)assertEquals(p.column(x,4,75,63),q.column(x-64,-60,75,63));}
    @Test void invalidFootprintsAndInputsFail(){assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Site(2,0,1,2,70,true));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Site(0,0,2,2,999,true));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Column(70,Double.NaN,1));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Column(70,70,2));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel(List.of()).column(0,0,Double.NaN,63));}
    @Test void vanillaVillageWhitelistDoesNotIncludeOutpostsOrModdedStructures(){assertTrue(VillageTerrainPlan.villageId("minecraft:village_savanna"));assertFalse(VillageTerrainPlan.villageId("minecraft:pillager_outpost"));assertFalse(VillageTerrainPlan.villageId("other:village_savanna"));}
    @Test void parallelPureEvaluationIsRepeatable(){var m=new VillageTerrainModel(List.of(house(0,80)));var a=java.util.stream.IntStream.range(-100,100).mapToObj(x->m.column(x,2,75,63)).toList();var b=java.util.stream.IntStream.range(-100,100).parallel().mapToObj(x->m.column(x,2,75,63)).toList();assertEquals(a,b);}
}
