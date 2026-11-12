package dev.freshgenerator;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.freshgenerator.pack.BiomeRegionCompiler;
import dev.freshgenerator.pack.CoherentSurfaceCompiler;
import dev.freshgenerator.pack.HydrologyCompiler;
import dev.freshgenerator.pack.VanillaAssets;
import dev.freshgenerator.pack.WorldgenCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyCompilerTest {
    private static final String P = CoherentSurfaceCompiler.PREFIX;
    private static final String HYDROLOGY = P + "coherent/hydrology/";
    private static Map<String, JsonElement> baseline;
    private static Map<String, JsonElement> candidate;

    @BeforeAll
    static void compile() throws Exception {
        baseline = WorldgenCatalog.compileBeforeHydrology(new VanillaAssets());
        candidate = WorldgenCatalog.compile(new VanillaAssets());
    }

    @Test
    void changesOnlyHydrologyInputsAndAddsSevenPrivateResources() {
        Set<String> changed = new HashSet<>();
        baseline.forEach((path, original) -> {
            assertTrue(candidate.containsKey(path), path);
            if (!original.equals(candidate.get(path))) changed.add(path);
        });
        assertEquals(Set.of(HydrologyCompiler.HEIGHT, HydrologyCompiler.CAVE_WEIGHT, HydrologyCompiler.PRELIMINARY, BiomeRegionCompiler.SETTINGS,
                HydrologyCompiler.BIOME_DEPTH, HydrologyCompiler.BIOME_TEMPERATURE, HydrologyCompiler.BIOME_MOISTURE), changed);
        assertEquals(baseline.size() + 7, candidate.size());
        assertEquals(baseline.get(HydrologyCompiler.HEIGHT), candidate.get(path(HYDROLOGY + "base_height")));
        assertEquals(baseline.get(HydrologyCompiler.CAVE_WEIGHT), candidate.get(path(HYDROLOGY + "base_cave_weight")));
    }

    @Test
    void theCutUsesTheOriginalTerrainAndNativeSeaLevel() {
        var blend = candidate.get(path(HYDROLOGY + "cut")).getAsJsonObject()
                .getAsJsonObject("input");
        assertEquals("minecraft:blend_alpha", blend.getAsJsonObject("left").get("type").getAsString());
        assertEquals(HYDROLOGY + "model", blend.get("right").getAsString());
        var drainage = candidate.get(path(HYDROLOGY + "model")).getAsJsonObject().getAsJsonObject("input");
        assertEquals("freshgenerator:drainage", drainage.get("type").getAsString());
        assertEquals(HYDROLOGY + "base_height", drainage.get("terrain").getAsString());
        assertEquals(P + "continents", drainage.get("continents").getAsString());
        assertEquals(P + "vegetation", drainage.get("moisture").getAsString());
        assertEquals(63, drainage.get("sea_level").getAsInt());
        assertEquals(32, drainage.get("height_grid").getAsInt());
        var settings = candidate.get(BiomeRegionCompiler.SETTINGS).deepCopy().getAsJsonObject();
        var debug = settings.getAsJsonArray("debug_functions");
        var binding = debug.remove(debug.size() - 1).getAsJsonObject();
        assertEquals("FreshGenerator drainage", binding.get("label").getAsString());
        assertEquals(HYDROLOGY + "model", binding.get("function").getAsString());
        for (JsonElement item : debug) {
            var entry = item.getAsJsonObject();
            if (entry.get("label").getAsString().equals("FG cave access: surface")) {
                assertEquals(HYDROLOGY + "base_height", entry.get("function").getAsString());
                entry.addProperty("function", P + "coherent/height");
            } else if (entry.get("label").getAsString().equals("FG cave access: roof")) {
                assertEquals(HYDROLOGY + "reference_roof_depth", entry.get("function").getAsString());
                entry.addProperty("function", P + "cave_access/roof_depth");
            }
        }
        assertEquals(baseline.get(BiomeRegionCompiler.SETTINGS), settings);
    }

    @Test
    void lakeBedSupportFadesOutBeforeTheDeepCaves() {
        assertEquals(0, caveWeight(12, 56, 0), 1e-5);
        assertEquals(0, caveWeight(12, 56, 5), 1e-5);
        assertTrue(caveWeight(12, 56, 8) > 0 && caveWeight(12, 56, 8) < 1);
        assertEquals(1, caveWeight(12, 56, 12), 1e-5);
        assertEquals(1, caveWeight(0, 56, 0), 1e-5);
        assertEquals(0, caveWeight(0.25, 110, 0), 1e-5);
        assertEquals(0, caveWeight(-0.25, 110, 0), 1e-5);
    }

    @Test
    void aRaisedMountainBankRetainsTheWholeRoof() {
        for (int depth = 2; depth <= 18; depth += 2) {
            assertEquals(0, caveWeight(-1.75, 112.25, depth), 1e-5);
        }
        assertEquals(1, caveWeight(-1.75, 112.25, 44), 1e-5);
    }

    @Test
    void heightSubtractsTheSharedDrainageCut() {
        var values = new HashMap<>(Map.of(HYDROLOGY + "base_height", 74.0, HYDROLOGY + "cut", 13.0));
        assertEquals(61, evaluate(candidate.get(HydrologyCompiler.HEIGHT), values), 1e-5);
    }

    @Test
    void undergroundReferencesDoNotFollowTheExcavatedHeight() {
        for (String name : new String[]{"reference_cave_weight", "reference_roof_depth"}) {
            String graph = candidate.get(path(HYDROLOGY + name)).toString();
            assertTrue(graph.contains(HYDROLOGY + "base_height"));
            assertFalse(graph.contains("\"" + P + "coherent/height\""));
            assertFalse(graph.contains("\"" + P + "coherent/depth_blocks\""));
            assertFalse(graph.contains("\"" + P + "cave_access/original_weight\""));
        }
        assertEquals(baseline.get(HydrologyCompiler.PRELIMINARY).toString().replace(
                P + "coherent/height", HYDROLOGY + "base_height"),
                candidate.get(HydrologyCompiler.PRELIMINARY).toString());
    }

    @Test
    void resourceCollisionLeavesTheCatalogIntact() {
        var input = new TreeMap<>(baseline);
        input.put(path(HYDROLOGY + "cut"), new JsonPrimitive(0));
        var expected = new TreeMap<>(input);
        assertThrows(IllegalStateException.class, () -> HydrologyCompiler.apply(input));
        assertEquals(expected, input);
    }

    private static String path(String id) {
        return VanillaAssets.path("density_function", id);
    }

    private static double caveWeight(double cut, double height, double depth) {
        var values = new HashMap<>(Map.of(HYDROLOGY + "cut", cut, P + "coherent/height", height,
                P + "coherent/depth_blocks", depth, HYDROLOGY + "reference_cave_weight", 1.0));
        return evaluate(candidate.get(HydrologyCompiler.CAVE_WEIGHT), values);
    }

    private static double evaluate(JsonElement node, Map<String, Double> values) {
        if (node.isJsonPrimitive()) {
            if (node.getAsJsonPrimitive().isNumber()) return node.getAsDouble();
            String id = node.getAsString();
            Double saved = values.get(id);
            if (saved != null) return saved;
            double value = evaluate(candidate.get(path(id)), values);
            values.put(id, value);
            return value;
        }
        var object = node.getAsJsonObject();
        String type = object.get("type").getAsString();
        if (type.equals("minecraft:cache")) return evaluate(object.get("input"), values);
        double left = evaluate(object.get("left"), values);
        double right = evaluate(object.get("right"), values);
        return switch (type) {
            case "minecraft:add" -> left + right;
            case "minecraft:sub" -> left - right;
            case "minecraft:mul" -> left * right;
            case "minecraft:min" -> Math.min(left, right);
            case "minecraft:max" -> Math.max(left, right);
            default -> throw new AssertionError("Unexpected density node: " + type);
        };
    }
}
