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
    /** Read-only probe of original 3-D cave/rock density near true alpine peaks. */
    private static JsonObject nativeMountainStudy(MinecraftServer server,RandomState state,
                                                   DensityFunction height,DensityFunction nativeBody) {
        var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var mask=registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
             Identifier.parse("freshgenerator:highlands_v2/mountain_mask"))).value();
        record Candidate(int x,int z,float h,float m) {}
        List<Candidate> peaks=new ArrayList<>();
        for(int x=-8192;x<=8192;x+=384)for(int z=-8192;z<=8192;z+=384) {
            float h=state.sampleBlockValueUncached(height,x,0,z);
            if(h<160)continue;
            float m=state.sampleBlockValueUncached(mask,x,0,z);
            if(m>.10f)peaks.add(new Candidate(x,z,h,m));
        }
        peaks.sort(Comparator.comparingDouble((Candidate a)->a.h()).reversed());
        List<Candidate> selected=new ArrayList<>();
        for(Candidate peak:peaks) {
            if(selected.stream().noneMatch(a->Math.abs(a.x()-peak.x())+Math.abs(a.z()-peak.z())<512))
               selected.add(peak);
            if(selected.size()>=48)break;
        }
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,sum=0;
        int samples=0,clipped=0,variation=0,positive=0,negative=0;
        double maxVerticalDelta=0,maxHorizontalDelta=0;
        for(Candidate peak:selected)for(int[] delta:new int[][]{{0,0},{8,0},{0,8}}) {
            int x=peak.x()+delta[0],z=peak.z()+delta[1];
            float h=state.sampleBlockValueUncached(height,x,0,z);
            int y=(int)Math.floor(h);
            double a=state.sampleBlockValueUncached(nativeBody,x,y,z);
            double b=state.sampleBlockValueUncached(nativeBody,x,y-3,z);
            double c=state.sampleBlockValueUncached(nativeBody,x,y+3,z);
            double d=state.sampleBlockValueUncached(nativeBody,x+3,y,z);
            min=Math.min(min,a);max=Math.max(max,a);sum+=a;samples++;
            if(Math.abs(a)>=.18)clipped++;
            if(a>=0)positive++;else negative++;
            if(Math.abs(a-b)>1e-4||Math.abs(a-c)>1e-4)variation++;
            maxVerticalDelta=Math.max(maxVerticalDelta,Math.max(Math.abs(a-b),Math.abs(a-c)));
            maxHorizontalDelta=Math.max(maxHorizontalDelta,Math.abs(a-d));
        }
        JsonObject out=new JsonObject();
        out.addProperty("sampled_peak_regions",selected.size());
        out.addProperty("surface_samples",samples);
        out.addProperty("min_native_density",samples==0?0:min);
        out.addProperty("max_native_density",samples==0?0:max);
        out.addProperty("mean_native_density",samples==0?0:sum/samples);
        out.addProperty("abs_gt_point18",clipped);
        out.addProperty("positive",positive);out.addProperty("negative",negative);
        out.addProperty("vertical_variation",variation);
        out.addProperty("max_3block_vertical_change",maxVerticalDelta);
        out.addProperty("max_3block_horizontal_change",maxHorizontalDelta);
        System.out.println("NATIVE_MOUNTAIN_SURFACE_DENSITY "+out);
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
                if(Math.abs(w)>.0001f)throw new AssertionError("High surface loses support: "+w);roofs++;
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
        r.add("original_3d_alpine_probe",nativeMountainStudy(server,state,height,nativeCaves));
        r.addProperty("scope","native density sampling; FULL topology and structures are audited separately; not a screenshot reproduction");
        Files.writeString(Path.of("freshgenerator-envelope.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r)+"\n");
        System.out.println("FRESHGENERATOR_ENVELOPE_PASS "+r);
    }
}
