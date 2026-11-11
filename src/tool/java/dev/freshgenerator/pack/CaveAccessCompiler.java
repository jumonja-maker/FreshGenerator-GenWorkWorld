package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import java.util.*;

/** A narrow final compiler layer. Keep terrain height, cave bodies, aquifers, biome climate,
 * materials, structure bearding and native carver definitions intact. Only the surface blend
 * weight changes. Debug entries also expose these exact samplers to the native carver hook,
 * including generation contexts without a WorldGenRegion.
 */
public final class CaveAccessCompiler {
    public static final String P="freshgenerator:highlands_v2/", ROOT="data/freshgenerator/worldgen/";
    public static final String WEIGHT=ROOT+"density_function/highlands_v2/coherent/cave_weight.json";
    public static final String SETTINGS_PATH=ROOT+"noise_settings/highlands_v2.json";
    public static final Set<String> REPLACED=Set.of(WEIGHT,SETTINGS_PATH);
    private static final Gson G=new Gson();
    private CaveAccessCompiler() { }
    public static void apply(Map<String,JsonElement> out) {
        put(out,"original_weight",Objects.requireNonNull(out.get(WEIGHT)).deepCopy());
        String noise=P+"cave_access/entrances";
        if(out.putIfAbsent(VanillaAssets.path("noise",noise),object("base_octave",-7,"octave_count",2,"base_amplitude",1.0))!=null)
            throw new IllegalStateException("Cave entrance noise already defined");
        put(out,"entrance_noise",cache(object("type","minecraft:noise","noise",noise,"xz_scale",1.0,"y_scale",0.0)));
        put(out,"entrance_gate",cache(smooth(CaveAccessModel.GATE_START,CaveAccessModel.GATE_END,ref("entrance_noise"))));
        put(out,"land",cache(smooth(CaveAccessModel.LAND_START,CaveAccessModel.LAND_END,P+"coherent/height")));
        put(out,"roof_depth",cache(mul(CaveAccessModel.ROOF_DEPTH,mul(ref("land"),sub(1.0,ref("entrance_gate"))))));
        JsonElement deep=smooth(CaveAccessModel.FADE_START,CaveAccessModel.FADE_END,P+"coherent/depth_blocks");
        out.put(WEIGHT,mul(ref("original_weight"),sub(1.0,mul(mul(ref("land"),sub(1.0,ref("entrance_gate"))),sub(1.0,deep)))));
        JsonObject settings=Objects.requireNonNull(out.get(SETTINGS_PATH)).deepCopy().getAsJsonObject();
        JsonArray debug=settings.getAsJsonArray("debug_functions");
        debug.add(object("label",CaveAccessModel.HEIGHT_LABEL,"function",P+"coherent/height"));
        debug.add(object("label",CaveAccessModel.ROOF_LABEL,"function",ref("roof_depth")));
        out.put(SETTINGS_PATH,settings);
    }
    private static String ref(String name){return P+"cave_access/"+name;}
    private static void put(Map<String,JsonElement> out,String name,JsonElement value){
        String path=VanillaAssets.path("density_function",ref(name));
        if(out.putIfAbsent(path,value)!=null)throw new IllegalStateException("Duplicate cave access resource: "+path);
    }
    private static JsonElement smooth(float lo,float hi,String input){return G.toJsonTree(NaturalTerrainProgram.smootherstep(lo,hi,DensityExpr.ref(input)).json());}
    private static JsonObject object(Object... pairs){JsonObject o=new JsonObject();for(int i=0;i<pairs.length;i+=2)o.add((String)pairs[i],G.toJsonTree(pairs[i+1]));return o;}
    private static JsonObject cache(Object input){return object("type","minecraft:cache","input",input);}
    private static JsonObject mul(Object a,Object b){return object("type","minecraft:mul","left",a,"right",b);}
    private static JsonObject sub(Object a,Object b){return object("type","minecraft:sub","left",a,"right",b);}
}
