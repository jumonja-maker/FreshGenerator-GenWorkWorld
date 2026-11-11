package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class EcotoneCompilerTest {
 static Map<String,JsonElement> pack()throws Exception{return WorldgenCatalog.compile(new VanillaAssets());}
 static JsonElement read(ZipFile z,String n)throws Exception{return JsonParser.parseString(new String(z.getInputStream(z.getEntry(n)).readAllBytes(),StandardCharsets.UTF_8));}
 @Test void onlyThreeExistingResourcesChangeComparedToPublishedTerrainFix()throws Exception{
  var p=pack();Set<String> changed=new TreeSet<>();int checked=0;
  try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar")){
   for(var e:Collections.list(z.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
    assertNotNull(p.get(e.getName()),e.getName());
    if(!read(z,e.getName()).equals(JsonParser.parseString(ResourceJson.encode(p.get(e.getName())))))changed.add(e.getName());checked++;
   }
  }
  assertEquals(333,checked);assertEquals(EcotoneCompiler.REPLACED,changed);
 }
 @Test void terrainAquifersAndOriginalDensityDepthRemainUnchanged()throws Exception{
  var p=pack();try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar")){
   for(var e:Collections.list(z.entries()))if(e.getName().contains("/density_function/highlands_v2/")&&e.getName().endsWith(".json"))
    assertEquals(read(z,e.getName()),JsonParser.parseString(ResourceJson.encode(p.get(e.getName()))),e.getName());
   String n=EcotoneCompiler.ROOT+"noise_settings/highlands_v2.json";
   var old=read(z,n).getAsJsonObject();var now=p.get(n).getAsJsonObject();
   assertEquals(old.get("aquifers"),now.get("aquifers"));assertEquals(old.get("spawn_target"),now.get("spawn_target"));
   for(var e:old.getAsJsonObject("noise_router").entrySet())if(!e.getKey().equals("depth"))assertEquals(e.getValue(),now.getAsJsonObject("noise_router").get(e.getKey()));
  }
 }
 @Test void materialFallbackIsPreservedRatherThanDiscarded()throws Exception{
  var p=pack();try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar")){
   for(String n:EcotoneCompiler.REPLACED)if(n.contains("material_rule")){
    var seq=p.get(n).getAsJsonObject().getAsJsonArray("sequence");assertEquals(2,seq.size());assertEquals(read(z,n),JsonParser.parseString(ResourceJson.encode(seq.get(1))));
    assertEquals("freshgenerator:ecotone_surface",seq.get(0).getAsJsonObject().get("type").getAsString());
   }
  }
 }
 @Test void climateCorrectionDoesNotReferenceItself()throws Exception{
  var s=pack().get(VanillaAssets.path("density_function",EcotoneCompiler.PREFIX+"ecotone/biome_depth")).toString();
  assertFalse(s.contains("ecotone/biome_depth"));assertTrue(s.contains("coherent/depth_blocks"));assertTrue(s.contains("highlands_v2/depth"));
 }
}
