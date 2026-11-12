package dev.freshgenerator.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static dev.freshgenerator.core.DensityExpr.*;

/** Connects the shared drainage profile to the terrain envelope and native fill. */
public final class HydrologyCompiler {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = CoherentSurfaceCompiler.PREFIX;
    private static final String COHERENT = PREFIX + "coherent/";
    private static final String HYDROLOGY = PREFIX + "coherent/hydrology/";
    private static final String MODEL = HYDROLOGY + "model";
    public static final String HEIGHT = path(COHERENT + "height");
    public static final String CAVE_WEIGHT = path(COHERENT + "cave_weight");
    public static final String BIOME_DEPTH = path(PREFIX + "ecotone/biome_depth");
    public static final String BIOME_TEMPERATURE = path(PREFIX + "regions/biome_temperature");
    public static final String BIOME_MOISTURE = path(PREFIX + "regions/biome_vegetation");

    private HydrologyCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        JsonElement original = Objects.requireNonNull(out.get(HEIGHT), "Missing coherent height");
        JsonElement originalCaves = Objects.requireNonNull(out.get(CAVE_WEIGHT), "Missing cave envelope");
        JsonObject settings = Objects.requireNonNull(out.get(BiomeRegionCompiler.SETTINGS), "Missing Overworld settings")
                .getAsJsonObject().deepCopy();
        int sea = settings.get("sea_level").getAsInt();
        var debug = settings.getAsJsonArray("debug_functions");
        JsonObject entry = new JsonObject();
        entry.addProperty("label", "FreshGenerator drainage");
        entry.addProperty("function", MODEL);
        debug.add(entry);
        DensityExpr base = ref(HYDROLOGY + "base_height");
        DensityExpr cut = ref(HYDROLOGY + "cut");

        Map<String, JsonElement> layer = new LinkedHashMap<>();
        layer.put(path(HYDROLOGY + "base_height"), original.deepCopy());
        layer.put(path(HYDROLOGY + "base_depth"), GSON.toJsonTree(sub(base, ref("minecraft:y")).json()));
        layer.put(path(HYDROLOGY + "base_cave_weight"), originalCaves.deepCopy());
        JsonObject exterior = original.getAsJsonObject().getAsJsonObject("input");
        if (!"minecraft:interpolated".equals(exterior.get("type").getAsString())) {
            throw new IllegalStateException("Expected the native interpolated coherent exterior");
        }
        int heightGrid = exterior.get("cell_size_xz").getAsInt();
        JsonElement drainage = GSON.toJsonTree(Map.of("type", "freshgenerator:drainage",
                "terrain", HYDROLOGY + "base_height", "continents", PREFIX + "continents",
                "moisture", PREFIX + "vegetation", "sea_level", sea, "height_grid", heightGrid));
        layer.put(path(MODEL), cache(drainage));
        layer.put(path(HYDROLOGY + "cut"), cache(GSON.toJsonTree(Map.of("type", "minecraft:mul",
                "left", Map.of("type", "minecraft:blend_alpha"), "right", MODEL))));
        JsonElement height = cache(sub(base, cut));

        // Keep the newly cut bed supported by solid ground. Existing terrain
        // and the native cave signal twelve blocks below the new bed keep the old blend.
        DensityExpr newBed = clamp01(mul(constant(4), cut));
        DensityExpr shallow = fade(5, 12, ref(COHERENT + "depth_blocks"));
        JsonElement caveWeight = GSON.toJsonTree(mul(ref(HYDROLOGY + "base_cave_weight"),
                sub(constant(1), mul(newBed, shallow))).json());

        for (String name : layer.keySet()) {
            if (out.containsKey(name)) throw new IllegalStateException("Duplicate hydrology resource: " + name);
        }
        // Climate keeps its published vertical reference. River biomes are selected
        // only in the surface band by the physical water profile.
        var climate = new LinkedHashMap<String, JsonElement>();
        for (String name : new String[]{BIOME_DEPTH, BIOME_TEMPERATURE, BIOME_MOISTURE}) {
            JsonElement graph = Objects.requireNonNull(out.get(name), name).deepCopy();
            rewriteDepth(graph);
            climate.put(name, graph);
        }
        out.putAll(layer);
        out.putAll(climate);
        out.put(HEIGHT, height);
        out.put(CAVE_WEIGHT, caveWeight);
        out.put(BiomeRegionCompiler.SETTINGS, settings);
    }

    private static void rewriteDepth(JsonElement graph) {
        if (graph.isJsonObject()) {
            var object = graph.getAsJsonObject();
            object.asMap().replaceAll((key, value) -> {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && value.getAsString().equals(COHERENT + "depth_blocks")) {
                    return new JsonPrimitive(HYDROLOGY + "base_depth");
                }
                rewriteDepth(value);
                return value;
            });
        } else if (graph.isJsonArray()) {
            for (JsonElement element : graph.getAsJsonArray()) rewriteDepth(element);
        }
    }

    private static DensityExpr smooth(double from, double to, DensityExpr input) {
        return NaturalTerrainProgram.smootherstep(from, to, input);
    }

    private static DensityExpr fade(double from, double to, DensityExpr input) {
        return sub(constant(1), smooth(from, to, input));
    }

    private static String path(String id) {
        return VanillaAssets.path("density_function", id);
    }

    private static JsonObject cache(DensityExpr expression) {
        return cache(GSON.toJsonTree(expression.json()));
    }

    private static JsonObject cache(JsonElement value) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "minecraft:cache");
        node.add("input", value);
        return node;
    }
}
