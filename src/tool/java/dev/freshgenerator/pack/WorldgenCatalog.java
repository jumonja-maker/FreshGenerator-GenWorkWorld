package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.io.IOException;
import java.util.*;

/**
 * One public world type. Historical registry keys are save-compatibility data,
 * never additional choices in the world creation menu.
 */
public final class WorldgenCatalog {
    public static final String WORLD_ID = "freshgenerator:world";
    // Internal persisted settings, deliberately reused to retain the audited terrain/noise seeds.
    public static final String CURRENT_SETTINGS = "freshgenerator:highlands_v2";
    public static final String NORMAL_TAG = "data/minecraft/tags/worldgen/world_preset/normal.json";

    private WorldgenCatalog() { }

    public static Map<String, JsonElement> compile(VanillaAssets vanilla) throws IOException {
        Objects.requireNonNull(vanilla);
        Map<String, JsonElement> output = new TreeMap<>();
        // Do not remove or mutate these resources: existing saves reference their settings directly.
        output.putAll(new WorldgenCompiler(vanilla, "balanced_v1", TerrainProfile.BALANCED).compile());
        output.putAll(new WorldgenCompiler(vanilla, "highlands_v1", TerrainProfile.HIGHLANDS).compile());
        output.putAll(new NaturalWorldgenCompiler(vanilla, "balanced_v2", TerrainProfile.BALANCED).compile());
        output.putAll(new NaturalWorldgenCompiler(vanilla, "highlands_v2", TerrainProfile.HIGHLANDS).compile());

        // A single complete Overworld: mountains are locally selected by terrain/climate masks;
        // oceans, coasts, plains and the native biome/structure rules remain in the same world.
        JsonElement current = Objects.requireNonNull(output.get(
                VanillaAssets.path("world_preset", CURRENT_SETTINGS)), "Current world definition missing");
        output.put(VanillaAssets.path("world_preset", WORLD_ID), current.deepCopy());
        JsonObject visible = new JsonObject();
        visible.addProperty("replace", false); // Never remove vanilla or another mod's menu entries.
        JsonArray values = new JsonArray();
        values.add(WORLD_ID);
        visible.add("values", values);
        output.put(NORMAL_TAG, visible);
        return Collections.unmodifiableMap(output);
    }
}
