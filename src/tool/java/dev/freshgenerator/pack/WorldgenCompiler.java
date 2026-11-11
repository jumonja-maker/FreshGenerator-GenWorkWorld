package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import java.io.*;
import java.util.*;

/** Compiles a per-world immutable graph; never replaces the normal Overworld or placement registries. */
public final class WorldgenCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> LOCAL_REGISTRIES = List.of("density_function", "material_rule", "material_condition");
    private final VanillaAssets vanilla;
    private final TerrainProfile profile;
    private final String preset, prefix;
    private final Map<String, JsonElement> output = new TreeMap<>();
    private final Set<String> resolving = new HashSet<>();
    public WorldgenCompiler(VanillaAssets vanilla, String preset, TerrainProfile profile) {
        this.vanilla = Objects.requireNonNull(vanilla); this.profile = Objects.requireNonNull(profile);
        if (!preset.matches("[a-z0-9_]+_v1")) throw new IllegalArgumentException("Preset must have an explicit v1 schema");
        this.preset = preset; this.prefix = "freshgenerator:" + preset + "/";
    }
    public Map<String, JsonElement> compile() throws IOException {
        if (!output.isEmpty()) throw new IllegalStateException("Compiler instances are single use");
        verifyNativeContract();
        TerrainProgram program = TerrainProgram.compile(prefix, profile);
        program.nodes().forEach((name, expr) -> output.put(VanillaAssets.path("density_function", prefix + name), GSON.toJsonTree(expr.json())));
        JsonObject settings = rewrite(vanilla.read("data/minecraft/worldgen/noise_settings/overworld.json")).getAsJsonObject();
        settings.addProperty("material_rule", cloneAsset("material_rule", "minecraft:overworld"));
        JsonArray debug = settings.getAsJsonArray("debug_functions");
        JsonObject elevation = new JsonObject(); elevation.addProperty("label", "FG height delta");
        elevation.addProperty("function", prefix + "height_delta"); debug.add(elevation);
        output.put(VanillaAssets.path("noise_settings", "freshgenerator:" + preset), settings);
        JsonObject world = vanilla.read("data/minecraft/worldgen/world_preset/normal.json").getAsJsonObject();
        world.getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator")
                .addProperty("settings", "freshgenerator:" + preset);
        output.put(VanillaAssets.path("world_preset", "freshgenerator:" + preset), world);
        output.put("data/freshgenerator/terrain_profile/" + preset + ".json", GSON.toJsonTree(profile));
        return Collections.unmodifiableMap(output);
    }
    private void verifyNativeContract() throws IOException {
        JsonObject version = vanilla.read("version.json").getAsJsonObject();
        if (!"26.3".equals(version.get("id").getAsString())) throw new IllegalStateException("This adapter is for 26.3 only");
        JsonObject depth = vanilla.read("data/minecraft/worldgen/density_function/overworld/depth.json").getAsJsonObject();
        JsonObject gradient = depth.getAsJsonObject("left");
        if (!"minecraft:gradient".equals(gradient.get("type").getAsString())
                || gradient.get("from_coordinate").getAsInt() != -64 || gradient.get("to_coordinate").getAsInt() != 320
                || gradient.get("from_value").getAsFloat() != 1.5f || gradient.get("to_value").getAsFloat() != -1.5f)
            throw new IllegalStateException("Native depth gradient changed; adapter needs review");
    }
    private String cloneAsset(String registry, String originalId) throws IOException {
        String local = originalId.equals("minecraft:overworld") ? "material_root" : originalId.substring("minecraft:overworld/".length());
        String id = prefix + local, target = VanillaAssets.path(registry, id);
        if (output.containsKey(target)) return id;
        if (!resolving.add(target)) throw new IllegalStateException("Unexpected cyclic registry graph: " + target);
        try {
            JsonElement result = rewrite(vanilla.read(VanillaAssets.path(registry, originalId)));
            if (registry.equals("density_function")) {
                double scale = switch (local) {
                    case "continents" -> profile.continentScale();
                    case "temperature", "vegetation" -> profile.biomeScale();
                    case "erosion", "ridges" -> profile.terrainScale();
                    default -> 1;
                };
                if (scale != 1) {
                    int changed = scaleNoise(result, scale);
                    if (changed != 1) throw new IllegalStateException("Unexpected noise layout for " + local + ": " + changed);
                }
                if (local.equals("offset") && (profile.mountainUplift() != 0 || profile.oceanDeepening() != 0)) {
                    output.put(VanillaAssets.path(registry, prefix + "base_offset"), result);
                    JsonObject blend = new JsonObject(); blend.addProperty("type", "minecraft:blend_alpha");
                    JsonObject delta = binary("mul", blend, new JsonPrimitive(prefix + "offset_delta"));
                    result = binary("add", new JsonPrimitive(prefix + "base_offset"), delta);
                }
            }
            output.put(target, result); return id;
        } finally { resolving.remove(target); }
    }
    private JsonElement rewrite(JsonElement value) throws IOException {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return new JsonPrimitive(rewriteId(value.getAsString()));
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(rewrite(item));
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) result.add(rewriteId(entry.getKey()), rewrite(entry.getValue()));
            return result;
        }
        return value.deepCopy();
    }
    private String rewriteId(String id) throws IOException {
        if (!id.startsWith("minecraft:overworld/")) return id;
        String match = null;
        for (String registry : LOCAL_REGISTRIES) if (vanilla.exists(VanillaAssets.path(registry, id))) {
            if (match != null) throw new IllegalStateException("Ambiguous native reference: " + id);
            match = registry;
        }
        if (match == null) throw new IllegalStateException("Unknown native Overworld reference: " + id);
        return cloneAsset(match, id);
    }
    private static int scaleNoise(JsonElement value, double scale) {
        int count = 0;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("type") && "minecraft:noise".equals(object.get("type").getAsString())) {
                object.addProperty("xz_scale", object.get("xz_scale").getAsDouble() / scale); count++;
            }
            for (JsonElement child : object.asMap().values()) count += scaleNoise(child, scale);
        } else if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) count += scaleNoise(child, scale);
        return count;
    }
    private static JsonObject binary(String type, JsonElement left, JsonElement right) {
        JsonObject result = new JsonObject(); result.addProperty("type", "minecraft:" + type);
        result.add("left", left); result.add("right", right); return result;
    }
}
