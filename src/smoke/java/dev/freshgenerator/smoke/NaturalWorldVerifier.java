package dev.freshgenerator.smoke;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Native mountain/boundary sampling. A FULL halo is loaded before taking an immutable observation. */
final class NaturalWorldVerifier {
    private static DensityFunction function(MinecraftServer server,String id) {
        return server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
                .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(id))).value();
    }
    static void verify(MinecraftServer server,String expected) throws Exception {
        var level=server.overworld();
        if(!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator))throw new AssertionError("Wrong generator");
        if(!generator.generatorSettings().unwrapKey().orElseThrow().toString().contains(expected))throw new AssertionError("Preset fallback");
        var settings=generator.generatorSettings().value();
        RandomState state=RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed(),settings);
        String prefix=expected+"/",oldPrefix=expected.replace("_v2","_v1")+"/";
        var delta=function(server,prefix+"height_delta");
        var program=NaturalTerrainProgram.compile(prefix,expected.contains("highlands")?TerrainProfile.HIGHLANDS:TerrainProfile.BALANCED);
        Map<String,DensityFunction> inputs=new LinkedHashMap<>();
        for(String key:List.of("continents","erosion","ridges_folded","massif_noise","spur_noise")) inputs.put(prefix+key,function(server,prefix+key));
        MessageDigest climate=MessageDigest.getInstance("SHA-256");ByteBuffer bits=ByteBuffer.allocate(4);
        float errorMax=0;int compared=0;
        for(int i=0;i<512;i++) {
            int x=i*157-39000,z=i*331-81000;
            Map<String,Float> sample=new HashMap<>();
            for(var e:inputs.entrySet()) sample.put(e.getKey(),state.sampleBlockValueUncached(e.getValue(),x,64,z));
            float core=program.sample("height_delta",id->sample.get(id));
            float nativeValue=state.sampleBlockValueUncached(delta,x,64,z);
            float err=Math.abs(core-nativeValue);errorMax=Math.max(errorMax,err);
            if(!Float.isFinite(nativeValue)||err>.02f)throw new AssertionError("Native/core mismatch "+err);
            for(String axis:List.of("continents","erosion","ridges","temperature","vegetation")) {
                float value=state.sampleBlockValueUncached(function(server,prefix+axis),x,64,z);
                float old=state.sampleBlockValueUncached(function(server,oldPrefix+axis),x,64,z);
                if(Float.floatToIntBits(value)!=Float.floatToIntBits(old))throw new AssertionError("Climate axis unexpectedly changed: "+axis);
                bits.clear();bits.putFloat(value);climate.update(bits.array());compared++;
            }
        }
        int bx=0,bz=0;float best=-Float.MAX_VALUE;
        for(int ix=-32;ix<=32;ix++)for(int iz=-32;iz<=32;iz++) {
            int x=ix*512,z=iz*512;float value=state.sampleBlockValueUncached(delta,x,96,z);
            if(value>best){best=value;bx=x;bz=z;}
        }
        if(best<4)throw new AssertionError("No meaningful mountain uplift in the sampled region");
        int[][] heights=new int[17][17];int min=320,max=-64;
        Set<Integer> distinct=new HashSet<>();double slopeSum=0;int slopes=0,largestStep=0;
        StringBuilder csv=new StringBuilder("x,z,base_surface_y\n");
        for(int i=0;i<17;i++)for(int j=0;j<17;j++) {
            int x=bx+(i-8)*16,z=bz+(j-8)*16;
            int h=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,state);
            if(h < -64 || h>320)throw new AssertionError("Height outside dimension: "+h);
            heights[i][j]=h;distinct.add(h);min=Math.min(min,h);max=Math.max(max,h);
            csv.append(x).append(',').append(z).append(',').append(h).append('\n');
            if(i>0){int d=Math.abs(h-heights[i-1][j]);largestStep=Math.max(largestStep,d);slopeSum+=d;slopes++;}
            if(j>0){int d=Math.abs(h-heights[i][j-1]);largestStep=Math.max(largestStep,d);slopeSum+=d;slopes++;}
        }
        if(distinct.size()<3)throw new AssertionError("Mountain sample is unexpectedly flat");
        Set<Long> chunks=new LinkedHashSet<>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)chunks.add(pack(Math.floorDiv(bx,16)+dx,Math.floorDiv(bz,16)+dz));
        String previous=null;int previousX=0,previousZ=0;int boundaryPairs=0;
        Set<String> biomes=new TreeSet<>();
        for(int i=-8;i<=8;i++) {
            previous=null;
            for(int j=-8;j<=8;j++) {
                int x=bx+i*128,z=bz+j*128;
                int h=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,state);
                String biome=level.getUncachedNoiseBiome(Math.floorDiv(x,4),Math.floorDiv(h,4),Math.floorDiv(z,4)).unwrapKey().orElseThrow().toString();
                biomes.add(biome);
                if(previous!=null&&!previous.equals(biome)) {
                    if(boundaryPairs==0) {
                        chunks.add(pack(Math.floorDiv(previousX,16),Math.floorDiv(previousZ,16)));
                        chunks.add(pack(Math.floorDiv(x,16),Math.floorDiv(z,16)));
                    }
                    boundaryPairs++;
                }
                previous=biome;previousX=x;previousZ=z;
            }
        }
        // Complete neighbour decorations BEFORE any target block enters the digest.
        // Never compare a partially observed target against a later fully decorated reload.
        Set<Long> preloaded=new LinkedHashSet<>();
        for(long key:chunks) {
            int cx=(int)(key>>32),cz=(int)key;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)preloaded.add(pack(cx+dx,cz+dz));
        }
        for(long key:preloaded)level.getChunk((int)(key>>32),(int)key);
        MessageDigest blocks=MessageDigest.getInstance("SHA-256");Map<BlockState,byte[]> strings=new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();int solid=0,air=0,water=0;
        for(long key:chunks) {
            int cx=(int)(key>>32),cz=(int)key;var chunk=level.getChunk(cx,cz);
            bits.clear();bits.putInt(cx);blocks.update(bits.array());bits.clear();bits.putInt(cz);blocks.update(bits.array());
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=-64;y<320;y++) {
                pos.set(cx*16+x,y,cz*16+z);BlockState block=chunk.getBlockState(pos);
                blocks.update(strings.computeIfAbsent(block,b->(b.toString()+"\n").getBytes(StandardCharsets.UTF_8)));
                if(block.isAir())air++;else if(block.is(Blocks.WATER))water++;else solid++;
            }
        }
        if(solid==0||air==0)throw new AssertionError("Broken generated terrain");
        JsonObject r=new JsonObject();r.addProperty("settings",expected);r.addProperty("seed",level.getSeed());
        r.addProperty("native_samples",512);r.addProperty("native_repeatability","verified by core comparison and restart scenario");
        r.addProperty("core_vs_native_max_error",errorMax);r.addProperty("unchanged_climate_axis_samples",compared);
        r.addProperty("climate_sha256",HexFormat.of().formatHex(climate.digest()));
        r.addProperty("full_chunks_checked",chunks.size());r.addProperty("block_sha256",HexFormat.of().formatHex(blocks.digest()));
        r.addProperty("preloaded_full_chunks",preloaded.size());r.addProperty("block_hash_scope","target plus one FULL chunk halo before digest; not the historical pre-halo observation");
        r.addProperty("solid",solid);r.addProperty("air",air);r.addProperty("water",water);
        r.addProperty("mountain_center_x",bx);r.addProperty("mountain_center_z",bz);r.addProperty("sampled_peak_uplift",best);
        r.addProperty("heightmap_columns",289);r.addProperty("min_base_height",min);r.addProperty("max_base_height",max);
        r.addProperty("distinct_base_heights",distinct.size());r.addProperty("mean_height_step_per_16_blocks",slopeSum/slopes);
        r.addProperty("max_height_step_per_16_blocks",largestStep);r.addProperty("surface_biome_boundary_pairs_sampled",boundaryPairs);
        r.add("surface_biomes_near_mountain",new Gson().toJsonTree(biomes));
        r.addProperty("scope","native heights and FULL mountain/boundary chunks; no client image or aesthetics claim");
        Files.writeString(Path.of("freshgenerator-smoke.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));
        Files.writeString(Path.of("freshgenerator-heightmap.csv"),csv);
        System.out.println("FRESHGENERATOR_WORLD_PASS "+r);
    }
    private static long pack(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
