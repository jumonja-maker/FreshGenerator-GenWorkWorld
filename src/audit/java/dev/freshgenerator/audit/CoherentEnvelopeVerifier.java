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
        r.addProperty("scope","native density sampling; FULL topology and structures are audited separately; not a screenshot reproduction");
        Files.writeString(Path.of("freshgenerator-envelope.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r)+"\n");
        System.out.println("FRESHGENERATOR_ENVELOPE_PASS "+r);
    }
}
