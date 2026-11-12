package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MountainSurfaceDetailTest {
    @Test void strictBoundsNeverTurnDeepRockIntoCaves() {
        for(double m=0;m<=1;m+=.125)for(double b=0;b<=1;b+=.125)
        for(double n=-3;n<=3;n+=.01){
            double d=MountainSurfaceDetailCompiler.boundedCorrection(m,b,n);
            assertTrue(Double.isFinite(d));
            assertTrue(Math.abs(d)<=MountainSurfaceDetailCompiler.MAX_DENSITY_CORRECTION+1e-12);
            if(m==0||b==0)assertEquals(0,d,0);
        }
        assertThrows(IllegalArgumentException.class,()->MountainSurfaceDetailCompiler.boundedCorrection(2,1,0));
        assertThrows(IllegalArgumentException.class,()->MountainSurfaceDetailCompiler.boundedCorrection(1,1,Double.NaN));
    }
    @Test void onlyOneOldResourceChangesAndHistoricOutputIsExact()throws Exception {
        var before=WorldgenCatalog.compileBeforeMountainDetails(new VanillaAssets());
        var after=WorldgenCatalog.compile(new VanillaAssets());
        var changed=new HashSet<String>();
        for(var entry:before.entrySet())if(!entry.getValue().equals(after.get(entry.getKey())))changed.add(entry.getKey());
        assertEquals(Set.of(MountainSurfaceDetailCompiler.SURFACE),changed);
        assertEquals(before.size()+4,after.size());
        for(String name:List.of("original_surface_density","mountain_factor","vertical_band","rock_relief"))
            assertTrue(after.containsKey(MountainSurfaceDetailCompiler.ROOT+"coherent/detail/"+name+".json"));
        try(var zip=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.5-alpha.1.jar")){
            for(var e:Collections.list(zip.entries()))if(e.getName().startsWith("data/")&&e.getName().endsWith(".json")){
                assertNotNull(before.get(e.getName()));
                try(var in=zip.getInputStream(e)){
                    assertEquals(JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)),
                        JsonParser.parseString(ResourceJson.encode(before.get(e.getName()))),e.getName());
                }
            }
        }
    }
    @Test void detailIsThreeDimensionalAndUsesNativeSignalNotNewCaveNoise()throws Exception {
        var after=WorldgenCatalog.compile(new VanillaAssets());
        String r=MountainSurfaceDetailCompiler.ROOT;
        var v=after.get(r+"coherent/detail/rock_relief.json").getAsJsonObject();
        assertEquals("minecraft:range_choice",v.get("type").getAsString());
        assertTrue(v.toString().contains("coherent/native_cave_body"));
        assertTrue(v.toString().contains("coherent/depth_blocks"));
        assertTrue(after.get(MountainSurfaceDetailCompiler.SURFACE).toString().contains("detail/rock_relief"));
        assertEquals("minecraft:beardifier",after.get(r+"final_density.json").getAsJsonObject().getAsJsonObject("right").get("type").getAsString());
    }
    @Test void samplerHeightAndAquifersAreUnchanged()throws Exception {
        var before=WorldgenCatalog.compileBeforeMountainDetails(new VanillaAssets());
        var after=WorldgenCatalog.compile(new VanillaAssets());
        for(String n:List.of("height","depth_blocks","cave_weight","preliminary_surface_level","native_cave_body"))
            assertEquals(before.get(MountainSurfaceDetailCompiler.ROOT+"coherent/"+n+".json"),
                after.get(MountainSurfaceDetailCompiler.ROOT+"coherent/"+n+".json"));
        for(String name:List.of("temperature","vegetation","jaggedness","offset","mountain_mask"))
            assertEquals(before.get(VanillaAssets.path("density_function",MountainSurfaceDetailCompiler.P+name)),
                after.get(VanillaAssets.path("density_function",MountainSurfaceDetailCompiler.P+name)));
    }
}
