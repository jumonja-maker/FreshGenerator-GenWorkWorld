package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldgenCompilerTest {
    private static Map<String,JsonElement> compile(TerrainProfile p) throws Exception {
        return new WorldgenCompiler(new VanillaAssets(),"test_v1",p).compile();
    }
    private static JsonElement output(Map<String,JsonElement> files,String kind,String local) {
        return Objects.requireNonNull(files.get(VanillaAssets.path(kind,"freshgenerator:"+local)));
    }
    @Test void builtPackUsesOnlyOwnNamespace() throws Exception {
        for(String path:compile(TerrainProfile.BALANCED).keySet()) assertTrue(path.startsWith("data/freshgenerator/"),path);
    }
    @Test void noBiomeStructureOrFeatureRarityRegistriesAreReplaced() throws Exception {
        for(String path:compile(TerrainProfile.BALANCED).keySet())
            for(String kind:List.of("biome","structure","structure_set","placed_feature","feature","multi_noise_biome_source_parameter_list"))
                assertFalse(path.contains("/worldgen/"+kind+"/"),path);
    }
    @Test void nativeBiomeSelectorNetherAndEndAreRetainedExactly() throws Exception {
        var original=new VanillaAssets().read("data/minecraft/worldgen/world_preset/normal.json").getAsJsonObject().getAsJsonObject("dimensions");
        var generated=output(compile(TerrainProfile.BALANCED),"world_preset","test_v1").getAsJsonObject().getAsJsonObject("dimensions");
        assertEquals(original.get("minecraft:the_nether"),generated.get("minecraft:the_nether"));
        assertEquals(original.get("minecraft:the_end"),generated.get("minecraft:the_end"));
        assertEquals(original.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("biome_source"),
                generated.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("biome_source"));
    }
    @Test void immutableSeededNativeNoiseIdsAreNotReplaced() throws Exception {
        String s=output(compile(TerrainProfile.BALANCED),"density_function","test_v1/continents").toString();
        assertTrue(s.contains("minecraft:continentalness"));assertFalse(s.contains("seed"));
    }
    @Test void allLocalReferencesHaveDefinitions() throws Exception {
        var files=compile(TerrainProfile.BALANCED); Set<String> ids=new HashSet<>();
        for(String path:files.keySet()) if(path.contains("/worldgen/")) {
            String rest=path.substring(path.indexOf("/worldgen/")+10);ids.add("freshgenerator:"+rest.substring(rest.indexOf('/')+1,rest.length()-5));
        }
        for(JsonElement json:files.values()) walk(json,id->{if(id.startsWith("freshgenerator:"))assertTrue(ids.contains(id),id);});
    }
    @Test void spawnClimateTargetsAreRewiredTogetherWithTerrain() throws Exception {
        var settings=output(compile(TerrainProfile.BALANCED),"noise_settings","test_v1").getAsJsonObject();
        for(JsonElement target:settings.getAsJsonArray("spawn_target")) for(String key:target.getAsJsonObject().keySet())
            assertTrue(key.startsWith("freshgenerator:test_v1/"),key);
    }
    @Test void aquifersAndNativeStructureBeardingStayPresent() throws Exception {
        var files=compile(TerrainProfile.BALANCED);var settings=output(files,"noise_settings","test_v1").getAsJsonObject();
        assertTrue(settings.has("aquifers"));
        assertTrue(files.values().stream().anyMatch(v->v.toString().contains("minecraft:beardifier")));
    }
    @Test void vanillaVerticalLimitsSeaAndMobGenerationAreUnchanged() throws Exception {
        var original=new VanillaAssets().read("data/minecraft/worldgen/noise_settings/overworld.json").getAsJsonObject();
        var settings=output(compile(TerrainProfile.BALANCED),"noise_settings","test_v1").getAsJsonObject();
        for(String key:List.of("sea_level","noise","disable_mob_generation","default_block","default_fluid","legacy_random_source"))
            assertEquals(original.get(key),settings.get(key),key);
    }
    @Test void identityAdapterDoesNotAddAnOffsetWrapper() throws Exception {
        var original=new VanillaAssets().read("data/minecraft/worldgen/density_function/overworld/offset.json");
        var files=compile(TerrainProfile.IDENTITY);
        JsonElement restored=JsonParser.parseString(output(files,"density_function","test_v1/offset").toString()
                .replace("freshgenerator:test_v1/","minecraft:overworld/"));
        assertEquals(original,restored);assertFalse(files.containsKey(VanillaAssets.path("density_function","freshgenerator:test_v1/base_offset")));
    }
    @Test void biomeScalesChangeSamplingNotNoiseAmplitude() throws Exception {
        var original=new VanillaAssets().read("data/minecraft/worldgen/density_function/overworld/temperature.json");
        var adjusted=output(compile(TerrainProfile.BALANCED),"density_function","test_v1/temperature");
        List<Double> before=new ArrayList<>(),after=new ArrayList<>();noiseScales(original,before);noiseScales(adjusted,after);
        assertEquals(1,before.size());assertEquals(1,after.size());assertEquals(before.getFirst()/1.25,after.getFirst(),1e-12);
    }
    @Test void sourceFingerprintsExistAndCompilationIsRepeatable() throws Exception {
        var v=new VanillaAssets();var a=new WorldgenCompiler(v,"test_v1",TerrainProfile.BALANCED).compile();
        assertEquals(a,compile(TerrainProfile.BALANCED));assertTrue(v.fingerprints().size()>10);
        v.fingerprints().values().forEach(s->assertTrue(s.matches("[0-9a-f]{64}")));
    }
    @Test void compilerRejectsUnversionedIdsAndReuse() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new WorldgenCompiler(new VanillaAssets(),"unversioned",TerrainProfile.BALANCED));
        var c=new WorldgenCompiler(new VanillaAssets(),"test_v1",TerrainProfile.BALANCED);c.compile();
        assertThrows(IllegalStateException.class,c::compile);
    }
    private static void walk(JsonElement e,java.util.function.Consumer<String> c) {
        if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString())c.accept(e.getAsString());
        if(e.isJsonObject())e.getAsJsonObject().entrySet().forEach(x->{c.accept(x.getKey());walk(x.getValue(),c);});
        if(e.isJsonArray())e.getAsJsonArray().forEach(x->walk(x,c));
    }
    private static void noiseScales(JsonElement e,List<Double> list) {
        if(e.isJsonObject()){var o=e.getAsJsonObject();if(o.has("type")&&o.get("type").getAsString().equals("minecraft:noise"))list.add(o.get("xz_scale").getAsDouble());
            o.entrySet().forEach(x->noiseScales(x.getValue(),list));}
        if(e.isJsonArray())e.getAsJsonArray().forEach(x->noiseScales(x,list));
    }
}
