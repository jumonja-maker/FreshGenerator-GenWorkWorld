package dev.freshgenerator.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.CaveAccessModel;
import dev.freshgenerator.core.NaturalTerrainProgram;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
    public static final String PRELIMINARY = path(PREFIX + "preliminary_surface_level");
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
        // Copy the dependency cone, not just the root JSON: otherwise references
        // to coherent/height silently move the old caves and underground aquifers.
        var frozen = new HashMap<String, JsonElement>();
        layer.put(path(HYDROLOGY + "reference_cave_weight"),
                freeze(originalCaves, out, frozen, new HashSet<>()));
        layer.put(path(HYDROLOGY + "reference_roof_depth"),
                freeze(Objects.requireNonNull(out.get(path(PREFIX + "cave_access/roof_depth"))),
                        out, frozen, new HashSet<>()));
        JsonElement preliminary = freeze(Objects.requireNonNull(out.get(PRELIMINARY)),
                out, frozen, new HashSet<>());
        for (JsonElement item : debug) {
            JsonObject binding = item.getAsJsonObject();
            String label = binding.get("label").getAsString();
            if (label.equals(CaveAccessModel.HEIGHT_LABEL))
                binding.addProperty("function", HYDROLOGY + "base_height");
            if (label.equals(CaveAccessModel.ROOF_LABEL))
                binding.addProperty("function", HYDROLOGY + "reference_roof_depth");
        }
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

        // A raised bank can cross the mountain threshold or put the old cave
        // transition closer to the surface. Keep the stronger of the original
        // cave protection and the mountain roof at the final elevation.
        DensityExpr depth = ref(COHERENT + "depth_blocks");
        DensityExpr mountainRoof = mul(ref(COHERENT + "mountain_protection"),
                fade(CoherentSurfaceCompiler.MOUNTAIN_ROOF, CoherentSurfaceCompiler.FULL_CAVE_DEPTH, depth));
        DensityExpr caveEnvelope = new Binary(Op.MIN, ref(HYDROLOGY + "reference_cave_weight"),
                sub(constant(1), mountainRoof));

        // Seal a separate, shallower band beneath cut beds and raised banks.
        DensityExpr bankOrBed = new Binary(Op.MAX, cut, mul(constant(-1), cut));
        DensityExpr newBed = clamp01(mul(constant(4), bankOrBed));
        JsonElement caveWeight = GSON.toJsonTree(mul(caveEnvelope,
                sub(constant(1), mul(newBed, fade(5, 12, depth)))).json());

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
        out.put(PRELIMINARY, preliminary);
        out.put(BiomeRegionCompiler.SETTINGS, settings);
    }

    /** Inline only changed dependencies while keeping all native noise leaves intact. */
    private static JsonElement freeze(JsonElement node, Map<String, JsonElement> catalog,
                                      Map<String, JsonElement> memo, Set<String> active) {
        if (node.isJsonPrimitive()) {
            if (!node.getAsJsonPrimitive().isString()) return node.deepCopy();
            String id = node.getAsString();
            if (id.equals(COHERENT + "height")) return new JsonPrimitive(HYDROLOGY + "base_height");
            if (id.equals(COHERENT + "depth_blocks")) return new JsonPrimitive(HYDROLOGY + "base_depth");
            if (!id.startsWith(PREFIX) || !catalog.containsKey(path(id))) return node.deepCopy();
            JsonElement cached = memo.get(id);
            if (cached != null) return cached.deepCopy();
            if (!active.add(id)) throw new IllegalStateException("Cyclic hydrology reference: " + id);
            JsonElement original = catalog.get(path(id));
            JsonElement rewritten = freeze(original, catalog, memo, active);
            active.remove(id);
            JsonElement result = original.equals(rewritten) ? node.deepCopy() : rewritten;
            memo.put(id, result);
            return result.deepCopy();
        }
        if (node.isJsonArray()) {
            var array = new com.google.gson.JsonArray();
            for (JsonElement item : node.getAsJsonArray()) array.add(freeze(item, catalog, memo, active));
            return array;
        }
        JsonObject result = new JsonObject();
        node.getAsJsonObject().asMap().forEach((key, value) -> result.add(key, freeze(value, catalog, memo, active)));
        return result;
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
