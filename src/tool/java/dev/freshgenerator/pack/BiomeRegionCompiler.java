package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/** Regional climate, NOT a uniform zoom of terrain or a majority vote over biome IDs.
 * Wide temperature/moisture domains coexist with original local rivers, coasts and relief.
 * Native climate thresholds, rare-biome entries, features and structure tables are untouched.
 */
public final class BiomeRegionCompiler {
    public static final String PREFIX="freshgenerator:highlands_v2/";
    public static final String ROOT="data/freshgenerator/worldgen/";
    public static final double HORIZONTAL_SCALE=3.0;
    public static final float DEEP_FADE_START=24, DEEP_FADE_END=64;
    public static final String SETTINGS=ROOT+"noise_settings/highlands_v2.json";
    public static final Set<String> REPLACED=Set.of(SETTINGS,path("temperature"),path("vegetation"));
    private static final Gson G=new Gson();
    private BiomeRegionCompiler() { }

    public static void apply(Map<String,JsonElement> out) {
        Objects.requireNonNull(out);
        // Validate and construct the entire layer before publishing any entries. A broken
        // second climate/router must not leave the first climate half-installed.
        Map<String,JsonElement> staged=new TreeMap<>(out);
        applyStaged(staged);
        out.putAll(staged);
    }
    private static void applyStaged(Map<String,JsonElement> out) {
        // Do not silently replace an existing selected-climate definition, including
        // null-valued entries. Check the complete namespace before constructing either axis.
        for(String name:List.of("temperature","vegetation"))
            for(String role:List.of("regions/original_","regions/biome_"))
                if(out.containsKey(path(role+name)))
                    throw new IllegalStateException("Regional output already exists: "+path(role+name));
        JsonObject settings=Objects.requireNonNull(out.get(SETTINGS)).deepCopy().getAsJsonObject();
        JsonObject router=settings.getAsJsonObject("noise_router");
        JsonElement deep=G.toJsonTree(NaturalTerrainProgram.smootherstep(DEEP_FADE_START,DEEP_FADE_END,
                DensityExpr.ref(PREFIX+"coherent/depth_blocks")).json());
        for(String name:List.of("temperature","vegetation")) {
            JsonElement original=Objects.requireNonNull(out.get(path(name)),name).deepCopy();
            String saved="regions/original_"+name,selected="regions/biome_"+name;
            out.put(path(saved),original);
            JsonElement regional=original.deepCopy();
            if(rescale(regional)!=1)throw new IllegalStateException("Unexpected native climate graph: "+name);
            out.put(path(name),regional);
            out.put(path(selected),object("type","minecraft:lerp","alpha",deep.deepCopy(),
                    "first",PREFIX+name,"second",PREFIX+saved));
            if(!router.get(name).getAsString().equals(PREFIX+name))throw new IllegalStateException("Unexpected climate routing");
            router.addProperty(name,PREFIX+selected);
            for(JsonElement e:settings.getAsJsonArray("debug_functions")) {
                JsonObject entry=e.getAsJsonObject();
                if(entry.get("function").isJsonPrimitive()&&entry.get("function").getAsString().equals(PREFIX+name))
                    entry.addProperty("function",PREFIX+selected);
            }
        }
        out.put(SETTINGS,settings);
    }
    private static double finiteNumber(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalStateException("Climate "+key+" must be a JSON number");
        double number=value.getAsDouble();
        if(!Double.isFinite(number))throw new IllegalStateException("Nonfinite climate "+key);
        return number;
    }
    private static int rescale(JsonElement element) {
        int count=0;
        if(element.isJsonArray())for(JsonElement e:element.getAsJsonArray())count+=rescale(e);
        else if(element.isJsonObject()) {
            JsonObject o=element.getAsJsonObject();
            if(o.has("type")&&o.get("type").getAsString().equals("minecraft:noise")) {
                if(finiteNumber(o,"y_scale")!=0)throw new IllegalStateException("Surface climate must be 2-D");
                double scaled=finiteNumber(o,"xz_scale")/HORIZONTAL_SCALE;
                if(!(scaled>0) || !Double.isFinite(scaled))
                    throw new IllegalStateException("Climate horizontal scale must remain finite and positive");
                o.addProperty("xz_scale",scaled);
                for(String axis:List.of("shift_x","shift_z"))if(o.has(axis))
                    o.add(axis,object("type","minecraft:mul","left",1.0/HORIZONTAL_SCALE,"right",o.get(axis).deepCopy()));
                return 1;
            }
            for(JsonElement e:o.asMap().values())count+=rescale(e);
        }
        return count;
    }
    public static String path(String local){return VanillaAssets.path("density_function",PREFIX+local);}
    private static JsonObject object(Object... pairs){JsonObject out=new JsonObject();for(int i=0;i<pairs.length;i+=2)out.add((String)pairs[i],G.toJsonTree(pairs[i+1]));return out;}
}
