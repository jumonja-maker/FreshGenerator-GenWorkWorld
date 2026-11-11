package dev.freshgenerator.audit;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Same observer for unmodified 0.2.4 and candidate. No imports from the new forest code.
 * Counts supported log columns and leaf projection, NOT exact biological tree individuals.
 */
public final class ForestWorldAudit implements ModInitializer {
 private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
 private final Set<Long> full=new HashSet<>();
 private ServerLevel level;private NoiseBasedChunkGenerator gen;private RandomState state;
 private DensitySampler envelope;private BiomeManager predicted;
 private final SamplerContext ctx=SamplerContext.builder().enableCaches().build();
 private record Window(String label,int x,int z,double score){}
 @Override public void onInitialize(){
  if(!"true".equals(System.getenv("FG_FOREST_AUDIT")))return;
  ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Exception e){throw new IllegalStateException(e);}finally{server.halt(false);}});
 }
 private void run(MinecraftServer server)throws Exception {
  level=server.overworld();gen=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();state=level.getChunkSource().randomState();
  boolean normal="normal".equals(System.getenv("FG_FOREST_SCOPE"));
  String expected=normal?"minecraft:overworld":"freshgenerator:highlands_v2";
  if(!gen.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals(expected))throw new AssertionError("Wrong settings");
  if(!normal){var f=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(expected+"/coherent/height"))).value();envelope=state.getSampler(f);}
  predicted=level.getBiomeManager().withDifferentSource(gen.getBiomeSource().createCachingResolver(state));
  List<Window> windows=normal?List.of(new Window("normal-control",0,0,0)):select();
  JsonArray regions=new JsonArray();for(Window w:windows){regions.add(scan(w));System.out.println("FOREST_AUDIT_STAGE "+w.label()+" "+w.x()+","+w.z());}
  JsonObject r=new JsonObject();r.addProperty("source_commit",System.getenv("FG_FOREST_SOURCE"));r.addProperty("mode",System.getenv("FG_FOREST_MODE"));
  r.addProperty("seed",level.getSeed());r.addProperty("settings",expected);r.addProperty("structures_enabled",false);
  r.addProperty("full_chunks_requested",full.size());r.add("regions",regions);
  try{var t=Class.forName("dev.freshgenerator.forest.ForestTelemetry");r.add("telemetry",G.toJsonTree(t.getMethod("snapshot").invoke(null)));}
  catch(ClassNotFoundException e){if(!"reference".equals(System.getenv("FG_FOREST_MODE")))throw e;}
  r.addProperty("scope","same native generated areas; log columns are not exact tree counts; structures separately audited; no client render");r.addProperty("completed",true);
  Files.writeString(Path.of("forest-audit.json"),G.toJson(r)+"\n");System.out.println("FRESHGENERATOR_FOREST_WORLD_PASS "+new Gson().toJson(r));
 }
 private float height(int x,int z){return envelope==null?gen.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state):envelope.sampleValue(ctx,x,0,z);}
 private String biome(int x,int z){return predicted.getBiome(x,(int)Math.ceil(height(x,z))-1,z).unwrapKey().orElseThrow().identifier().toString();}
 private static String group(String id){return switch(id){
  case "minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest"->"temperate";
  case "minecraft:taiga","minecraft:snowy_taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga"->"conifer";
  case "minecraft:jungle"->"tropical";
  case "minecraft:plains","minecraft:sunflower_plains"->"plains-control";
  default->"other";};}
 private List<Window> select(){
  Map<String,Window> best=new TreeMap<>();
  for(int x=-16384;x<=16384;x+=512)for(int z=-16384;z<=16384;z+=512){
   float h=height(x,z);if(h<68||h>220)continue;String g=group(biome(x,z));if(g.equals("other"))continue;
   int same=0;for(int[] d:new int[][]{{-64,-64},{-64,64},{64,-64},{64,64},{-96,0},{96,0},{0,-96},{0,96}})if(group(biome(x+d[0],z+d[1])).equals(g))same++;
   if(same<7)continue;
   double slope=Math.max(Math.abs(height(x+16,z)-height(x-16,z)),Math.abs(height(x,z+16)-height(x,z-16)))/32;
   if(h<=155&&slope<.65){double score=Math.hypot(x,z)+slope*4000;Window w=new Window(g,x-40,z-40,score);if(!best.containsKey(g)||score<best.get(g).score())best.put(g,w);}
   if(!g.equals("plains-control")&&h>=160&&slope<1){double score=-h+Math.hypot(x,z)/100000;Window w=new Window("montane",x-40,z-40,score);if(!best.containsKey("montane")||score<best.get("montane").score())best.put("montane",w);}
  }
  for(String required:List.of("temperate","conifer","tropical","plains-control"))if(!best.containsKey(required))throw new AssertionError("No representative "+required+" plot for "+level.getSeed());
  return new ArrayList<>(best.values());
 }
 private JsonObject scan(Window w)throws Exception {
  int x0=Math.floorDiv(w.x(),16)*16,z0=Math.floorDiv(w.z(),16)*16,side=80;
  int cx0=Math.floorDiv(x0,16),cz0=Math.floorDiv(z0,16);
  for(int cx=cx0-1;cx<=cx0+5;cx++)for(int cz=cz0-1;cz<=cz0+5;cz++){level.getChunk(cx,cz);full.add(((long)cx<<32)|(cz&0xffffffffL));}
  MessageDigest terrain=MessageDigest.getInstance("SHA-256"),blocks=MessageDigest.getInstance("SHA-256");
  int stems=0,canopy=0,logs=0,leafBlocks=0,min=320,max=-64;int[] cells=new int[25];
  Map<String,Integer> biomes=new TreeMap<>();Map<Object,byte[]> encodings=new IdentityHashMap<>();
  BlockPos.MutableBlockPos p=new BlockPos.MutableBlockPos();
  for(int dx=0;dx<side;dx++)for(int dz=0;dz<side;dz++){
   int x=x0+dx,z=z0+dz,base=gen.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state)-1;
   int water=gen.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,state)-1;
   float h=height(x,z);String biome=level.getBiome(new BlockPos(x,base,z)).unwrapKey().orElseThrow().identifier().toString();
   terrain.update(ByteBuffer.allocate(12).putInt(base).putInt(water).putFloat(h).array());terrain.update(biome.getBytes(StandardCharsets.UTF_8));terrain.update((byte)0);
   biomes.merge(biome,1,Integer::sum);min=Math.min(min,base);max=Math.max(max,base);
   boolean leaf=false,stem=false;var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
   for(int y=Math.max(-64,base-8);y<Math.min(320,base+96);y++){
    p.set(x,y,z);var b=chunk.getBlockState(p);blocks.update(encodings.computeIfAbsent(b,k->(k.toString()+"\n").getBytes(StandardCharsets.UTF_8)));
    if(b.is(BlockTags.LEAVES)){leaf=true;leafBlocks++;}
    if(b.is(BlockTags.LOGS)){
     logs++;var below=chunk.getBlockState(new BlockPos(x,y-1,z));
     if(y<=base+10&&!below.is(BlockTags.LOGS)&&!below.is(BlockTags.LEAVES)&&below.isSolid()
         &&chunk.getBlockState(new BlockPos(x,y+1,z)).is(BlockTags.LOGS))stem=true;
    }
   }
   if(leaf)canopy++;if(stem){stems++;cells[(dx/16)*5+dz/16]++;}
  }
  double mean=stems/25.0,var=0;int empty=0;for(int n:cells){var+=(n-mean)*(n-mean);if(n==0)empty++;}var/=24;
  JsonObject out=new JsonObject();out.addProperty("label",w.label());out.addProperty("x",x0);out.addProperty("z",z0);out.addProperty("side",side);
  out.addProperty("columns",side*side);out.addProperty("base_geometry_biome_sha256",HexFormat.of().formatHex(terrain.digest()));
  out.addProperty("surface_block_sha256",HexFormat.of().formatHex(blocks.digest()));out.addProperty("supported_log_columns",stems);
  out.addProperty("canopy_projection_columns",canopy);out.addProperty("canopy_projection_fraction",canopy/(double)(side*side));
  out.addProperty("log_blocks",logs);out.addProperty("leaf_blocks",leafBlocks);out.addProperty("empty_16x16_stem_cells",empty);
  out.addProperty("stem_quadrat_variance_mean",mean==0?0:var/mean);out.addProperty("min_ground_y",min);out.addProperty("max_ground_y",max);
  out.add("stem_quadrats",G.toJsonTree(cells));out.add("biomes",G.toJsonTree(biomes));return out;
 }
}
