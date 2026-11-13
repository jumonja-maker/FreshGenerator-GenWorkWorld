package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoherentSurfaceTest {
    @Test void softCeilingDoesNotCreateFlatCut() {
        float previous=-Float.MAX_VALUE;
        for(int i=-40;i<=800;i++) {float h=CoherentSurfaceCompiler.softHeight(i);assertTrue(h>previous);assertTrue(h<304);previous=h;}
        assertEquals(128f,CoherentSurfaceCompiler.softHeight(128));
        assertEquals(256f,CoherentSurfaceCompiler.softHeight(256));
    }
    @Test void softCeilingHasContinuousSlope() {
        float a=CoherentSurfaceCompiler.softHeight(255.99f),b=CoherentSurfaceCompiler.softHeight(256),c=CoherentSurfaceCompiler.softHeight(256.01f);
        assertEquals(b-a,c-b,0.0001f);
        assertThrows(IllegalArgumentException.class,()->CoherentSurfaceCompiler.softHeight(Float.NaN));
    }
    @Test void highTerrainRoofCannotBeCarvedByOldFloatingDensity() {
        for(float h=112;h<304;h+=1)for(float depth=-24;depth<=18;depth+=.5f)assertEquals(0,CoherentSurfaceCompiler.caveWeight(h,h-depth),1e-6);
    }
    @Test void DeepCavesAndLowlandEntrancesAreNotGloballyDisabled() {
        for(float h=48;h<304;h+=1)assertEquals(1,CoherentSurfaceCompiler.caveWeight(h,h-42),1e-6);
        for(float d=-10;d<=100;d+=1)assertEquals(1,CoherentSurfaceCompiler.caveWeight(63,63-d),1e-6);
    }
    @Test void caveBlendIsFiniteBoundedAndContinuous() {
        for(float h=-48;h<320;h+=2) {
            float prev=0;
            for(float d=-16;d<90;d+=.25f){float w=CoherentSurfaceCompiler.caveWeight(h,h-d);assertTrue(Float.isFinite(w)&&w>=0&&w<=1);assertTrue(w+1e-6>=prev);prev=w;}
        }
    }
    @Test void structureAdjustmentIsOutsideSurfaceLimiter() throws Exception {
        var map=WorldgenCatalog.compile(new VanillaAssets());var finalDensity=map.get(CoherentSurfaceCompiler.ROOT+"final_density.json").getAsJsonObject();
        assertEquals("minecraft:add",finalDensity.get("type").getAsString());
        assertEquals("minecraft:beardifier",finalDensity.getAsJsonObject("right").get("type").getAsString());
    }
    @Test void originalSurfaceAndAquifersShareThePreHydrologyEnvelope() throws Exception {
        var m=WorldgenCatalog.compileBeforeHydrology(new VanillaAssets());String p=CoherentSurfaceCompiler.PREFIX;
        assertTrue(m.get(CoherentSurfaceCompiler.ROOT+"preliminary_surface_level.json").toString().contains(p+"coherent/height"));
        var settings=m.get("data/freshgenerator/worldgen/noise_settings/highlands_v2.json").getAsJsonObject();
        assertEquals(p+"preliminary_surface_level",settings.getAsJsonObject("aquifers").get("surface_level").getAsString());
        assertEquals(p+"final_density",settings.getAsJsonObject("noise_router").get("final_density").getAsString());
    }
    @Test void surfaceEnvelopeHasNoYDependentNoiseOrBiomeIdBranches() throws Exception {
        var m=WorldgenCatalog.compile(new VanillaAssets());String root=CoherentSurfaceCompiler.ROOT;
        for(String n:List.of("raw_height","height_excess","limited_height","height")) {
            String json=m.get(root+"coherent/"+n+".json").toString();
            assertFalse(json.contains("minecraft:y\""));assertFalse(json.contains("range_choice"));assertFalse(json.contains("base_3d_noise"));
        }
    }
    @Test void allCorrectionReferencesAreDefined() throws Exception {
        var m=WorldgenCatalog.compile(new VanillaAssets());
        for(var e:m.entrySet())if(e.getKey().contains("/coherent/"))visit(e.getValue(),m);
    }
    private void visit(JsonElement e,Map<String,JsonElement> m) {
        if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString()) {
            String s=e.getAsString();if(s.startsWith(CoherentSurfaceCompiler.PREFIX))assertTrue(m.containsKey(VanillaAssets.path("density_function",s)),s);
        } else if(e.isJsonObject())e.getAsJsonObject().asMap().values().forEach(v->visit(v,m));
        else if(e.isJsonArray())e.getAsJsonArray().forEach(v->visit(v,m));
    }
}
