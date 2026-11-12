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
        JsonObject settings=Objects.requireNonNull(out.get(SETTINGS)).deepCopy().getAsJsonObject();
        JsonObject router=settings.getAsJsonObject("noise_router");
        JsonElement deep=G.toJsonTree(NaturalTerrainProgram.smootherstep(DEEP_FADE_START,DEEP_FADE_END,
                DensityExpr.ref(PREFIX+"coherent/depth_blocks")).json());
        for(String name:List.of("temperature","vegetation")) {
            JsonElement original=Objects.requireNonNull(out.get(path(name)),name).deepCopy();
            String saved="regions/original_"+name,selected="regions/biome_"+name;
            if(out.putIfAbsent(path(saved),original)!=null)throw new IllegalStateException("Regional compiler applied twice");
            JsonElement regional=original.deepCopy();
            if(rescale(regional)!=1)throw new IllegalStateException("Unexpected native climate graph: "+name);
            out.put(path(name),regional);
            out.put(path(selected),object("type","minecraft:lerp","alpha",deep.deepCopy(),
                    "first",PREFIX+name,"second",PREFIX+saved));
            if(!router.get(name).getAsString().equals(PREFIX+name))throw new IllegalStateException("Unexpected climate routing");
            router.addProperty(name,PREFIX+selected);
            // Debug values should describe the actual selected climate, including deep fallback.
            for(JsonElement e:settings.getAsJsonArray("debug_functions")) {
                JsonObject entry=e.getAsJsonObject();
                if(entry.get("function").isJsonPrimitive()&&entry.get("function").getAsString().equals(PREFIX+name))
                    entry.addProperty("function",PREFIX+selected);
            }
        }
        out.put(SETTINGS,settings);
    }
    private static int rescale(JsonElement element) {
        int count=0;
        if(element.isJsonArray())for(JsonElement e:element.getAsJsonArray())count+=rescale(e);
        else if(element.isJsonObject()) {
            JsonObject o=element.getAsJsonObject();
            if(o.has("type")&&o.get("type").getAsString().equals("minecraft:noise")) {
                if(o.get("y_scale").getAsDouble()!=0)throw new IllegalStateException("Surface climate must be 2-D");
                o.addProperty("xz_scale",o.get("xz_scale").getAsDouble()/HORIZONTAL_SCALE);
                // Without this, fast native coordinate shifts remain oversized relative to the
                // enlarged climate field and can reintroduce tiny fragments at its boundaries.
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
