package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.village.VillageTerrainModel;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VillageLakeContractTest {
 @Test void knownSurfaceLakeIntersectsTheSameProtectedRoofAsCarvers(){
  var c=new VillageTerrainModel.Column(67.3,67.3,1);int blocked=0;
  for(int y=67-4;y<=67+3;y++)if(!c.permitsCarving(y))blocked++;
  assertEquals(8,blocked);
 }
 @Test void deepLakesStillPassEveryVoxel(){
  var c=new VillageTerrainModel.Column(90,92,1);
  for(int y=20-4;y<=20+3;y++)assertTrue(c.permitsCarving(y));
 }
 @Test void unprotectedColumnsDoNotSuppressLakes(){
  var c=new VillageTerrainModel.Column(67,67,0);
  for(int y=-64;y<320;y++)assertTrue(c.permitsCarving(y));
 }
 @Test void lowWaterDoesNotBecomeAnArtificialProtectedDam(){
  var m=new VillageTerrainModel(java.util.List.of(new VillageTerrainModel.Site(0,0,16,3,64,false)));
  assertEquals(0,m.column(5,2,50,63).influence());
 }
 @Test void exactNativeLakeIdentifierHasTheExpectedFeatureType()throws Exception {
  try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/feature/lake_lava.json")){
   assertNotNull(in);var o=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
   assertEquals("minecraft:lake",o.get("type").getAsString());
   assertEquals("minecraft:lava",o.getAsJsonObject("fluid").get("id").getAsString());
  }
 }
 @Test void guardNeverWritesBlocksRequestsNewChunksOrUsesRandomness()throws Exception {
  String s=Files.readString(Path.of("src/main/java/dev/freshgenerator/village/VillageLakeGuard.java"));
  assertFalse(s.contains("setBlock("));assertFalse(s.contains("getRandom("));assertFalse(s.contains("nextInt("));
  assertTrue(s.contains("ChunkStatus.STRUCTURE_STARTS,false"));assertTrue(s.contains("plan.permitsCarving(x,y,z)"));
 }
 @Test void mixinCannotSilentlyBecomeGlobalOrApplyToCommands()throws Exception {
  String s=Files.readString(Path.of("src/main/java/dev/freshgenerator/mixin/VillageLakeMixin.java"));
  assertTrue(s.contains("biomeCheck && feature instanceof LakeFeature"));assertTrue(s.contains("minecraft:lake_lava"));
  assertTrue(s.contains("original.call(feature,level,generator,random,at)"));
 }
 @Test void everyNativePlacedLavaLakeUsesTheAuditedIdentifier()throws Exception {
  for(String n:java.util.List.of("lake_lava_surface","lake_lava_underground"))try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+n+".json")){
   assertNotNull(in);assertEquals("minecraft:lake_lava",JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().get("feature").getAsString());
  }
 }
}
