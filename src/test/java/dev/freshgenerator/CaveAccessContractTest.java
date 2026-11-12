package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.core.CaveAccessModel;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
class CaveAccessContractTest {
 static JsonElement read(ZipFile z,String n)throws Exception{return JsonParser.parseString(new String(z.getInputStream(z.getEntry(n)).readAllBytes(),StandardCharsets.UTF_8));}
 @Test void exactlyTwoExistingResourcesChangeAndEverythingElseIsPreserved()throws Exception{
  // Historical cave layer. The complete current catalog is independently compared to 0.3.1 by BiomeRegionCompilerTest.
  var p=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());Set<String> changed=new TreeSet<>();int unchanged=0;
  try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.0-alpha.1.jar")){
   for(var e:Collections.list(z.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
    assertNotNull(p.get(e.getName()),e.getName());var now=JsonParser.parseString(ResourceJson.encode(p.get(e.getName())));
    if(!read(z,e.getName()).equals(now))changed.add(e.getName());else unchanged++;
   }
   assertEquals(CaveAccessCompiler.REPLACED,changed);assertEquals(332,unchanged);assertEquals(340,p.size());
   assertEquals(read(z,CaveAccessCompiler.WEIGHT),JsonParser.parseString(ResourceJson.encode(p.get(CoherentSurfaceCompiler.ROOT+"cave_access/original_weight.json"))));
   var settings=p.get(CaveAccessCompiler.SETTINGS_PATH).deepCopy().getAsJsonObject();var debug=settings.getAsJsonArray("debug_functions");
   assertEquals(CaveAccessModel.ROOF_LABEL,debug.remove(debug.size()-1).getAsJsonObject().get("label").getAsString());
   assertEquals(CaveAccessModel.HEIGHT_LABEL,debug.remove(debug.size()-1).getAsJsonObject().get("label").getAsString());
   assertEquals(read(z,CaveAccessCompiler.SETTINGS_PATH),JsonParser.parseString(ResourceJson.encode(settings)));
  }
 }
 @Test void noNativeCarverBiomeFeatureOrStructureDefinitionIsOverridden()throws Exception{
  var p=WorldgenCatalog.compile(new VanillaAssets());for(String name:p.keySet())for(String registry:List.of("carver","biome","structure","structure_set","placed_feature","feature"))assertFalse(name.contains("/worldgen/"+registry+"/"),name);
 }
 @Test void gateIsWorldSeededAndTwoDimensional()throws Exception{
  var p=WorldgenCatalog.compile(new VanillaAssets());var noise=p.get(CoherentSurfaceCompiler.ROOT+"cave_access/entrance_noise.json").getAsJsonObject().getAsJsonObject("input");
  assertEquals("minecraft:noise",noise.get("type").getAsString());assertEquals(0,noise.get("y_scale").getAsDouble(),0);
  assertTrue(p.containsKey(VanillaAssets.path("noise",noise.get("noise").getAsString())));
 }
 @Test void nativeCarverSafetyAndRandomSequenceAreNotReplaced()throws Exception{
  String mixin=Files.readString(Path.of("src/main/java/dev/freshgenerator/mixin/CaveCarverMixin.java"));
  assertTrue(mixin.contains("original.call(carver,generation,random,target,source,output)"));assertTrue(mixin.contains("@Share("));assertTrue(mixin.contains("require=1"));
  String guard=Files.readString(Path.of("src/main/java/dev/freshgenerator/cave/CaveCarverGuard.java"));
  for(String bad:List.of(".setBlock(",".setBlockState(",".getChunk(",".nextFloat(",".nextInt(","ThreadLocal"))assertFalse(guard.contains(bad),bad);
  assertTrue(guard.contains("new float[256]"));
 }
}
