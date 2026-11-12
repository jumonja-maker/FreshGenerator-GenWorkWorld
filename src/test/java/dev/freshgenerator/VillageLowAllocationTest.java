package dev.freshgenerator;

import dev.freshgenerator.village.VillageTerrainModel;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The fast no-op path has identical density and carving behavior. */
class VillageLowAllocationTest {
    @Test void conservativeFootprintPrecheckNeverSkipsAnInfluencedColumn() {
        var sites=List.of(
            new VillageTerrainModel.Site(-96,-48,-84,-37,80,true),
            new VillageTerrainModel.Site(0,0,12,10,89,true),
            new VillageTerrainModel.Site(27,-3,34,3,76,false));
        var model=new VillageTerrainModel(sites);
        int skipped=0,near=0;
        for(int x=-140;x<90;x++)for(int z=-90;z<70;z+=2) {
            boolean nearby=model.hasNearbySite(x,z);
            var old=model.column(x,z,72+x*0.008,63);
            if(!nearby) {
                skipped++;
                assertEquals(0,old.influence());
                for(double original:new double[]{-0.0,-0.47,0.0,0.86})
                    for(int y:new int[]{-48,0,42,64,80,128})
                        assertEquals(Double.doubleToRawLongBits(original),
                            Double.doubleToRawLongBits(old.density(original,y)));
                assertTrue(old.permitsCarving(80));
            } else near++;
        }
        assertTrue(skipped>10000);
        assertTrue(near>1000);
    }
    @Test void hardRadiusBoundaryRemainsExcluded() {
        var model=new VillageTerrainModel(List.of(
            new VillageTerrainModel.Site(0,0,8,8,81,true)));
        assertFalse(model.hasNearbySite(32,4));
        assertTrue(model.hasNearbySite(31,4));
        assertFalse(model.hasNearbySite(32,32));
        assertFalse(model.hasNearbySite(-24,4));
        assertTrue(model.hasNearbySite(-23,4));
    }
    @Test void waterPreservesTheOriginalNoInfluenceDecision() {
        var model=new VillageTerrainModel(List.of(
            new VillageTerrainModel.Site(0,0,12,10,81,true)));
        assertTrue(model.hasNearbySite(23,5));
        assertEquals(0,model.column(23,5,50,63).influence());
    }
    @Test void lakeCollectsOneRegionSnapshotAndUsesEachTargetFootprint()throws Exception {
        var s=Files.readString(Path.of("src/main/java/dev/freshgenerator/village/VillageLakeGuard.java"));
        String needle="VillageTerrainPlan.villagePieces(";
        assertEquals(s.indexOf(needle),s.lastIndexOf(needle));
        assertTrue(s.contains("VillageTerrainPlan.createWithPieces("));
        assertFalse(s.contains("VillageTerrainPlan.create(chunk,"));
        assertTrue(s.contains("region.getChunk(cx,cz,ChunkStatus.STRUCTURE_STARTS,false)"));
        assertTrue(s.contains("plan.permitsCarving(x,y,z)"));
    }
    @Test void noGlobalWorldCacheOrForcedGeneration()throws Exception {
        var s=Files.readString(Path.of("src/main/java/dev/freshgenerator/village/VillageTerrainPlan.java"));
        assertTrue(s.contains("metadataOrigin=region.getCenter()"));
        assertTrue(s.contains("ChunkStatus.STRUCTURE_STARTS,false"));
        assertTrue(s.contains("model.hasNearbySite(wx,wz)"));
        assertFalse(s.contains("static Map<"));
        assertFalse(s.contains(".join("));
        assertFalse(s.contains("setBlock("));
    }
}
