package dev.freshgenerator.audit;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import java.nio.file.*;
import java.util.*;

/** Native sampler checks for the corrected exterior; never shipped in the mod JAR. */
public final class CoherentEnvelopeVerifier implements ModInitializer {
    private static final String P="freshgenerator:highlands_v2/coherent/";
    @Override public void onInitialize() {
        if(!Boolean.getBoolean("freshgenerator.audit"))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            if(!"freshgenerator:highlands_v2".equals(System.getenv("FG_AUDIT_SETTINGS")))return;
            try {verify(server);}catch(Exception e){throw new IllegalStateException("Coherent terrain validation failed",e);}
        });
    }
    private static DensityFunction function(MinecraftServer server,String name) {
        return server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(
                ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name))).value();
    }
    /** Independent observation contract: positive relief may not refill an excavation.
     * Unlike a tolerance waiver, the exact cap is required only in its declared
     * interval; negative detail and every unexcavated column retain their signal.
     */
    static double expectedAlpineCorrection(double relief, double cut) {
        if (!Double.isFinite(relief) || !Double.isFinite(cut))
            throw new AssertionError("Nonfinite alpine correction inputs");
        return cut >= 0.25 && cut < 19 ? Math.min(0, relief) : relief;
    }
    static void requireAlpineCorrection(double old, double now, double relief, double cut) {
        if (!Double.isFinite(old) || !Double.isFinite(now) || Math.abs(relief) > 0.1201)
            throw new AssertionError("Nonfinite or unbounded 3-D alpine density");
        double expected = expectedAlpineCorrection(relief, cut);
        if (Math.abs((now - old) - expected) > 2e-4)
            throw new AssertionError("Unexpected alpine density correction: old=" + old
                    + " now=" + now + " raw=" + relief + " cut=" + cut + " expected=" + expected);
    }
    /** Real native density and single-surface checks across independent alpine districts. */
    private static JsonObject verifyAlpineDetail(MinecraftServer server,RandomState state,DensityFunction height){
        var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var mountain=registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.parse("freshgenerator:highlands_v2/mountain_mask"))).value();
        var original=function(server,"detail/original_surface_density");
        var detail=function(server,"detail/rock_relief");
        var surface=function(server,"surface_density");
        var excavation=function(server,"hydrology/cut");
        record Peak(int x,int z,float height,float mountain) {}
        List<Peak> candidates=new ArrayList<>();
        for(int x=-8192;x<=8192;x+=384)for(int z=-8192;z<=8192;z+=384){
            float h=state.sampleBlockValueUncached(height,x,0,z);
            if(h<150)continue;
            float m=state.sampleBlockValueUncached(mountain,x,0,z);
            if(m>.10)candidates.add(new Peak(x,z,h,m));
        }
        candidates.sort(Comparator.comparingDouble((Peak p)->p.height()).reversed());
        List<Peak> peaks=new ArrayList<>();
        for(var p:candidates) {
            if(peaks.stream().noneMatch(q->Math.abs(p.x()-q.x())+Math.abs(p.z()-q.z())<512))peaks.add(p);
            if(peaks.size()>=40)break;
        }
        int cols=0,voxels=0,changed=0,signReversals=0,deep=0,high=0;
        double maxAbs=0,minRelief=Double.POSITIVE_INFINITY,maxRelief=Double.NEGATIVE_INFINITY;
        int cappedPositive=0;
        JsonArray capExamples=new JsonArray();
        for(var p:peaks)for(int dx=-16;dx<=16;dx+=8)for(int dz=-16;dz<=16;dz+=8){
            int x=p.x()+dx,z=p.z()+dz,center=(int)Math.floor(
                state.sampleBlockValueUncached(height,x,0,z));
            double cut=state.sampleBlockValueUncached(excavation,x,0,z);
            boolean seenAir=false;
            for(int y=center-12;y<=center+12;y++){
                double d=state.sampleBlockValueUncached(detail,x,y,z);
                double old=state.sampleBlockValueUncached(original,x,y,z);
                double now=state.sampleBlockValueUncached(surface,x,y,z);
                try { requireAlpineCorrection(old,now,d,cut); }
                catch(AssertionError failure) { throw new AssertionError("Alpine sample at "
                        +x+","+y+","+z+": "+failure.getMessage(),failure); }
                double applied=now-old;
                if(cut>=0.25&&cut<19&&d>2e-4) {
                    cappedPositive++;
                    if(capExamples.size()<8) {
                        JsonObject example=new JsonObject();
                        example.addProperty("x",x);example.addProperty("y",y);example.addProperty("z",z);
                        example.addProperty("excavation",cut);example.addProperty("raw_relief",d);
                        example.addProperty("original_density",old);example.addProperty("actual_density",now);
                        capExamples.add(example);
                    }
                }
                maxAbs=Math.max(maxAbs,Math.abs(d));
                minRelief=Math.min(minRelief,d);maxRelief=Math.max(maxRelief,d);
                if(Math.abs(applied)>.003)changed++;
                if(y<=center-10){deep++;if(Math.abs(d)>1e-6)throw new AssertionError("Detail leaked deep");}
                if(y>=center+10){high++;if(Math.abs(d)>1e-6)throw new AssertionError("Detail leaked above top");}
                if(now<=0)seenAir=true;
                else if(seenAir)signReversals++;
                voxels++;
            }
            cols++;
        }
        if(cols<30||changed<50)throw new AssertionError("Insufficient actual alpine detail evidence: "+cols+"/"+changed);
        if(maxAbs>0.1201||signReversals>0)
            throw new AssertionError("Unbounded or disjoint alpine profile: "+maxAbs+"/"+signReversals);
        JsonObject out=new JsonObject();
        out.addProperty("peak_regions",peaks.size());out.addProperty("sampled_columns",cols);
        out.addProperty("voxels",voxels);out.addProperty("changed_voxels",changed);
        out.addProperty("max_absolute_density_correction",maxAbs);
        out.addProperty("min_relief",minRelief);out.addProperty("max_relief",maxRelief);
        out.addProperty("sign_reversals",signReversals);
        out.addProperty("excavated_positive_relief_caps",cappedPositive);
        out.add("excavation_cap_examples",capExamples);
        out.addProperty("deep_zero_checks",deep);out.addProperty("high_zero_checks",high);
        System.out.println("FRESHGENERATOR_ALPINE_DETAIL_PASS "+out);
        return out;
    }

    private static void verify(MinecraftServer server) throws Exception {
        var level=server.overworld();
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var state=RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed(),generator.generatorSettings().value());
        var height=function(server,"height");var density=function(server,"surface_density");var weight=function(server,"cave_weight");
        var nativeCaves=function(server,"native_cave_body");
        Random random=new Random(0x436f686572656e74L);int samples=0,monotone=0,roofs=0,deep=0,deepCavities=0;
        float maxNeighbourStep=0;List<List<Integer>> coordinates=new ArrayList<>();
        coordinates.add(List.of(27,13));coordinates.add(List.of(4080,-14840));
        coordinates.add(List.of(3584,-13312));
        for(int i=0;i<157;i++)coordinates.add(List.of(random.nextInt(65536)-32768,random.nextInt(65536)-32768));
        for(var at:coordinates) {
            int x=at.get(0),z=at.get(1);float h=state.sampleBlockValueUncached(height,x,0,z);
            if(!Float.isFinite(h)||h < -48.01f||h>304.01f)throw new AssertionError("Envelope out of bounds: "+h);
            for(int y:List.of(-48,0,64,128,256))if(Math.abs(h-state.sampleBlockValueUncached(height,x,y,z))>.002f)
                throw new AssertionError("Surface height depends on Y");
            float previous=Float.POSITIVE_INFINITY;
            for(int y=-64;y<320;y+=4) {
                float d=state.sampleBlockValueUncached(density,x,y,z);
                if(!Float.isFinite(d)||d>previous+.00001f)throw new AssertionError("Exterior reverses from air to rock");
                previous=d;monotone++;
            }
            maxNeighbourStep=Math.max(maxNeighbourStep,Math.abs(h-state.sampleBlockValueUncached(height,x+1,0,z)));
            if(h>=112)for(int d=2;d<=16;d+=2){
                float w=state.sampleBlockValueUncached(weight,x,(int)Math.floor(h)-d,z);
                if(Math.abs(w)>.0001f)
                    throw new AssertionError("High surface loses support at "+x+","+z
                            +" height="+h+" depth="+d+" weight="+w);
                roofs++;
            }
            for(int y:List.of(-48,-24,0,24))if(h-y>=43) {
                float w=state.sampleBlockValueUncached(weight,x,y,z);
                if(Math.abs(w-1)>.0001f)throw new AssertionError("Deep caves disabled");
                deep++;if(state.sampleBlockValueUncached(nativeCaves,x,y,z)<0)deepCavities++;
            }
            samples++;
        }
        if(roofs==0||deepCavities==0)throw new AssertionError("Insufficient highland/cavity coverage");
        JsonObject r=new JsonObject();r.addProperty("seed",level.getSeed());r.addProperty("sampled_columns",samples);
        r.addProperty("monotone_exterior_density_samples",monotone);r.addProperty("protected_roof_samples",roofs);
        r.addProperty("deep_cave_weight_samples",deep);r.addProperty("native_deep_cavity_samples",deepCavities);
        r.addProperty("max_sampled_height_step_per_block",maxNeighbourStep);
        r.add("alpine_detail",verifyAlpineDetail(server,state,height));
        r.add("native_density_cache",NativeDensityCacheVerifier.verify(server,state));
        r.addProperty("scope","native density sampling; FULL topology and structures are audited separately; not a screenshot reproduction");
        Files.writeString(Path.of("freshgenerator-envelope.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r)+"\n");
        System.out.println("FRESHGENERATOR_ENVELOPE_PASS "+r);
    }
}
