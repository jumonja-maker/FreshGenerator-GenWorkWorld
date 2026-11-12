package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/**
 * Corrects the ACTIVE world's exterior, not biome/structure placement. The previous
 * 3-D sloped-cheese field can re-enter solid above an air interval; raising it does
 * not make a supported mountain. Use a bounded 2-D envelope for the exterior and
 * retain native caves deeper inside. No world-load deletion, no neighbour writes.
 */
public final class CoherentSurfaceCompiler {
    static final float MOUNTAIN_START = 64, MOUNTAIN_FULL = 112;
    static final float MOUNTAIN_ROOF = 18, FULL_CAVE_DEPTH = 42;
    public static final String PREFIX = "freshgenerator:highlands_v2/";
    public static final String ROOT = "data/freshgenerator/worldgen/density_function/highlands_v2/";
    public static final Set<String> REPLACED = Set.of(ROOT + "final_density.json", ROOT + "preliminary_surface_level.json");
    private static final Gson GSON = new Gson();
    private CoherentSurfaceCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        JsonObject original = Objects.requireNonNull(out.get(ROOT + "final_density.json")).getAsJsonObject();
        if (!"minecraft:add".equals(original.get("type").getAsString())
                || !"minecraft:beardifier".equals(original.getAsJsonObject("right").get("type").getAsString()))
            throw new IllegalStateException("Expected audited final-density structure adjustment");
        JsonElement beard = original.get("right").deepCopy();
        put(out, "native_cave_body", original.get("left").deepCopy());
        // The original offset already has the continent/river masks and mountain uplift.
        // Low-amplitude 2-D detail cannot make a second solid interval above the surface.
        put(out, "raw_height", cache(add(add(128.0, mul(PREFIX + "offset", 128.0)),
                mul(clamp(PREFIX + "spur_noise", -1, 1), 3.0))));
        put(out, "height_excess", cache(max(sub(ref("raw_height"), 256.0), 0.0)));
        // C1 soft ceiling approaching 304, not a flat cut at a fixed mountain altitude.
        put(out, "limited_height", cache(max(-48.0, add(min(ref("raw_height"), 256.0),
                mul(48.0, div(ref("height_excess"), add(48.0, ref("height_excess"))))))));
        put(out, "height", cache(object("type", "minecraft:interpolated", "cell_size_xz", 32,
                "cell_size_y", 1, "input", ref("limited_height"))));
        put(out, "depth_blocks", sub(ref("height"), "minecraft:y"));
        put(out, "surface_density", object("type", "minecraft:squeeze", "input", mul(ref("depth_blocks"), 0.08)));
        // Surface openings in lowlands remain; elevated slopes gain a continuous roof.
        // At 42 blocks below the envelope native caves regain their full influence.
        put(out, "mountain_protection", cache(smooth(MOUNTAIN_START, MOUNTAIN_FULL, ref("height"))));
        put(out, "cave_weight", sub(1.0, mul(ref("mountain_protection"),
                sub(1.0, smooth(MOUNTAIN_ROOF, FULL_CAVE_DEPTH, ref("depth_blocks"))))));
        JsonElement body = min(ref("surface_density"), object("type", "minecraft:lerp",
                "alpha", ref("cave_weight"), "first", ref("surface_density"), "second", ref("native_cave_body")));
        // Honour native blend data where present. Beardifier remains outside the cap:
        // structure foundations are not clipped to the unmodified landscape height.
        JsonElement blended = object("type", "minecraft:lerp", "alpha", object("type", "minecraft:blend_alpha"),
                "first", ref("native_cave_body"), "second", body);
        out.put(ROOT + "final_density.json", add(blended, beard));
        // Both surface materials and aquifers use the same exterior, with a conservative
        // underground margin. Do not leave the previous 3-D surface search behind.
        out.put(ROOT + "preliminary_surface_level.json", cache(sub(ref("height"), 8.0)));
    }
    public static float softHeight(float raw) {
        if (!Float.isFinite(raw)) throw new IllegalArgumentException("Non-finite height");
        float excess = Math.max(0f, raw - 256f);
        return Math.max(-48f, Math.min(raw, 256f) + 48f * (excess / (48f + excess)));
    }
    public static float smoothValue(float low, float high, float value) {
        float t = Math.clamp((value-low)/(high-low), 0f, 1f);
        return Math.clamp(t*t*t*(t*(t*6f-15f)+10f), 0f, 1f);
    }
    public static float caveWeight(float height, float y) {
        return 1f-smoothValue(64,112,height)*(1f-smoothValue(18,42,height-y));
    }
    private static String ref(String name) { return PREFIX + "coherent/" + name; }
    private static void put(Map<String, JsonElement> out, String name, JsonElement value) {
        String path = ROOT + "coherent/" + name + ".json";
        if (out.putIfAbsent(path, value) != null) throw new IllegalStateException("Duplicate correction: " + path);
    }
    private static JsonElement smooth(float lo,float hi,String value) {
        return GSON.toJsonTree(NaturalTerrainProgram.smootherstep(lo,hi,DensityExpr.ref(value)).json());
    }
    private static JsonObject object(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i=0;i<pairs.length;i+=2) o.add((String)pairs[i],GSON.toJsonTree(pairs[i+1]));
        return o;
    }
    private static JsonObject cache(Object x){return object("type","minecraft:cache","input",x);}
    private static JsonObject binary(String op,Object a,Object b){return object("type","minecraft:"+op,"left",a,"right",b);}
    private static JsonObject add(Object a,Object b){return binary("add",a,b);}
    private static JsonObject sub(Object a,Object b){return binary("sub",a,b);}
    private static JsonObject mul(Object a,Object b){return binary("mul",a,b);}
    private static JsonObject div(Object a,Object b){return binary("div",a,b);}
    private static JsonObject min(Object a,Object b){return binary("min",a,b);}
    private static JsonObject max(Object a,Object b){return binary("max",a,b);}
    private static JsonObject clamp(Object a,double lo,double hi){return object("type","minecraft:clamp","input",a,"min",lo,"max",hi);}
}
