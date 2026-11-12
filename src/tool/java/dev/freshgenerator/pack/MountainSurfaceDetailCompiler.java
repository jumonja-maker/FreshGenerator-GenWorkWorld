package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/**
 * Reintroduce a bounded slice of the ORIGINAL native 3-D rock density on high
 * mountain faces. The coherent 2-D height remains the foundation and aquifer
 * envelope, so native upper caves cannot recreate unsupported floating roofs.
 * No replacement world preset, structure registry, carvers or biome rules.
 */
public final class MountainSurfaceDetailCompiler {
    public static final String P="freshgenerator:highlands_v2/";
    public static final String ROOT="data/freshgenerator/worldgen/density_function/highlands_v2/";
    public static final String SURFACE=ROOT+"coherent/surface_density.json";
    public static final String NATIVE=P+"coherent/native_cave_body";
    public static final double ALPINE_START=148,ALPINE_FULL=190;
    public static final double NATIVE_CLAMP=0.16,BLEND=0.75;
    public static final double MAX_DENSITY_CORRECTION=NATIVE_CLAMP*BLEND;
    private static final Gson G=new Gson();
    private MountainSurfaceDetailCompiler(){}

    public static void apply(Map<String,JsonElement> out) {
        Objects.requireNonNull(out);
        Map<String,JsonElement> staged=new TreeMap<>(out);
        JsonElement previous=Objects.requireNonNull(staged.get(SURFACE),"Missing coherent exterior");
        if(!previous.isJsonObject()||
           !"minecraft:squeeze".equals(previous.getAsJsonObject().get("type").getAsString()))
            throw new IllegalStateException("Unexpected coherent exterior");
        if(!staged.containsKey(ROOT+"coherent/native_cave_body.json"))
            throw new IllegalStateException("Native 3-D body missing");
        put(staged,"original_surface_density",previous.deepCopy());
        // Two-dimensional selection, with smooth zero on foothills and all lowland.
        put(staged,"mountain_factor",cache(mul(
            smooth(ALPINE_START,ALPINE_FULL,P+"coherent/height"),
            smooth(0.10,0.42,P+"mountain_mask"))));
        // World-relative 3-D slice: it vanishes outside +/-9 vertical blocks.
        put(staged,"vertical_band",mul(
            smooth(-9,-6,P+"coherent/depth_blocks"),
            sub(1.0,smooth(6,9,P+"coherent/depth_blocks"))));
        // Compute the expensive original native 3-D field ONLY in the thin
        // surface band, not in deep caves or in lowlands. Explicitly bounded.
        JsonElement bounded=mul(P+"coherent/detail/mountain_factor",
            mul(P+"coherent/detail/vertical_band",
                mul(BLEND,clamp(NATIVE,-NATIVE_CLAMP,NATIVE_CLAMP))));
        put(staged,"rock_relief",range(P+"coherent/depth_blocks",-9,9,
                bounded,0.0));
        // For lowland Y-independent height this references exactly the old
        // surface density. No change to weather, caves, or village height oracle.
        staged.put(SURFACE,range(P+"coherent/height",ALPINE_START,321,
            add(P+"coherent/detail/original_surface_density",
                P+"coherent/detail/rock_relief"),
            P+"coherent/detail/original_surface_density"));
        out.putAll(staged);
    }
    /** Verified bound; new detail can never excavate more than the capped band. */
    public static double boundedCorrection(double alpine,double band,double nativeDensity) {
        if(!Double.isFinite(alpine)||!Double.isFinite(band)||!Double.isFinite(nativeDensity)
           ||alpine<0||alpine>1||band<0||band>1)throw new IllegalArgumentException("Invalid rock input");
        return alpine*band*BLEND*Math.clamp(nativeDensity,-NATIVE_CLAMP,NATIVE_CLAMP);
    }
    private static String path(String name){return ROOT+"coherent/detail/"+name+".json";}
    private static void put(Map<String,JsonElement> o,String name,JsonElement value){
        if(o.putIfAbsent(path(name),value)!=null)throw new IllegalStateException("Duplicate detail node: "+name);
    }
    private static JsonObject object(Object... p){
        JsonObject o=new JsonObject();for(int i=0;i<p.length;i+=2)o.add((String)p[i],G.toJsonTree(p[i+1]));return o;
    }
    private static JsonElement smooth(double low,double high,String input) {
        return G.toJsonTree(NaturalTerrainProgram.smootherstep(low,high,DensityExpr.ref(input)).json());
    }
    private static JsonObject binary(String name,Object left,Object right){
        return object("type","minecraft:"+name,"left",left,"right",right);
    }
    private static JsonObject add(Object a,Object b){return binary("add",a,b);}
    private static JsonObject sub(Object a,Object b){return binary("sub",a,b);}
    private static JsonObject mul(Object a,Object b){return binary("mul",a,b);}
    private static JsonObject clamp(Object input,double a,double b){return object("type","minecraft:clamp","input",input,"min",a,"max",b);}
    private static JsonObject cache(Object input){return object("type","minecraft:cache","input",input);}
    private static JsonObject range(Object input,double lo,double hi,Object inside,Object outside){
        return object("type","minecraft:range_choice","input",input,"min_inclusive",lo,
             "max_exclusive",hi,"when_in_range",inside,"when_out_of_range",outside);
    }
}
