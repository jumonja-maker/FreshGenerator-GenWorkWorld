package dev.freshgenerator.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static dev.freshgenerator.core.DensityExpr.*;

/** Shapes lowland riverbeds and isolated lake basins before native aquifers fill them. */
public final class HydrologyCompiler {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = CoherentSurfaceCompiler.PREFIX;
    private static final String COHERENT = PREFIX + "coherent/";
    private static final String HYDROLOGY = PREFIX + "coherent/hydrology/";
    public static final String HEIGHT = path(COHERENT + "height");
    public static final String CAVE_WEIGHT = path(COHERENT + "cave_weight");

    private HydrologyCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        JsonElement original = Objects.requireNonNull(out.get(HEIGHT), "Missing coherent height");
        JsonElement originalCaves = Objects.requireNonNull(out.get(CAVE_WEIGHT), "Missing cave envelope");
        int sea = Objects.requireNonNull(out.get(BiomeRegionCompiler.SETTINGS), "Missing Overworld settings")
                .getAsJsonObject().get("sea_level").getAsInt();
        DensityExpr base = ref(HYDROLOGY + "base_height");
        DensityExpr cut = ref(HYDROLOGY + "cut");

        Map<String, JsonElement> layer = new LinkedHashMap<>();
        layer.put(path(HYDROLOGY + "base_height"), original.deepCopy());
        layer.put(path(HYDROLOGY + "base_cave_weight"), originalCaves.deepCopy());
        JsonElement drainage = GSON.toJsonTree(Map.of("type", "freshgenerator:drainage",
                "terrain", HYDROLOGY + "base_height", "continents", PREFIX + "continents", "sea_level", sea));
        layer.put(path(HYDROLOGY + "cut"), cache(GSON.toJsonTree(Map.of("type", "minecraft:mul",
                "left", Map.of("type", "minecraft:blend_alpha"), "right", drainage))));
        JsonElement height = cache(sub(base, cut));

        // Keep the newly cut bed supported by solid ground. Existing terrain
        // and the native cave signal twelve blocks below the new bed keep the old blend.
        DensityExpr surface = ref(COHERENT + "height");
        DensityExpr newBed = mul(smooth(0.5, 2, cut), fade(sea - 1.5, sea - 1, surface));
        DensityExpr shallow = fade(5, 12, ref(COHERENT + "depth_blocks"));
        JsonElement caveWeight = GSON.toJsonTree(mul(ref(HYDROLOGY + "base_cave_weight"),
                sub(constant(1), mul(newBed, shallow))).json());

        for (String name : layer.keySet()) {
            if (out.containsKey(name)) throw new IllegalStateException("Duplicate hydrology resource: " + name);
        }
        out.putAll(layer);
        out.put(HEIGHT, height);
        out.put(CAVE_WEIGHT, caveWeight);
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
