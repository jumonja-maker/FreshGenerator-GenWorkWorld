package dev.freshgenerator;

import dev.freshgenerator.village.VillageTerrainModel;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Regressions for the real FEATURES-stage out-of-bounds crash, not a world mock. */
class VillageMetadataBoundsTest {
 @Test void metadataHaloIsAnchoredToTheSchedulingRegionNotLakeTarget() throws Exception {
  String source=Files.readString(Path.of("src/main/java/dev/freshgenerator/village/VillageTerrainPlan.java"));
  assertTrue(source.contains("metadataOrigin=region.getCenter()"));
  assertTrue(source.contains("cx=metadataOrigin.x()-8"));
  assertTrue(source.contains("cz=metadataOrigin.z()-8"));
  assertFalse(source.contains("cx=origin.x()-8"));
  assertFalse(source.contains("cz=origin.z()-8"));
 }
 @Test void everyNativeLakeVoxelHasAllRelevantStartChunksInsideTheRegionHalo() {
  // A start outside +/-8 cannot contribute, even its closest possible piece at +/-96.
  for(int center: new int[]{-9000,-1,0,1,9000})for(int placement=0;placement<16;placement++)
  for(int delta=-8;delta<=7;delta++)for(int startDelta=-12;startDelta<=12;startDelta++) {
   int world=center*16+placement+delta;
   int start=(center+startDelta)*16;
   int closest=Math.max(start-96,Math.min(start+96,world));
   boolean contributes=Math.abs(world-closest)<VillageTerrainModel.RADIUS;
   if(contributes)assertTrue(Math.abs(startDelta)<=8,"Uncovered start: "+startDelta);
  }
 }
 @Test void nativeLakeOnlyTouchesTheCenterAndItsImmediateNeighbours() {
  for(int center:new int[]{-9000,-1,0,1,9000})for(int placement=0;placement<16;placement++)
  for(int delta=-8;delta<=7;delta++)assertTrue(Math.abs(Math.floorDiv(center*16+placement+delta,16)-center)<=1);
 }
 @Test void oldTargetRelativeHaloWouldRequestUnavailableNinthRing() {
  int westTarget=Math.floorDiv(-8,16),eastTarget=Math.floorDiv(22,16);
  assertEquals(-9,westTarget-8);assertEquals(9,eastTarget+8);
 }
 @Test void aStartJustOutsideTheHaloHasExactlyZeroModelInfluenceOnEveryLakeColumn() {
  for(int startChunk:new int[]{-9,9}) {
   var m=new VillageTerrainModel(java.util.List.of(new VillageTerrainModel.Site(startChunk*16-96,-96,startChunk*16+96,96,80,true)));
   for(int x=-8;x<=22;x++)assertEquals(0,m.column(x,0,80,63).influence());
  }
 }
 @Test void targetPlanDoesNotEditBlocksOrAdvanceChunkStages() throws Exception {
  String source=Files.readString(Path.of("src/main/java/dev/freshgenerator/village/VillageTerrainPlan.java"));
  assertFalse(source.contains("setBlock("));assertFalse(source.contains(".join("));
  assertFalse(source.contains("getChunkSource("));assertTrue(source.contains("ChunkStatus.STRUCTURE_STARTS,false"));
 }
}
