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
        var p=WorldgenCatalog.compileBeforeMountainDetails(new VanillaAssets());int count=0;
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
    @Test void rejectedCacheDoesNotRemainInProductionSources()throws Exception {
        assertFalse(Files.exists(Path.of("src/core/java/dev/freshgenerator/core/ColumnFloatCache.java")));
        assertTrue(Files.exists(Path.of("src/test/java/dev/freshgenerator/core/ColumnFloatCache.java")));
        for(String root:List.of("src/core/java","src/main/java"))try(var files=Files.walk(Path.of(root))){
            for(var path:files.filter(p->p.toString().endsWith(".java")).toList())
                assertFalse(Files.readString(path).contains("ColumnFloatCache"),path.toString());
        }
    }
    @Test void forestAdapterIsTheExactPublishedSource()throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/java/dev/freshgenerator/forest/ForestEnvironment.java"));
        var digest=java.security.MessageDigest.getInstance("SHA-1");
        digest.update(("blob "+source.length+"\0").getBytes(StandardCharsets.UTF_8));digest.update(source);
        assertEquals("bcc99d4a68abdfa1f6180387bba9853a0ecd1dc1",HexFormat.of().formatHex(digest.digest()));
    }
}
