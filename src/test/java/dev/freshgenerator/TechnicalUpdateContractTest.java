package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class TechnicalUpdateContractTest {
    static Map<String,JsonElement> baseline()throws Exception {
        return new TreeMap<>(WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets()));
    }
    static Map<String,JsonElement> snapshot(Map<String,JsonElement> source) {
        var copy=new TreeMap<String,JsonElement>();source.forEach((k,v)->copy.put(k,v.deepCopy()));return copy;
    }
    static void failsAtomically(Map<String,JsonElement> source) {
        var before=snapshot(source);assertThrows(RuntimeException.class,()->BiomeRegionCompiler.apply(source));assertEquals(before,source);
    }
    @Test void missingSecondClimateCannotPartiallyCommitFirstClimate()throws Exception {
        var p=baseline();p.remove(BiomeRegionCompiler.path("vegetation"));failsAtomically(p);
    }
    @Test void threeDimensionalClimateFailsWithoutChangingInput()throws Exception {
        var p=baseline();var broken=p.get(BiomeRegionCompiler.path("vegetation")).deepCopy().getAsJsonObject();
        broken.addProperty("y_scale",1);p.put(BiomeRegionCompiler.path("vegetation"),broken);failsAtomically(p);
    }
    @Test void wrongRouterFailsWithoutAddingSavedClimates()throws Exception {
        var p=baseline();var broken=p.get(BiomeRegionCompiler.SETTINGS).deepCopy().getAsJsonObject();
        broken.getAsJsonObject("noise_router").addProperty("vegetation","minecraft:zero");
        p.put(BiomeRegionCompiler.SETTINGS,broken);failsAtomically(p);
    }
    @Test void secondSavedClimateCollisionDoesNotLeaveFirstClimateChanged()throws Exception {
        var p=baseline();p.put(BiomeRegionCompiler.path("regions/original_vegetation"),new JsonPrimitive(0));failsAtomically(p);
    }
    @Test void allProductionWorldgenDataMatchesPublished032()throws Exception {
        var p=WorldgenCatalog.compile(new VanillaAssets());int count=0;
        try(var zip=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.2-alpha.1.jar")){
            for(var e:Collections.list(zip.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
                assertNotNull(p.get(e.getName()),e.getName());
                try(var in=zip.getInputStream(e)){
                    assertEquals(JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)),
                        JsonParser.parseString(ResourceJson.encode(p.get(e.getName()))),e.getName());
                }count++;
            }
        }assertEquals(344,count);assertEquals(count,p.size());
    }
    @Test void onlyPureHeightsUseReplacementCache()throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/freshgenerator/forest/ForestEnvironment.java"));
        assertTrue(source.contains("new ColumnFloatCache(1024)"));
        // Site samples include native biome queries: retain their previous lookup sequence.
        assertTrue(source.contains("if(sites.size()>=256)sites.clear()"));
        assertFalse(source.contains("heights.clear()"));assertFalse(source.contains("new int[][]"));
    }
}
