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
}
