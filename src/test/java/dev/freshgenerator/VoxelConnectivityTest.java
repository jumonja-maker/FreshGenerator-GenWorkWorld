package dev.freshgenerator;
import dev.freshgenerator.audit.VoxelConnectivity;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class VoxelConnectivityTest {
    private static int at(int x,int y,int z){return y*25+z*5+x;}
    private static VoxelConnectivity.Result scan(byte[] a){return VoxelConnectivity.scan(a,5,6,5);}
    @Test void isolatedNaturalBlockIsDetected(){byte[] a=new byte[150];a[at(2,3,2)]=3;var r=scan(a);assertEquals(1,r.detached().size());assertEquals(1,r.detached().getFirst().blocks());assertTrue(r.detached().getFirst().skyExposed());}
    @Test void sideSupportedOverhangIsNotFloating(){byte[] a=new byte[150];for(int y=0;y<=3;y++)a[at(1,y,2)]=3;a[at(2,3,2)]=3;assertTrue(scan(a).detached().isEmpty());assertEquals(1,scan(a).groundedComponents());}
    @Test void unknownBoundaryIsNotDeclaredGroundedOrFloating(){byte[] a=new byte[150];a[at(0,3,2)]=3;a[at(1,3,2)]=3;var r=scan(a);assertEquals(1,r.boundaryUnknownComponents());assertEquals(0,r.groundedComponents());assertTrue(r.detached().isEmpty());}
    @Test void topBoundaryRemainsUnknown(){byte[] a=new byte[150];a[at(2,5,2)]=3;assertEquals(1,scan(a).boundaryUnknownComponents());}
    @Test void diagonalContactDoesNotInventSupport(){byte[] a=new byte[150];for(int y=0;y<3;y++)a[at(1,y,2)]=3;a[at(2,3,2)]=3;assertEquals(1,scan(a).detached().size());}
    @Test void woodOrFoliageAreNotTerrainCandidates(){byte[] a=new byte[150];a[at(2,3,2)]=1;assertTrue(scan(a).detached().isEmpty());}
    @Test void protectedStructureIsMarkedNotSilentlyDeleted(){byte[] a=new byte[150];a[at(2,3,2)]=7;var r=scan(a);assertTrue(r.detached().getFirst().protectedStructure());assertEquals(7,a[at(2,3,2)]);}
    @Test void ceilingAboveCandidateSeparatesCaveFromSky(){byte[] a=new byte[150];a[at(2,2,2)]=3;for(int x=0;x<5;x++)a[at(x,4,2)]=3;assertFalse(scan(a).detached().getFirst().skyExposed());}
    @Test void noRowWrappingBetweenNeighbours(){byte[] a=new byte[150];a[at(4,2,1)]=3;a[at(0,2,2)]=3;assertEquals(2,scan(a).boundaryUnknownComponents());}
    @Test void inputAndResultsCannotBeMutatedByAnalysis(){byte[] a=new byte[150];a[at(2,3,2)]=3;byte[] copy=a.clone();var r=scan(a);assertArrayEquals(copy,a);assertThrows(UnsupportedOperationException.class,()->r.detached().clear());}
    @Test void allSolidAndEmptyVolumesAreHandled(){byte[] a=new byte[150];assertEquals(0,scan(a).occupiedBlocks());Arrays.fill(a,(byte)3);assertEquals(150,scan(a).occupiedBlocks());assertEquals(1,scan(a).groundedComponents());}
    @Test void invalidVolumesAreRejected(){assertThrows(IllegalArgumentException.class,()->VoxelConnectivity.scan(new byte[10],5,6,5));assertThrows(IllegalArgumentException.class,()->VoxelConnectivity.scan(new byte[0],0,5,5));assertThrows(IllegalArgumentException.class,()->VoxelConnectivity.scan(new byte[0],100000,100000,100000));}

    private static byte[] icedWater(){
        byte[] a=new byte[150];a[at(2,0,2)]=(byte)(3|VoxelConnectivity.STURDY_FLOOR);
        a[at(2,1,2)]=(byte)VoxelConnectivity.SOURCE_WATER;
        a[at(2,2,2)]=(byte)VoxelConnectivity.SOURCE_WATER;
        a[at(2,3,2)]=(byte)(3|VoxelConnectivity.ORDINARY_ICE);return a;
    }
    private static boolean buoyant(byte[] a){var c=scan(a).detached().getFirst();return VoxelConnectivity.waterSupportedIce(a,5,6,5,c);}
    @Test void sourceWaterSupportsOnlyAnObservedIceSheet(){var a=icedWater();assertTrue(buoyant(a));assertEquals(2,VoxelConnectivity.sourceWaterDepthBelow(a,at(2,3,2),25));assertEquals(1,scan(a).detached().size());}
    @Test void floatingStoneOverWaterStillFails(){var a=icedWater();a[at(2,3,2)]=3;assertFalse(buoyant(a));}
    @Test void iceOverAirStillFails(){var a=icedWater();a[at(2,2,2)]=0;assertFalse(buoyant(a));}
    @Test void interruptedWaterColumnStillFails(){var a=icedWater();a[at(2,1,2)]=0;assertFalse(buoyant(a));}
    @Test void unknownOrNonSturdyBedStillFails(){var a=icedWater();a[at(2,0,2)]=16;assertFalse(buoyant(a));a[at(2,0,2)]=1;assertFalse(buoyant(a));}
    @Test void iceMixedWithStoneStillFails(){var a=icedWater();a[at(3,3,2)]=3;assertFalse(buoyant(a));}
    @Test void eachIceBlockNeedsItsOwnWaterSupport(){var a=icedWater();a[at(3,3,2)]=11;assertFalse(buoyant(a));a[at(3,0,2)]=35;a[at(3,1,2)]=16;a[at(3,2,2)]=16;assertTrue(buoyant(a));}
    @Test void waterEvidenceDoesNotChangeTerrainConnectivityOrInput(){var a=icedWater();var copy=a.clone();assertTrue(buoyant(a));assertArrayEquals(copy,a);assertEquals(1,scan(a).groundedComponents());assertEquals(1,scan(a).detached().size());}
}
