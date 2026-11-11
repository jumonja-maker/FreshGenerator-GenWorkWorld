package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
class SingleWorldTest {
 private static Map<String,JsonElement> catalog()throws Exception{return WorldgenCatalog.compile(new VanillaAssets());}
 @Test void exactlyOneFreshGeneratorWorldIsSelectable()throws Exception{var t=catalog().get(WorldgenCatalog.NORMAL_TAG).getAsJsonObject();assertFalse(t.get("replace").getAsBoolean());assertEquals(JsonParser.parseString("[\"freshgenerator:world\"]"),t.get("values"));}
 @Test void currentWorldIncludesEveryDimensionAndTheLatestTerrainImprovements()throws Exception{var f=catalog();var w=f.get(VanillaAssets.path("world_preset",WorldgenCatalog.WORLD_ID));assertEquals(f.get(VanillaAssets.path("world_preset",WorldgenCatalog.CURRENT_SETTINGS)),w);var d=w.getAsJsonObject().getAsJsonObject("dimensions");assertEquals(Set.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end"),d.keySet());assertEquals(WorldgenCatalog.CURRENT_SETTINGS,d.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("settings").getAsString());assertTrue(f.containsKey(CoherentSurfaceCompiler.ROOT+"coherent/height.json"));}
 @Test void onlyAuditedActiveTerrainResourcesChangeAndLegacyResourcesRemainIntact()throws Exception{new EcotoneCompilerTest().onlyThreeExistingResourcesChangeComparedToPublishedTerrainFix();}
 @Test void noOtherSelectionTagReintroducesLegacyModes()throws Exception{var f=catalog();assertEquals(List.of(WorldgenCatalog.NORMAL_TAG),f.keySet().stream().filter(p->p.contains("/tags/worldgen/world_preset/")).toList());String t=f.get(WorldgenCatalog.NORMAL_TAG).toString();for(String n:List.of("balanced","highlands","_v1","_v2"))assertFalse(t.contains(n));}
 @Test void onlyPublicWorldNameIsTranslatedAndHasNoVariantSuffix()throws Exception{for(String l:List.of("en_us","ru_ru"))try(var in=getClass().getResourceAsStream("/assets/freshgenerator/lang/"+l+".json")){assertNotNull(in);var j=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(Set.of("generator.freshgenerator.world"),j.keySet());assertEquals("FreshGenerator",j.get("generator.freshgenerator.world").getAsString());}}
 @Test void rarityAndOtherModsRegistriesAreNotReplaced()throws Exception{for(String p:catalog().keySet()){assertTrue(p.startsWith("data/freshgenerator/")||p.equals(WorldgenCatalog.NORMAL_TAG),p);for(String r:List.of("biome","structure","structure_set","placed_feature","feature"))assertFalse(p.contains("/worldgen/"+r+"/"),p);}}
 @Test void catalogIsDeterministicAndPublishedMapCannotBeMutated()throws Exception{var f=catalog();assertEquals(f,catalog());assertThrows(UnsupportedOperationException.class,f::clear);}
}
