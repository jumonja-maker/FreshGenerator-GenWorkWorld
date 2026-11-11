package dev.freshgenerator.smoke;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Full dedicated-server test. Never included in the distributed JAR. */
public final class WorldSmoke implements ModInitializer {
    @Override public void onInitialize() {
        if (!Boolean.getBoolean("freshgenerator.integration")) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { verify(server); }
            catch (Throwable error) { System.err.println("FRESHGENERATOR_WORLD_FAIL"); error.printStackTrace(); }
            finally { server.halt(false); }
        });
    }
    private static void verify(MinecraftServer server) throws Exception {
        String expected = Objects.requireNonNull(System.getenv("FG_EXPECTED_SETTINGS"));
        if (expected.endsWith("_v2")) { NaturalWorldVerifier.verify(server, expected); return; }
        var level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator))
            throw new AssertionError("Native noise generator not selected");
        String actual = generator.generatorSettings().unwrapKey().orElseThrow().toString();
        if (!actual.contains(expected)) throw new AssertionError("Preset fallback detected: " + actual);
        var settings = generator.generatorSettings().value();
        RandomState state = RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE), level.getSeed(), settings);
        var router = settings.noiseRouter();
        MessageDigest climateDigest = MessageDigest.getInstance("SHA-256");
        ByteBuffer bytes = ByteBuffer.allocate(4);
        int[] coords = {-29999984,-100000,-1025,-17,-16,-1,0,1,15,16,17,1024,100000,29999984};
        int samples = 0;
        for (int x : coords) for (int z : coords) {
            for (var function : List.of(router.continents(),router.erosion(),router.ridges(),router.temperature(),router.vegetation(),router.depth())) {
                float v = state.sampleBlockValueUncached(function,x,64,z);
                if (!Float.isFinite(v)) throw new AssertionError("Non-finite native density");
                float again = state.sampleBlockValueUncached(function,x,64,z);
                if (Float.floatToIntBits(v) != Float.floatToIntBits(again)) throw new AssertionError("Unstable native evaluation");
                bytes.clear();bytes.putFloat(v);climateDigest.update(bytes.array());samples++;
            }
        }
        String prefix = expected + "/";
        var densityRegistry = server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var delta = densityRegistry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(prefix+"height_delta"))).value();
        var folded = densityRegistry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(prefix+"ridges_folded"))).value();
        var profile = expected.contains("highlands") ? TerrainProfile.HIGHLANDS : TerrainProfile.BALANCED;
        var program = TerrainProgram.compile(prefix,profile);
        float maxError=0;
        for(int i=0;i<256;i++) {
            int x=i*157-19000,z=i*331-40000;
            float c=state.sampleBlockValueUncached(router.continents(),x,64,z);
            float e=state.sampleBlockValueUncached(router.erosion(),x,64,z);
            float p=state.sampleBlockValueUncached(folded,x,64,z);
            float nativeValue=state.sampleBlockValueUncached(delta,x,64,z);
            float expectedValue=program.heightDelta(c,e,p);
            float error=Math.abs(nativeValue-expectedValue);maxError=Math.max(maxError,error);
            if(error>.01f)throw new AssertionError("Compiled terrain differs from core: "+error);
        }
        MessageDigest terrainDigest=MessageDigest.getInstance("SHA-256");
        Map<BlockState,byte[]> encoded=new IdentityHashMap<>();
        int solid=0,air=0,water=0;
        Set<String> biomes=new TreeSet<>();
        int[][] chunks={{0,0},{1,0},{-1,0},{0,1},{0,-1}};
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int[] pair:chunks) {
            var chunk=level.getChunk(pair[0],pair[1]);
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=-64;y<320;y++) {
                pos.set(pair[0]*16+x,y,pair[1]*16+z);var block=chunk.getBlockState(pos);
                terrainDigest.update(encoded.computeIfAbsent(block,b->(b.toString()+"\n").getBytes(StandardCharsets.UTF_8)));
                if(block.isAir())air++;else if(block.is(Blocks.WATER))water++;else solid++;
            }
            biomes.add(level.getBiome(new BlockPos(pair[0]*16+8,80,pair[1]*16+8)).unwrapKey().orElseThrow().toString());
        }
        if(solid==0||air==0)throw new AssertionError("Empty or entirely solid test terrain");
        JsonObject report=new JsonObject();
        report.addProperty("settings",expected);report.addProperty("seed",level.getSeed());
        report.addProperty("native_samples",samples);report.addProperty("native_repeatability","passed");
        report.addProperty("core_vs_native_max_error",maxError);
        report.addProperty("climate_sha256",HexFormat.of().formatHex(climateDigest.digest()));
        report.addProperty("full_chunks_checked",chunks.length);
        report.addProperty("block_sha256",HexFormat.of().formatHex(terrainDigest.digest()));
        report.addProperty("solid",solid);report.addProperty("air",air);report.addProperty("water",water);
        report.add("sample_biomes",new Gson().toJsonTree(biomes));
        report.addProperty("scope","real vanilla+Fabric server; five FULL chunks; no client render or third-party mods");
        Files.writeString(Path.of("freshgenerator-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("FRESHGENERATOR_WORLD_PASS "+report);
    }
}
