package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class NaturalCompilerTest {
    private static Map<String,JsonElement> compile() throws Exception {
        return new NaturalWorldgenCompiler(new VanillaAssets(),"test_v2",TerrainProfile.HIGHLANDS).compile();
    }
    private static JsonElement get(Map<String,JsonElement> files,String registry,String local) {
        return Objects.requireNonNull(files.get(VanillaAssets.path(registry,"freshgenerator:test_v2"+local)));
    }
    @Test void previousPresetResourcesMatchPublishedAlphaExactly() throws Exception {
        try(var zip=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.1.0-alpha.1.jar")) {
            for(var pair:Map.of("balanced_v1",TerrainProfile.BALANCED,"highlands_v1",TerrainProfile.HIGHLANDS).entrySet()) {
                var files=new WorldgenCompiler(new VanillaAssets(),pair.getKey(),pair.getValue()).compile();
                for(var f:files.entrySet()) {
                    var entry=zip.getEntry(f.getKey());assertNotNull(entry,f.getKey());
                    try(var in=zip.getInputStream(entry)) {
                        // Compare serialized registry data on both sides. Gson's in-memory Float
                        // numbers and parsed decimal numbers need not compare equal before encoding.
                        assertEquals(JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)),
                                JsonParser.parseString(f.getValue().toString()),f.getKey());
                    }
                }
            }
        }
    }
    @Test void climateAxesAreUnchangedApartFromTheirNamespace() throws Exception {
        var old=new WorldgenCompiler(new VanillaAssets(),"test_v1",TerrainProfile.HIGHLANDS).compile();var fresh=compile();
        for(String axis:List.of("continents","erosion","ridges","ridges_folded","temperature","vegetation")) {
            var a=old.get(VanillaAssets.path("density_function","freshgenerator:test_v1/"+axis));
            var b=get(fresh,"density_function","/"+axis);
            assertEquals(JsonParser.parseString(a.toString().replace("test_v1","test_v2")),b,axis);
        }
    }
    @Test void rareBiomeAndStructureRegistriesAreNotReplaced() throws Exception {
        for(String path:compile().keySet()) {
            assertTrue(path.startsWith("data/freshgenerator/"));
            for(String kind:List.of("biome","structure","structure_set","feature","placed_feature","multi_noise_biome_source_parameter_list"))
                assertFalse(path.contains("/worldgen/"+kind+"/"),path);
        }
    }
    @Test void nativeBiomeSelectorDimensionsAndHeightStayIntact() throws Exception {
        var v=new VanillaAssets();var files=compile();
        var original=v.read("data/minecraft/worldgen/world_preset/normal.json").getAsJsonObject().getAsJsonObject("dimensions");
        var changed=get(files,"world_preset","").getAsJsonObject().getAsJsonObject("dimensions");
        for(String dim:List.of("minecraft:the_end","minecraft:the_nether"))assertEquals(original.get(dim),changed.get(dim));
        assertEquals(original.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("biome_source"),
                changed.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("biome_source"));
        var settings=get(files,"noise_settings","").getAsJsonObject();
        var nativeSettings=v.read("data/minecraft/worldgen/noise_settings/overworld.json").getAsJsonObject();
        for(String field:List.of("noise","sea_level","default_fluid","default_block","disable_mob_generation"))
            assertEquals(nativeSettings.get(field),settings.get(field));
    }
    @Test void bothSurfaceEstimateAndTerrainUseTheSameHigherFade() throws Exception {
        var files=compile();
        for(String name:List.of("final_density","preliminary_surface_level")) {
            var value=get(files,"density_function","/"+name);List<JsonObject> gradients=new ArrayList<>();
            walkObjects(value,o->{if(o.has("type")&&o.get("type").getAsString().equals("minecraft:gradient")
                    &&o.get("from_coordinate").getAsInt()==288)gradients.add(o);});
            assertEquals(1,gradients.size(),name);assertEquals(312,gradients.getFirst().get("to_coordinate").getAsInt());
        }
    }
    @Test void nativeAquifersAndBeardifierAreKept() throws Exception {
        var f=compile();assertTrue(get(f,"noise_settings","").getAsJsonObject().has("aquifers"));
        assertTrue(get(f,"density_function","/final_density").toString().contains("minecraft:beardifier"));
    }
    @Test void additionalNoisesAreWorldSeededNotHardcodedToOneWorld() throws Exception {
        var f=compile();
        for(String name:List.of("massif_variation","slope_spurs")) {
            var n=get(f,"noise","/"+name).getAsJsonObject();assertTrue(n.has("base_octave"));assertFalse(n.has("seed"));
        }
    }
    @Test void surfaceTransitionIsRestrictedAndKeepsOriginalFallback() throws Exception {
        var v=new VanillaAssets();var f=compile();
        var seq=get(f,"material_rule","/biome_surface/default").getAsJsonObject().getAsJsonArray("sequence");
        assertEquals(2,seq.size());
        assertEquals(v.read("data/minecraft/worldgen/material_rule/overworld/biome_surface/default.json"),seq.get(1));
        String transition=seq.get(0).toString();
        assertTrue(transition.contains("minecraft:meadow"));assertTrue(transition.contains("minecraft:not_underwater"));
        assertFalse(transition.contains("minecraft:mushroom_fields"));assertFalse(transition.contains("minecraft:desert"));
    }
    @Test void compilationIsDeterministicAndRejectsUnversionedNames() throws Exception {
        assertEquals(compile(),compile());
        assertThrows(IllegalArgumentException.class,()->new NaturalWorldgenCompiler(new VanillaAssets(),"test_v1",TerrainProfile.BALANCED));
    }
    private static void walkObjects(JsonElement value,java.util.function.Consumer<JsonObject> action) {
        if(value.isJsonObject()){action.accept(value.getAsJsonObject());value.getAsJsonObject().asMap().values().forEach(v->walkObjects(v,action));}
        if(value.isJsonArray())value.getAsJsonArray().forEach(v->walkObjects(v,action));
    }
}
