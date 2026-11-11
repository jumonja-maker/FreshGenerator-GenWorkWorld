package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/** Climate/material correction only: preserve the successful exterior and aquifer graph byte-for-byte. */
public final class EcotoneCompiler {
    public static final String PREFIX="freshgenerator:highlands_v2/";
    public static final String ROOT="data/freshgenerator/worldgen/";
    public static final Set<String> REPLACED=Set.of(ROOT+"noise_settings/highlands_v2.json",
        ROOT+"material_rule/highlands_v2/biome_surface.json",ROOT+"material_rule/highlands_v2/under_biome_surface.json");
    private static final Gson G=new Gson();
    private EcotoneCompiler() { }
    public static void apply(Map<String,JsonElement> out) {
        String depth=PREFIX+"coherent/depth_blocks";
        JsonElement surface=mul(add(min(depth,0),max(sub(depth,24),0)),1.0/128.0);
        JsonElement w=G.toJsonTree(NaturalTerrainProgram.smootherstep(40,88,DensityExpr.ref(depth)).json());
        String id=PREFIX+"ecotone/biome_depth";
        out.put(VanillaAssets.path("density_function",id),object("type","minecraft:lerp","alpha",w,
            "first",surface,"second",PREFIX+"depth"));
        String settingsPath=ROOT+"noise_settings/highlands_v2.json";
        JsonObject settings=Objects.requireNonNull(out.get(settingsPath)).deepCopy().getAsJsonObject();
        // Never replace the original depth resource: caves and aquifers still reference it.
        settings.getAsJsonObject("noise_router").addProperty("depth",id);
        settings.getAsJsonArray("debug_functions").add(object("label","FG biome depth","function",id));
        out.put(settingsPath,settings);
        for(boolean under:List.of(false,true)) {
            String path=ROOT+"material_rule/highlands_v2/"+(under?"under_biome_surface":"biome_surface")+".json";
            JsonElement original=Objects.requireNonNull(out.get(path)).deepCopy();
            JsonObject rule=object("type","freshgenerator:ecotone_surface","height",PREFIX+"coherent/height",
                "temperature",PREFIX+"temperature","erosion",PREFIX+"erosion","subsurface",under);
            out.put(path,object("type","minecraft:sequence","sequence",List.of(rule,original)));
        }
    }
    private static JsonObject object(Object...p){JsonObject o=new JsonObject();for(int i=0;i<p.length;i+=2)o.add((String)p[i],G.toJsonTree(p[i+1]));return o;}
    private static JsonObject bin(String t,Object a,Object b){return object("type","minecraft:"+t,"left",a,"right",b);}
    private static JsonObject add(Object a,Object b){return bin("add",a,b);}
    private static JsonObject sub(Object a,Object b){return bin("sub",a,b);}
    private static JsonObject mul(Object a,Object b){return bin("mul",a,b);}
    private static JsonObject min(Object a,Object b){return bin("min",a,b);}
    private static JsonObject max(Object a,Object b){return bin("max",a,b);}
}
