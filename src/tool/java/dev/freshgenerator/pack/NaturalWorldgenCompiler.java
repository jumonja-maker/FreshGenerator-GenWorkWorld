package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import java.io.IOException;
import java.util.*;

/** A separate compiler layer: the published v1 compiler and its resources remain unchanged. */
public final class NaturalWorldgenCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final VanillaAssets vanilla;
    private final String preset, prefix;
    private final TerrainProfile profile;

    public NaturalWorldgenCompiler(VanillaAssets vanilla, String preset, TerrainProfile profile) {
        this.vanilla = Objects.requireNonNull(vanilla);
        this.profile = Objects.requireNonNull(profile);
        if (preset == null || !preset.matches("[a-z0-9_]+_v2")) throw new IllegalArgumentException("Expected a v2 preset");
        this.preset = preset; this.prefix = "freshgenerator:" + preset + "/";
    }
    public Map<String, JsonElement> compile() throws IOException {
        // First resolve the native 26.3 pipeline using the frozen adapter. Rename only our private IDs.
        String staging = "natural_source_v1";
        Map<String, JsonElement> out = new TreeMap<>();
        for (var entry : new WorldgenCompiler(vanilla, staging, profile).compile().entrySet()) {
            String path = entry.getKey().replace(staging, preset);
            out.put(path, JsonParser.parseString(GSON.toJson(entry.getValue()).replace(staging, preset)));
        }
        NaturalTerrainProgram program = NaturalTerrainProgram.compile(prefix, profile);
        program.nodes().forEach((name, expr) -> out.put(path("density_function", name),
                object("type", "minecraft:cache", "input", expr.json())));
        addNoise(out, "massif_noise", "massif_variation", -9, 3, profile.terrainScale());
        addNoise(out, "spur_noise", "slope_spurs", -6, 3, profile.terrainScale());

        String jagged = path("density_function", "jaggedness");
        JsonElement originalJagged = Objects.requireNonNull(out.get(jagged), "Native jaggedness missing");
        out.put(path("density_function", "base_jaggedness"), originalJagged);
        out.put(jagged, object("type", "minecraft:cache", "input", object("type", "minecraft:mul",
                "left", prefix + "base_jaggedness", "right", prefix + "jaggedness_weight")));

        // The native 240..256 upper fade can flatten tall uplifted summits. Move ONLY the two
        // matching top gradients, in both the real density and its preliminary-surface estimate.
        int gradients = 0;
        for (String name : List.of("final_density", "preliminary_surface_level"))
            gradients += moveTopFade(out.get(path("density_function", name)));
        if (gradients != 2) throw new IllegalStateException("Expected two audited top gradients, got " + gradients);

        addAlpineSurfaceTransition(out);
        JsonObject metadata = GSON.toJsonTree(profile).getAsJsonObject();
        metadata.addProperty("terrainAlgorithm", 2);
        metadata.addProperty("upperFadeStart", 288);
        metadata.addProperty("upperFadeEnd", 312);
        metadata.addProperty("placementPolicy", "native; no rarity controls");
        out.put("data/freshgenerator/terrain_profile/" + preset + ".json", metadata);
        return Collections.unmodifiableMap(out);
    }
    private void addNoise(Map<String, JsonElement> out, String functionName, String noiseName,
            int octave, int octaves, double scale) {
        String id = prefix + noiseName;
        out.put(VanillaAssets.path("noise", id), object("base_octave", octave,
                "octave_count", octaves, "base_amplitude", 1.0));
        out.put(path("density_function", functionName), object("type", "minecraft:cache", "input",
                object("type", "minecraft:noise", "noise", id, "xz_scale", 1.0 / scale, "y_scale", 0.0)));
    }
    private static int moveTopFade(JsonElement value) {
        int count = 0;
        if (value != null && value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.has("type") && o.get("type").getAsString().equals("minecraft:gradient")
                    && o.has("axis") && o.get("axis").getAsString().equals("y")
                    && o.get("from_coordinate").getAsInt() == 240 && o.get("to_coordinate").getAsInt() == 256
                    && o.get("from_value").getAsFloat() == 1f && o.get("to_value").getAsFloat() == 0f) {
                o.addProperty("from_coordinate", 288); o.addProperty("to_coordinate", 312); count++;
            }
            for (JsonElement child : o.asMap().values()) count += moveTopFade(child);
        } else if (value != null && value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) count += moveTopFade(child);
        return count;
    }
    private void addAlpineSurfaceTransition(Map<String, JsonElement> out) {
        // Limited to exposed alpine meadow/windswept-forest surfaces. No desert, mushroom,
        // wetland or underwater palette replacement; no biome ID, vegetation or structure rewrite.
        String defaultPath = path("material_rule", "biome_surface/default");
        JsonElement original = Objects.requireNonNull(out.get(defaultPath), "Native default surface missing");
        JsonObject biome = object("type", "minecraft:biome", "biome_is",
                List.of("minecraft:meadow", "minecraft:windswept_forest"));
        JsonObject rock = condition(object("type", "minecraft:steep"),
                condition(aboveTransition("scree", 128, 184), block("minecraft:stone")));
        JsonObject snow = condition(object("type", "minecraft:temperature"),
                condition(aboveTransition("snowline", 168, 224), block("minecraft:snow_block")));
        JsonObject transition = condition(biome, condition(new JsonPrimitive("minecraft:not_underwater"),
                object("type", "minecraft:sequence", "sequence", List.of(rock, snow))));
        out.put(defaultPath, object("type", "minecraft:sequence", "sequence", List.of(transition, original)));
    }
    private JsonObject aboveTransition(String name, int low, int high) {
        return object("type", "minecraft:not", "invert", object("type", "minecraft:vertical_gradient",
                "random_name", prefix + name, "true_at_and_below", Map.of("absolute", low),
                "false_at_and_above", Map.of("absolute", high)));
    }
    private String path(String registry, String local) { return VanillaAssets.path(registry, prefix + local); }
    private static JsonObject block(String id) { return object("type", "minecraft:block", "result_state", id); }
    private static JsonObject condition(JsonElement test, JsonElement rule) {
        return object("type", "minecraft:condition", "if_true", test, "then_run", rule);
    }
    private static JsonObject object(Object... values) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < values.length; i += 2) result.add((String) values[i], GSON.toJsonTree(values[i+1]));
        return result;
    }
}
