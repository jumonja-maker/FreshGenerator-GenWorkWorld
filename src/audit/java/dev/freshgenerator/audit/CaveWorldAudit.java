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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Identical read-only observer for unmodified 0.3.0 and the candidate. All coordinates are
 * chosen by the reference once, then passed verbatim to candidate and reload. Opening area
 * is a vertical sky-cut proxy, not an exact count of distinct connected cave systems.
 */
public final class CaveWorldAudit implements ModInitializer {
 private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
 private static final String P="freshgenerator:highlands_v2/";
 private static final Set<Block> DECOR=Set.of(Blocks.SNOW,Blocks.BAMBOO,Blocks.POINTED_DRIPSTONE,Blocks.CACTUS,
   Blocks.RED_MUSHROOM_BLOCK,Blocks.BROWN_MUSHROOM_BLOCK,Blocks.MUSHROOM_STEM,Blocks.MELON,Blocks.PUMPKIN);
 private record Window(String label,int x,int z,double score) { }
 private ServerLevel level;private NoiseBasedChunkGenerator gen;private RandomState state;
 private DensitySampler height,weight,surface,caves,gate,roof,originalWeight;
 private final SamplerContext context=SamplerContext.builder().enableCaches().build();
 private final Set<Long> chunks=new HashSet<>();private boolean normal,candidate;
 private int nativeChecks;private float minGate=1,maxGate=0;
 @Override public void onInitialize(){if(!"true".equals(System.getenv("FG_CAVE_AUDIT")))return;
  ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Exception e){throw new IllegalStateException("Cave audit failed",e);}finally{server.halt(false);}});
 }
 private DensitySampler sampler(MinecraftServer server,String local){return state.getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
   .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+local))).value());}
 private float sample(DensitySampler f,int x,int y,int z){return f.sampleValue(context,x,y,z);}
 private float h(int x,int z){return normal?gen.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state):sample(height,x,0,z);}
 private float body(int x,int y,int z){float a=sample(surface,x,y,z),b=sample(caves,x,y,z),w=sample(weight,x,y,z);return Math.min(a,a+(b-a)*w);}
 private void run(MinecraftServer server)throws Exception{
  level=server.overworld();gen=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();state=level.getChunkSource().randomState();
  normal="normal".equals(System.getenv("FG_CAVE_SCOPE"));candidate=!"reference".equals(System.getenv("FG_CAVE_MODE"));
  String expected=normal?"minecraft:overworld":"freshgenerator:highlands_v2";
  if(!expected.equals(gen.generatorSettings().unwrapKey().orElseThrow().identifier().toString()))throw new AssertionError("Wrong generator");
  if(!normal){height=sampler(server,"coherent/height");weight=sampler(server,"coherent/cave_weight");surface=sampler(server,"coherent/surface_density");caves=sampler(server,"coherent/native_cave_body");
   if(candidate){gate=sampler(server,"cave_access/entrance_gate");roof=sampler(server,"cave_access/roof_depth");originalWeight=sampler(server,"cave_access/original_weight");}}
  Path coordinates=Path.of(System.getenv("FG_CAVE_COORDINATES"));List<Window> windows;
  if(Files.exists(coordinates))windows=Arrays.asList(G.fromJson(Files.readString(coordinates),Window[].class));
  else{windows=normal?List.of(new Window("normal-control",0,0,0)):select();Files.writeString(coordinates,G.toJson(windows)+"\n");}
  JsonArray regions=new JsonArray();for(Window w:windows){var region=scan(w);regions.add(region);
   System.out.println("CAVE_AUDIT_STAGE seed="+level.getSeed()+" label="+w.label()+" x="+w.x()+" z="+w.z()+" open="+region.get("dry_opening_columns"));}
  JsonObject out=new JsonObject();out.addProperty("completed",true);out.addProperty("source_commit",System.getenv("FG_CAVE_SOURCE"));
  out.addProperty("seed",level.getSeed());out.addProperty("settings",expected);out.addProperty("structures_enabled",false);
  out.addProperty("mode",System.getenv("FG_CAVE_MODE"));out.addProperty("full_chunks_requested",chunks.size());out.add("regions",regions);
  out.addProperty("native_policy_checks",nativeChecks);out.addProperty("min_sampled_gate",minGate);out.addProperty("max_sampled_gate",maxGate);
  try{var t=Class.forName("dev.freshgenerator.cave.CaveTelemetry");out.add("telemetry",G.toJsonTree(t.getMethod("snapshot").invoke(null)));}
  catch(ClassNotFoundException e){if(candidate)throw e;}
  out.addProperty("scope","paired finite FULL windows, bounded vertical opening-area proxy; not all lateral entrances, cave accessibility or client visuals");
  Files.writeString(Path.of("cave-audit.json"),G.toJson(out)+"\n");System.out.println("FRESHGENERATOR_CAVE_WORLD_PASS seed="+level.getSeed()+" windows="+windows.size());
 }
 private List<Window> select(){
  List<Window> low=new ArrayList<>(),rich=new ArrayList<>(),high=new ArrayList<>(),water=new ArrayList<>();
  for(int x=-4096;x<=4096;x+=256)for(int z=-4096;z<=4096;z+=256){
   float elevation=h(x,z);double distance=Math.hypot(x,z);
   if(elevation>=68&&elevation<=110){
    low.add(new Window("typical",x-32,z-32,distance));int score=0;
    for(int dx=-16;dx<=16;dx+=16)for(int dz=-16;dz<=16;dz+=16){float hh=h(x+dx,z+dz);if(hh>=67&&body(x+dx,(int)Math.ceil(hh)-7,z+dz)<0)score++;}
    if(score>0)rich.add(new Window("opening-rich",x-32,z-32,score*100000-distance));
   }
   if(elevation>=160&&elevation<=270)high.add(new Window("highland",x-32,z-32,distance));
   if(elevation<58){boolean submerged=true;for(int dx=-32;dx<=32;dx+=32)for(int dz=-32;dz<=32;dz+=32)if(h(x+dx,z+dz)>62)submerged=false;
    if(submerged)water.add(new Window("water-control",x-32,z-32,distance));}
  }
  low.sort(Comparator.comparingDouble(Window::score));rich.sort(Comparator.comparingDouble(Window::score).reversed());
  high.sort(Comparator.comparingDouble(Window::score));water.sort(Comparator.comparingDouble(Window::score));
  List<Window> chosen=new ArrayList<>();take(chosen,low,6);take(chosen,rich,3);take(chosen,high,1);take(chosen,water,1);
  if(chosen.size()<8||chosen.stream().noneMatch(w->w.label().startsWith("water-control")))throw new AssertionError("Insufficient land/water sample");
  return chosen;
 }
 private static void take(List<Window> chosen,List<Window> from,int wanted){int n=0;for(Window w:from){
  if(chosen.stream().anyMatch(a->Math.abs(w.x()-a.x())<192&&Math.abs(w.z()-a.z())<192))continue;
  chosen.add(new Window(w.label()+"-"+n,w.x(),w.z(),w.score()));if(++n>=wanted)break;}}
 private void policy(int x,int z,float elevation){if(!candidate||normal)return;
  float g=sample(gate,x,0,z);if(!Float.isFinite(g)||g<-.0001||g>1.0001)throw new AssertionError("Gate outside bounds");
  minGate=Math.min(minGate,g);maxGate=Math.max(maxGate,g);double land=smooth(63,69,elevation);
  if(Math.abs(sample(roof,x,0,z)-14*land*(1-g))>.002)throw new AssertionError("Runtime carver roof differs from graph");
  for(int depth:new int[]{0,6,12,24,42,64}){int y=(int)Math.floor(elevation)-depth;
   double expected=sample(originalWeight,x,y,z)*(1-land*(1-g)*(1-smooth(12,42,elevation-y)));
   if(Math.abs(sample(weight,x,y,z)-expected)>.0005)throw new AssertionError("Native cave weight differs from model");nativeChecks++;}
 }
 private static double smooth(double a,double b,double v){double t=Math.max(0,Math.min(1,(v-a)/(b-a)));return t*t*t*(t*(6*t-15)+10);}
 private JsonObject scan(Window w)throws Exception{
  int side=64,x0=Math.floorDiv(w.x(),16)*16,z0=Math.floorDiv(w.z(),16)*16;
  for(int cx=Math.floorDiv(x0,16)-1;cx<=Math.floorDiv(x0+side-1,16)+1;cx++)for(int cz=Math.floorDiv(z0,16)-1;cz<=Math.floorDiv(z0+side-1,16)+1;cz++){
   level.getChunk(cx,cz);chunks.add(((long)cx<<32)|(cz&0xffffffffL));}
  MessageDigest envelope=MessageDigest.getInstance("SHA-256"),deepNoise=MessageDigest.getInstance("SHA-256"),blocks=MessageDigest.getInstance("SHA-256");
  ByteArrayOutputStream deep=new ByteArrayOutputStream(),deepStates=new ByteArrayOutputStream();Map<BlockState,byte[]> encoded=new IdentityHashMap<>();
  Map<BlockState,Integer> stateIds=new IdentityHashMap<>();List<String> palette=new ArrayList<>();List<Integer> deepTops=new ArrayList<>();
  int[] ground=new int[side*side],waterHeights=new int[side*side];boolean[] openings=new boolean[side*side];
  int holes=0,noiseHoles=0,deepAir=0,wet=0,underSea=0;BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
  for(int dx=0;dx<side;dx++)for(int dz=0;dz<side;dz++){
   int x=x0+dx,z=z0+dz,i=dx*side+dz;float hh=h(x,z);
   envelope.update(ByteBuffer.allocate(4).putFloat(hh).array());if(hh<=63)underSea++;
   int y=level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-1;pos.set(x,y,z);BlockState b=level.getBlockState(pos);
   while(y>level.getMinY()&&(!Heightmap.Types.OCEAN_FLOOR.isOpaque().test(b)||b.is(BlockTags.LOGS)||b.is(BlockTags.LEAVES)||DECOR.contains(b.getBlock()))){pos.setY(--y);b=level.getBlockState(pos);}
   ground[i]=y;boolean fluid=!level.getFluidState(pos.above()).isEmpty();if(fluid)wet++;
   waterHeights[i]=fluid?level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z):-10000;
   int top=(int)Math.ceil(hh)-1;
   boolean hole=!normal&&top>=level.getSeaLevel()+3&&y<=top-6&&level.getBlockState(new BlockPos(x,top-1,z)).isAir()&&level.getBlockState(new BlockPos(x,top-5,z)).isAir();
   openings[i]=hole;if(hole)holes++;
   if(!normal&&hh>=66&&body(x,top-5,z)<0)noiseHoles++;
   if(dx%4==0&&dz%4==0){
    policy(x,z,hh);
    var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
    int deepTop=Math.min(48,(int)Math.floor(hh)-64);deepTops.add(deepTop);
    for(int yy=-48;yy<=deepTop;yy++){
     pos.set(x,yy,z);var s=chunk.getBlockState(pos);int kind=!s.getFluidState().isEmpty()?2:s.isSolid()&&!s.is(BlockTags.LOGS)&&!s.is(BlockTags.LEAVES)?1:0;
     deep.write(kind);if(kind==0)deepAir++;
     int id=stateIds.computeIfAbsent(s,k->{int v=palette.size();palette.add(k.toString());return v;});
     if(id>65535)throw new AssertionError("Deep-state palette exceeds ushort");deepStates.write(id>>>8);deepStates.write(id&255);
     if(!normal&&yy%8==0)deepNoise.update(ByteBuffer.allocate(4).putFloat(body(x,yy,z)).array());
    }
    for(int yy=-64;yy<320;yy++){pos.set(x,yy,z);var s=chunk.getBlockState(pos);blocks.update(encoded.computeIfAbsent(s,k->(k.toString()+"\n").getBytes(StandardCharsets.UTF_8)));}
   }
  }
  JsonObject result=new JsonObject();result.addProperty("label",w.label());result.addProperty("x",x0);result.addProperty("z",z0);result.addProperty("side",side);
  result.addProperty("columns",side*side);result.addProperty("dry_opening_columns",holes);result.addProperty("noise_only_opening_columns",noiseHoles);
  result.addProperty("wet_ground_columns",wet);result.addProperty("submerged_envelope_columns",underSea);result.addProperty("deep_air_samples",deepAir);
  result.addProperty("envelope_sha256",HexFormat.of().formatHex(envelope.digest()));result.addProperty("deep_density_sha256",HexFormat.of().formatHex(deepNoise.digest()));
  result.addProperty("sampled_full_blocks_sha256",HexFormat.of().formatHex(blocks.digest()));result.addProperty("deep_occupancy_base64",Base64.getEncoder().encodeToString(deep.toByteArray()));
  result.add("deep_state_palette",G.toJsonTree(palette));result.addProperty("deep_state_indices_u16be",Base64.getEncoder().encodeToString(deepStates.toByteArray()));
  result.add("deep_column_top_y",G.toJsonTree(deepTops));result.addProperty("deep_min_y",-48);result.addProperty("deep_column_stride",4);
  result.add("ground_y",G.toJsonTree(ground));result.add("water_y",G.toJsonTree(waterHeights));result.add("opening_regions",components(openings,side));
  return result;
 }
 private static JsonArray components(boolean[] input,int side){boolean[] seen=new boolean[input.length];JsonArray out=new JsonArray();int[] stack=new int[input.length];
  for(int start=0;start<input.length;start++)if(input[start]&&!seen[start]){int size=0,head=0,tail=0,minX=side,maxX=0,minZ=side,maxZ=0;boolean boundary=false;
   seen[start]=true;stack[tail++]=start;while(head<tail){int at=stack[head++],x=at/side,z=at%side;size++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
    if(x==0||z==0||x==side-1||z==side-1)boundary=true;
    for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){int xx=x+d[0],zz=z+d[1];if(xx<0||zz<0||xx>=side||zz>=side)continue;int nb=xx*side+zz;if(input[nb]&&!seen[nb]){seen[nb]=true;stack[tail++]=nb;}}}
   JsonObject c=new JsonObject();c.addProperty("columns",size);c.addProperty("touches_boundary",boundary);c.add("local_bounds",G.toJsonTree(List.of(minX,minZ,maxX,maxZ)));out.add(c);
  }return out;}
}
