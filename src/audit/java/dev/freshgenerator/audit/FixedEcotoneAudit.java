package dev.freshgenerator.audit;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Same observer is compiled against the unmodified reference and candidate production sources.
 * Fixed coordinates avoid comparing different selected boundaries after a biome-selection correction.
 */
public final class FixedEcotoneAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final String P="freshgenerator:highlands_v2/";
    private static final Set<String> CAVES=Set.of("minecraft:dripstone_caves","minecraft:lush_caves","minecraft:sulfur_caves","minecraft:deep_dark");
    private static final Set<Block> DECOR=Set.of(Blocks.SNOW,Blocks.BAMBOO,Blocks.POINTED_DRIPSTONE,Blocks.CACTUS,
        Blocks.RED_MUSHROOM_BLOCK,Blocks.BROWN_MUSHROOM_BLOCK,Blocks.MUSHROOM_STEM,Blocks.MELON,Blocks.PUMPKIN);
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_FIXED_ECOTONE")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try{run(server);}catch(Exception e){throw new IllegalStateException("Fixed-coordinate audit failed",e);}
            finally{server.halt(false);}
        });
    }
    private static DensityFunction f(MinecraftServer server,String name){return server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
        .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name))).value();}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static void run(MinecraftServer server)throws Exception{
        var level=server.overworld();var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(!generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong world");
        var state=level.getChunkSource().randomState();var ctx=SamplerContext.builder().enableCaches().build();
        var height=state.getSampler(f(server,"coherent/height"));var oldDepth=state.getSampler(f(server,"depth"));
        var climate=state.createClimateSampler(ctx);
        Method snowPolicy=null;
        try{snowPolicy=Class.forName("dev.freshgenerator.surface.SnowTransitions").getMethod("precipitation",LevelReader.class,BlockPos.class,Biome.Precipitation.class);}
        catch(ClassNotFoundException absent){if(!"reference".equals(System.getenv("FG_FIXED_MODE")))throw absent;}
        Set<Long> chunks=new HashSet<>();JsonArray windows=new JsonArray();int checkedSnow=0,deepChecks=0,deepMismatch=0;
        // Includes the reported snow/rock/grass junction and exposed dripstone-cave mountain.
        int[][] starts={{4480,-12976},{-7936,14720},{-16016,-3008},{-3280,-3088},{-3744,-14032},{0,0}};
        for(int[] start:starts){
            int x0=start[0],z0=start[1];
            for(int cx=Math.floorDiv(x0,16)-1;cx<=Math.floorDiv(x0+32,16)+1;cx++)
                for(int cz=Math.floorDiv(z0,16)-1;cz<=Math.floorDiv(z0+32,16)+1;cz++){level.getChunk(cx,cz);chunks.add(key(cx,cz));}
            JsonArray columns=new JsonArray();int exposed=0;
            for(int dx=0;dx<=32;dx++)for(int dz=0;dz<=32;dz++){
                int x=x0+dx,z=z0+dz;float envelope=height.sampleValue(ctx,x,0,z);
                int baseY=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state)-1;
                int waterY=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,state)-1;
                int y=level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-1;
                var pos=new BlockPos.MutableBlockPos(x,y,z);var block=level.getBlockState(pos);
                while(y>level.getMinY()&&(!Heightmap.Types.OCEAN_FLOOR.isOpaque().test(block)||block.is(BlockTags.LOGS)||block.is(BlockTags.LEAVES)||DECOR.contains(block.getBlock()))){pos.setY(--y);block=level.getBlockState(pos);}
                String biome=level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
                String material=BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
                BlockPos above=pos.above();boolean wet=level.getFluidState(above).is(FluidTags.WATER);
                boolean sky=level.canSeeSky(above);boolean snowLayer=level.getBlockState(above).is(Blocks.SNOW);
                if(!wet&&y==baseY&&sky&&CAVES.contains(biome))exposed++;
                boolean shouldSnow=level.getBiome(above).value().shouldSnow(level,above);
                if(snowPolicy!=null){
                    var b=level.getBiome(above).value();var precipitation=(Biome.Precipitation)snowPolicy.invoke(null,level,above,b.getPrecipitationAt(above,level.getSeaLevel()));
                    var space=level.getBlockState(above);
                    boolean expected=precipitation==Biome.Precipitation.SNOW&&level.isInsideBuildHeight(above.getY())
                        &&level.getBrightness(LightLayer.BLOCK,above)<10&&(space.isAir()||space.is(Blocks.SNOW))&&Blocks.SNOW.defaultBlockState().canSurvive(level,above);
                    if(expected!=shouldSnow)throw new AssertionError("Snow mixin disagrees with shared policy at "+above);
                    checkedSnow++;
                }
                if(dx%8==0&&dz%8==0){
                    int qx=Math.floorDiv(x,4),qz=Math.floorDiv(z,4),qy=Math.floorDiv((int)Math.floor(envelope)-96,4);
                    float expected=oldDepth.sampleValue(ctx,qx*4,qy*4,qz*4);
                    float actual=net.minecraft.world.level.biome.Climate.unquantizeCoord(climate.sample(qx,qy,qz).depth());
                    if(Math.abs(actual-expected)>.0002)deepMismatch++;deepChecks++;
                }
                JsonArray c=new JsonArray();c.add(x);c.add(z);c.add(baseY);c.add(waterY);c.add(y);c.add(envelope);c.add(biome);c.add(material);c.add(snowLayer);c.add(wet);c.add(sky);c.add(shouldSnow);columns.add(c);
            }
            JsonObject w=new JsonObject();w.addProperty("min_x",x0);w.addProperty("min_z",z0);w.addProperty("side",33);
            w.addProperty("exposed_cave_columns",exposed);w.add("columns",columns);windows.add(w);
            System.out.println("ECOTONE_FIXED_STAGE "+level.getSeed()+" "+x0+","+z0+" caves="+exposed);
        }
        if(deepMismatch!=0)throw new AssertionError("Deep climate changed at "+deepMismatch+" cells");
        JsonObject r=new JsonObject();r.addProperty("seed",level.getSeed());r.addProperty("mode",System.getenv("FG_FIXED_MODE"));
        r.addProperty("production_source",System.getenv("FG_FIXED_SOURCE"));r.addProperty("full_chunks_requested",chunks.size());
        r.addProperty("snow_native_safety_checks",checkedSnow);r.addProperty("deep_climate_checks",deepChecks);r.addProperty("deep_climate_mismatches",deepMismatch);
        r.add("column_schema",G.toJsonTree(List.of("x","z","base_y","water_y","ground_y","envelope_y","biome","material","snow_layer","wet","sky","should_snow")));
        r.add("windows",windows);r.addProperty("completed",true);
        Files.writeString(Path.of("fixed-ecotone.json"),G.toJson(r)+"\n");
        System.out.println("FRESHGENERATOR_FIXED_ECOTONE_PASS seed="+level.getSeed()+" chunks="+chunks.size()+" snowChecks="+checkedSnow);
    }
}
