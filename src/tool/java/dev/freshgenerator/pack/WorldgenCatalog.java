package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.io.IOException;
import java.util.*;

/** One public world, continually improved. Legacy keys are save-compatibility data only. */
public final class WorldgenCatalog {
    public static final String WORLD_ID = "freshgenerator:world";
    public static final String CURRENT_SETTINGS = "freshgenerator:highlands_v2";
    public static final String NORMAL_TAG = "data/minecraft/tags/worldgen/world_preset/normal.json";
    private WorldgenCatalog() { }
    public static Map<String, JsonElement> compile(VanillaAssets vanilla) throws IOException {
        Objects.requireNonNull(vanilla);
        Map<String, JsonElement> output = new TreeMap<>();
        output.putAll(new WorldgenCompiler(vanilla,"balanced_v1",TerrainProfile.BALANCED).compile());
        output.putAll(new WorldgenCompiler(vanilla,"highlands_v1",TerrainProfile.HIGHLANDS).compile());
        output.putAll(new NaturalWorldgenCompiler(vanilla,"balanced_v2",TerrainProfile.BALANCED).compile());
        output.putAll(new NaturalWorldgenCompiler(vanilla,"highlands_v2",TerrainProfile.HIGHLANDS).compile());
        CoherentSurfaceCompiler.apply(output);
        JsonElement current=Objects.requireNonNull(output.get(VanillaAssets.path("world_preset",CURRENT_SETTINGS)));
        output.put(VanillaAssets.path("world_preset",WORLD_ID),current.deepCopy());
        JsonObject visible=new JsonObject();visible.addProperty("replace",false);
        JsonArray values=new JsonArray();values.add(WORLD_ID);visible.add("values",values);
        output.put(NORMAL_TAG,visible);
        return Collections.unmodifiableMap(output);
    }
}
