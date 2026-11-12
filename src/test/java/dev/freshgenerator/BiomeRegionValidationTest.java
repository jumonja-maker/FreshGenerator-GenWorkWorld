package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Invalid compiler inputs must not produce or partially publish broken climate graphs. */
class BiomeRegionValidationTest {
    static Map<String,JsonElement> baseline()throws Exception {
        return new TreeMap<>(WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets()));
    }
    static void rejectUnchanged(Map<String,JsonElement> input) {
        Map<String,JsonElement> snapshot=new TreeMap<>();
        input.forEach((k,v)->snapshot.put(k,v==null?null:v.deepCopy()));
        assertThrows(IllegalStateException.class,()->BiomeRegionCompiler.apply(input));
        assertEquals(snapshot,input,"Rejected climate left partial resources in the caller");
    }
    static JsonObject climate(Map<String,JsonElement> input,String name) {
        String key=BiomeRegionCompiler.path(name);
        JsonObject copy=input.get(key).deepCopy().getAsJsonObject();input.put(key,copy);return copy;
    }
    @Test void everyGeneratedOutputCollisionIsRejectedAtomically()throws Exception {
        for(String name:List.of("temperature","vegetation"))
            for(String role:List.of("regions/original_","regions/biome_")) {
                var input=baseline();input.put(BiomeRegionCompiler.path(role+name),new JsonPrimitive(0));
                rejectUnchanged(input);
            }
    }
    @Test void nullValuedOutputKeysAreStillCollisions()throws Exception {
        for(String name:List.of("temperature","vegetation"))
            for(String role:List.of("regions/original_","regions/biome_")) {
                var input=baseline();input.put(BiomeRegionCompiler.path(role+name),null);rejectUnchanged(input);
            }
    }
    @Test void invalidHorizontalScalesCannotEscapeIntoJson()throws Exception {
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MIN_VALUE}) {
            var input=baseline();climate(input,"vegetation").addProperty("xz_scale",value);rejectUnchanged(input);
        }
    }
    @Test void numericStringsAreNotAcceptedAsNumbers()throws Exception {
        for(String key:List.of("xz_scale","y_scale")) {
            var input=baseline();climate(input,"vegetation").addProperty(key,key.equals("y_scale")?"0":"0.25");
            rejectUnchanged(input);
        }
    }
    @Test void missingOrStructuredScaleIsRejectedAtomically()throws Exception {
        for(String key:List.of("xz_scale","y_scale")) {
            var missing=baseline();climate(missing,"vegetation").remove(key);rejectUnchanged(missing);
            var structured=baseline();climate(structured,"vegetation").add(key,new JsonObject());rejectUnchanged(structured);
            var nullValue=baseline();climate(nullValue,"vegetation").add(key,JsonNull.INSTANCE);rejectUnchanged(nullValue);
        }
    }
    @Test void validScaleKeepsExactlyTheOriginalArithmetic()throws Exception {
        var input=baseline();double before=climate(input,"vegetation").get("xz_scale").getAsDouble();
        BiomeRegionCompiler.apply(input);
        assertEquals(Double.doubleToRawLongBits(before/3.0),Double.doubleToRawLongBits(
                input.get(BiomeRegionCompiler.path("vegetation")).getAsJsonObject().get("xz_scale").getAsDouble()));
    }
}
