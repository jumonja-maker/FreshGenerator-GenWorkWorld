package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class BiomeRegionCompilerTest {
 static Map<String,JsonElement> pack()throws Exception{return WorldgenCatalog.compileBeforeMountainDetails(new VanillaAssets());}
 static JsonElement read(ZipFile z,String n)throws Exception{return JsonParser.parseString(new String(z.getInputStream(z.getEntry(n)).readAllBytes(),StandardCharsets.UTF_8));}
 @Test void historicalStageStillMatchesEveryPublished031Resource()throws Exception {
  var old=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());int count=0;
  try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.1-alpha.1.jar")) {
   for(var e:Collections.list(z.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
    assertEquals(read(z,e.getName()),JsonParser.parseString(ResourceJson.encode(old.get(e.getName()))),e.getName());count++;
   }
  }assertEquals(340,count);
 }
 @Test void finalProductionCatalogChangesOnlyTwoClimateFunctionsAndTheirRouter()throws Exception {
  var current=pack();Set<String> changed=new TreeSet<>();int unchanged=0;
  try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.1-alpha.1.jar")){
   for(var e:Collections.list(z.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
    assertNotNull(current.get(e.getName()),e.getName());
    if(!read(z,e.getName()).equals(JsonParser.parseString(ResourceJson.encode(current.get(e.getName())))))changed.add(e.getName());else unchanged++;
   }
  }assertEquals(BiomeRegionCompiler.REPLACED,changed);assertEquals(337,unchanged);assertEquals(344,current.size());
 }
 @Test void originalNoiseIdsAndAmplitudeArePreserved()throws Exception {
  var p=pack();for(String name:List.of("temperature","vegetation")){
   var original=p.get(BiomeRegionCompiler.path("regions/original_"+name)).getAsJsonObject();
   var now=p.get(BiomeRegionCompiler.path(name)).getAsJsonObject();
   assertEquals(original.get("noise"),now.get("noise"));assertEquals(original.get("y_scale"),now.get("y_scale"));
   assertEquals(original.get("xz_scale").getAsDouble()/3,now.get("xz_scale").getAsDouble(),0);
  }
 }
 @Test void coordinateWarpIsNotLeftAtItsOldOversizedAmplitude()throws Exception {
  var p=pack();for(String name:List.of("temperature","vegetation"))for(String axis:List.of("shift_x","shift_z")){
   var original=p.get(BiomeRegionCompiler.path("regions/original_"+name)).getAsJsonObject();
   var shift=p.get(BiomeRegionCompiler.path(name)).getAsJsonObject().getAsJsonObject(axis);
   assertEquals("minecraft:mul",shift.get("type").getAsString());assertEquals(1.0/3,shift.get("left").getAsDouble(),0);assertEquals(original.get(axis),shift.get("right"));
  }
 }
 @Test void surfaceCoverAndForestStillUseTheSameRegionalFields()throws Exception {
  var p=pack();for(String name:List.of("biome_surface","under_biome_surface")){
   var first=p.get(EcotoneCompiler.ROOT+"material_rule/highlands_v2/"+name+".json").getAsJsonObject().getAsJsonArray("sequence").get(0).getAsJsonObject();
   assertEquals(BiomeRegionCompiler.PREFIX+"temperature",first.get("temperature").getAsString());
  }
 }
 @Test void deepClimateHasAnExplicitOriginalFallback()throws Exception {
  var p=pack();for(String name:List.of("temperature","vegetation")){
   var function=p.get(BiomeRegionCompiler.path("regions/biome_"+name)).getAsJsonObject();
   assertEquals("minecraft:lerp",function.get("type").getAsString());
   assertEquals(BiomeRegionCompiler.PREFIX+name,function.get("first").getAsString());
   assertEquals(BiomeRegionCompiler.PREFIX+"regions/original_"+name,function.get("second").getAsString());
   assertTrue(function.get("alpha").toString().contains("coherent/depth_blocks"));
  }
 }
 @Test void terrainAndHydrologyAxesAreNotZoomed()throws Exception {
  var before=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());var after=pack();
  var a=before.get(BiomeRegionCompiler.SETTINGS).getAsJsonObject();var b=after.get(BiomeRegionCompiler.SETTINGS).getAsJsonObject();
  for(String field:List.of("aquifers","noise","sea_level","material_rule","spawn_target"))assertEquals(a.get(field),b.get(field),field);
  for(String field:List.of("continents","erosion","ridges","depth","final_density","chunk_surface_level"))assertEquals(a.getAsJsonObject("noise_router").get(field),b.getAsJsonObject("noise_router").get(field),field);
 }
 @Test void terrainGraphDoesNotAcquireRegionalClimateDependencies()throws Exception {
  var p=pack();Set<String> seen=new HashSet<>();follow(p,p.get(BiomeRegionCompiler.path("final_density")),seen);
  assertFalse(seen.contains(BiomeRegionCompiler.path("temperature")));assertFalse(seen.contains(BiomeRegionCompiler.path("vegetation")));
  assertTrue(seen.stream().noneMatch(s->s.contains("/regions/")));
 }
 static void follow(Map<String,JsonElement> p,JsonElement value,Set<String> seen){
  if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){
   String id=value.getAsString();if(!id.startsWith("freshgenerator:"))return;
   String path=VanillaAssets.path("density_function",id);if(p.containsKey(path)&&seen.add(path))follow(p,p.get(path),seen);
  }else if(value.isJsonArray())for(var v:value.getAsJsonArray())follow(p,v,seen);
  else if(value.isJsonObject())for(var v:value.getAsJsonObject().asMap().values())follow(p,v,seen);
 }
 @Test void noBiomeWeightsTablesOrStructuresAreIntroduced()throws Exception {
  for(String path:pack().keySet())for(String registry:List.of("biome","multi_noise_biome_source_parameter_list","structure","structure_set","placed_feature","feature","carver"))assertFalse(path.contains("/worldgen/"+registry+"/"),path);
 }
 @Test void onePublicWorldAndAllDimensionsSurvive()throws Exception {
  var p=pack();assertEquals(JsonParser.parseString("[\"freshgenerator:world\"]"),p.get(WorldgenCatalog.NORMAL_TAG).getAsJsonObject().get("values"));
  var before=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());for(String key:p.keySet())if(key.contains("/world_preset/"))assertEquals(before.get(key),p.get(key));
 }
 @Test void compileIsDeterministicAndDoubleApplicationIsRejected()throws Exception {
  assertEquals(pack(),pack());var p=new TreeMap<>(pack());assertThrows(IllegalStateException.class,()->BiomeRegionCompiler.apply(p));
 }
 @Test void originalLayerInputsAreNotMutated()throws Exception {
  var baseline=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());Map<String,JsonElement> copy=new TreeMap<>();baseline.forEach((k,v)->copy.put(k,v.deepCopy()));
  var working=new TreeMap<>(baseline);BiomeRegionCompiler.apply(working);assertEquals(copy,baseline);
 }
}
