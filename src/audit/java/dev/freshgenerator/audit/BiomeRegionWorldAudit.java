package dev.freshgenerator.audit;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Same native observer on the unmodified release and candidate. Reference chooses FULL
 * windows first. Maps are native biome-source samples, NOT claimed to be FULL chunks.
 */
public final class BiomeRegionWorldAudit implements ModInitializer {
 private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
 private static final String P="freshgenerator:highlands_v2/";
 private record Window(String label,int x,int z){}
 private ServerLevel level;private NoiseBasedChunkGenerator generator;private RandomState state;
 private final SamplerContext context=SamplerContext.builder().enableCaches().build();
 private Climate.Sampler climate,baseline;private BiomeResolver resolver,baselineResolver;
 private DensitySampler height,density;
 private boolean candidate,normal;private long deepChecks,repeatChecks,storedChecks;
 private final Set<Long> chunks=new HashSet<>();
 @Override public void onInitialize(){if(!"true".equals(System.getenv("FG_REGION_AUDIT")))return;
  ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Exception e){throw new IllegalStateException("Biome region audit failed",e);}finally{server.halt(false);}});
 }
 private DensitySampler sampler(MinecraftServer server,String name){return state.getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
  .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name))).value());}
 private float elevation(int x,int z){return normal?generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state):height.sampleValue(context,x,0,z);}
 private static String id(net.minecraft.core.Holder<Biome> h){return h.unwrapKey().orElseThrow().identifier().toString();}
 private void run(MinecraftServer server)throws Exception {
  level=server.overworld();generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();state=level.getChunkSource().randomState();
  normal="normal".equals(System.getenv("FG_REGION_SCOPE"));candidate=!"reference".equals(System.getenv("FG_REGION_MODE"));
  String expected=normal?"minecraft:overworld":"freshgenerator:highlands_v2";
  if(!expected.equals(generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString()))throw new AssertionError("Wrong world");
  climate=state.createClimateSampler(context);baseline=climate;
  if(!normal){height=sampler(server,"coherent/height");density=sampler(server,"final_density");
   if(candidate)baseline=new Climate.Sampler(sampler(server,"regions/original_temperature").bind(context),
     sampler(server,"regions/original_vegetation").bind(context),climate.continentalness(),climate.erosion(),climate.depth(),climate.weirdness());
  }
  resolver=generator.getBiomeSource().createResolver(climate);baselineResolver=generator.getBiomeSource().createResolver(baseline);
  JsonArray maps=new JsonArray();List<Window> selected=new ArrayList<>();
  if(!normal){maps.add(map("regional",-12288,-12288,384,64,selected));maps.add(map("local",-2048,-2048,256,16,new ArrayList<>()));}
  Path coordinates=Path.of(System.getenv("FG_REGION_COORDINATES"));List<Window> windows;
  if(Files.exists(coordinates))windows=Arrays.asList(G.fromJson(Files.readString(coordinates),Window[].class));
  else{windows=normal?List.of(new Window("normal",0,0)):selected;
   if(!normal&&windows.size()<4)throw new AssertionError("Insufficient natural region controls: "+windows);
   Files.writeString(coordinates,G.toJson(windows)+"\n");}
  // Finish all neighbouring decorations before hashing any observation window.
  for(Window w:windows){int cx=Math.floorDiv(w.x(),16),cz=Math.floorDiv(w.z(),16);
   for(int x=cx-1;x<=cx+4;x++)for(int z=cz-1;z<=cz+4;z++){level.getChunk(x,z);chunks.add(((long)x<<32)|(z&0xffffffffL));}}
  JsonArray full=new JsonArray();for(Window w:windows)full.add(scan(w));
  JsonObject out=new JsonObject();out.addProperty("source_commit",System.getenv("FG_REGION_SOURCE"));out.addProperty("seed",level.getSeed());
  out.addProperty("mode",System.getenv("FG_REGION_MODE"));out.addProperty("pass",System.getenv("FG_REGION_PASS"));out.addProperty("settings",expected);
  out.add("maps",maps);out.add("full_windows",full);out.addProperty("explicit_full_chunks",chunks.size());
  out.addProperty("deep_climate_exact_checks",deepChecks);out.addProperty("repeated_source_checks",repeatChecks);out.addProperty("stored_biome_checks",storedChecks);
  out.addProperty("scope","Native resolver maps plus limited FULL windows; no client rendering, no universal biome rarity estimate");out.addProperty("completed",true);
  Files.writeString(Path.of("biome-region-audit.json"),G.toJson(out)+"\n");
  System.out.println("FRESHGENERATOR_BIOME_REGION_WORLD_PASS seed="+level.getSeed()+" maps="+maps.size()+" fullChunks="+chunks.size()+" deep="+deepChecks+" stored="+storedChecks);
 }
 private JsonObject map(String name,int x0,int z0,int side,int step,List<Window> selected)throws Exception {
  var palette=new ArrayList<String>();Map<String,Integer> ids=new TreeMap<>();int[] cells=new int[side*side],originals=new int[cells.length];
  MessageDigest geography=MessageDigest.getInstance("SHA-256"),deep=MessageDigest.getInstance("SHA-256");
  Set<String> chosen=new HashSet<>();
  for(int ix=0;ix<side;ix++)for(int iz=0;iz<side;iz++){
   int x=x0+ix*step,z=z0+iz*step,index=ix*side+iz;float h=elevation(x,z);int y=(int)Math.floor(h)-1,qx=Math.floorDiv(x,4),qz=Math.floorDiv(z,4),qy=Math.floorDiv(y,4);
   var target=climate.sample(qx,qy,qz);var oldTarget=baseline.sample(qx,qy,qz);
   if(target.continentalness()!=oldTarget.continentalness()||target.erosion()!=oldTarget.erosion()||target.weirdness()!=oldTarget.weirdness()||target.depth()!=oldTarget.depth())throw new AssertionError("Terrain climate axes changed");
   String current=id(resolver.getNoiseBiome(qx,qy,qz)),original=id(baselineResolver.getNoiseBiome(qx,qy,qz));
   cells[index]=ids.computeIfAbsent(current,k->{palette.add(k);return palette.size()-1;});
   originals[index]=ids.computeIfAbsent(original,k->{palette.add(k);return palette.size()-1;});
   geography.update(ByteBuffer.allocate(36).putFloat(h).putLong(target.continentalness()).putLong(target.erosion()).putLong(target.weirdness()).putLong(target.depth()).array());
   if(index%16==0){
    int dy=Math.floorDiv((int)Math.floor(h)-100,4);var a=climate.sample(qx,dy,qz);var b=baseline.sample(qx,dy,qz);
    if(!a.equals(b))throw new AssertionError("Deep climate changed at "+x+","+z);deepChecks++;
    deep.update(a.toString().getBytes(StandardCharsets.UTF_8));
    deep.update(ByteBuffer.allocate(4).putFloat(density.sampleValue(context,x,-32,z)).array());
   }
   if(index%128==0){var separate=generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
    if(!id(separate.getNoiseBiome(qx,qy,qz)).equals(current))throw new AssertionError("Caching/order changed biome");repeatChecks++;}
   if(selected.size()<5&&Math.abs(x)>256&&Math.abs(z)>256){
    String label=h<50?"water":h>=175?"mountain":h>=70&&h<130?landLabel(original):null;
    if(label!=null&&!chosen.contains(label)&&selected.stream().noneMatch(w->Math.abs(w.x()-x)<256&&Math.abs(w.z()-z)<256)){
     chosen.add(label);selected.add(new Window(label,Math.floorDiv(x-32,16)*16,Math.floorDiv(z-32,16)*16));
    }
   }
  }
  JsonObject out=new JsonObject();out.addProperty("name",name);out.addProperty("x",x0);out.addProperty("z",z0);out.addProperty("side",side);out.addProperty("step",step);
  out.add("palette",G.toJsonTree(palette));out.add("cells",G.toJsonTree(cells));out.add("reconstructed_reference_cells",G.toJsonTree(originals));
  out.addProperty("geography_sha256",HexFormat.of().formatHex(geography.digest()));out.addProperty("deep_sha256",HexFormat.of().formatHex(deep.digest()));
  System.out.println("BIOME_MAP_STAGE seed="+level.getSeed()+" map="+name+" samples="+cells.length+" types="+palette.size());return out;
 }
 private static String landLabel(String id){return switch(id){
  case "minecraft:forest","minecraft:birch_forest","minecraft:dark_forest","minecraft:taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga"->"forest";
  case "minecraft:plains","minecraft:sunflower_plains","minecraft:snowy_plains"->"plain";
  case "minecraft:jungle","minecraft:sparse_jungle","minecraft:desert","minecraft:savanna"->"warm";
  default->null;};}
 private JsonObject scan(Window window)throws Exception {
  int side=64,x0=window.x(),z0=window.z();MessageDigest base=MessageDigest.getInstance("SHA-256"),blocks=MessageDigest.getInstance("SHA-256");
  Map<Object,byte[]> encodings=new IdentityHashMap<>();Map<String,Integer> types=new TreeMap<>();BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
  for(int dx=0;dx<side;dx+=4)for(int dz=0;dz<side;dz+=4){int x=x0+dx,z=z0+dz;
   int ground=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state),water=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,state);
   float h=elevation(x,z);base.update(ByteBuffer.allocate(12).putInt(ground).putInt(water).putFloat(h).array());
   int qx=Math.floorDiv(x,4),qz=Math.floorDiv(z,4),qy=Math.floorDiv((int)Math.floor(h)-1,4);
   String expected=id(resolver.getNoiseBiome(qx,qy,qz)),actual=id(level.getNoiseBiome(qx,qy,qz));
   if(!actual.equals(expected))throw new AssertionError("Stored FULL biome differs from native resolver at "+x+","+z+": "+actual+" vs "+expected);
   storedChecks++;types.merge(actual,1,Integer::sum);var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
   for(int y=-64;y<320;y++){pos.set(x,y,z);var b=chunk.getBlockState(pos);blocks.update(encodings.computeIfAbsent(b,k->(k.toString()+"\n").getBytes(StandardCharsets.UTF_8)));}
  }
  JsonObject out=new JsonObject();out.addProperty("label",window.label());out.addProperty("x",x0);out.addProperty("z",z0);out.addProperty("sample_columns",256);
  out.addProperty("base_geometry_water_sha256",HexFormat.of().formatHex(base.digest()));out.addProperty("sampled_full_blocks_sha256",HexFormat.of().formatHex(blocks.digest()));out.add("biomes",G.toJsonTree(types));
  System.out.println("BIOME_FULL_STAGE "+window.label()+" "+x0+","+z0);return out;
 }
}
