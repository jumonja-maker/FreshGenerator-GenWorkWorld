package dev.freshgenerator.audit;

import com.google.gson.*;
import dev.freshgenerator.village.VillageTerrainModel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only paired measurements. Identical source on baseline and candidate.
 * Native farm-less layouts remain in the evidence; late air/lava failures are counted separately.
 */
public final class VillageWorldAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().create();
    private record Location(String id,int chunkX,int chunkZ){}
    private record Study(Location location,StructureStart start,BoundingBox box,VillageTerrainModel model){}
    private ServerLevel level;private NoiseBasedChunkGenerator generator;private RandomState state;
    private DensitySampler heights, currentHeights;
    private Object drainage; private java.lang.reflect.Method waterAt;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private final JsonObject report=new JsonObject();
    private final Set<Long> fullChunks=new HashSet<>();
    private final Map<Long,ChunkAccess> observedFullChunks=new HashMap<>();
    @Override public void onInitialize(){if(!"true".equals(System.getenv("FG_VILLAGE_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{boolean ok=false;try{run(server);ok=true;}
            catch(Throwable error){error.printStackTrace();report.addProperty("failure",error.toString());}
            finally{report.addProperty("completed",ok);try{Files.writeString(Path.of("village-audit.json"),G.toJson(report)+"\n");}catch(Exception e){throw new IllegalStateException(e);}
                System.out.println(ok?"FRESHGENERATOR_VILLAGE_AUDIT_PASS":"FRESHGENERATOR_VILLAGE_AUDIT_FAIL");server.halt(false);}});
    }
    private static String id(BlockState b){return BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString();}
    private static String hash(MessageDigest d){return HexFormat.of().formatHex(d.digest());}
    private static void hash(MessageDigest d,String s){d.update(s.getBytes(StandardCharsets.UTF_8));}
    private double height(int x,int z){return heights.sampleValue(context,x,0,z);}
    private void run(MinecraftServer server)throws Exception {
        level=server.overworld();generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();state=level.getChunkSource().randomState();
        String settings=generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
        if(!settings.equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong generator");
        heights=state.getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.parse("freshgenerator:highlands_v2/coherent/height"))).value());
        currentHeights=heights;
        heights=state.getSampler(generator.generatorSettings().value().debugFunctions().functions().stream()
            .filter(e->e.label().equals("FG cave access: surface")).findFirst().orElseThrow().function());
        // Same observer source also runs with the historical JAR, where the new
        // hydrology access interface does not exist. Never load its classes there.
        try {
            drainage=state.getClass().getMethod("freshgenerator$drainage").invoke(state);
            if(drainage!=null)waterAt=drainage.getClass().getMethod("waterAt",int.class,int.class);
        } catch(NoSuchMethodException absent) { drainage=null; }
        report.addProperty("hydrology_observer_schema",4);
        report.addProperty("source_commit",System.getenv("FG_VILLAGE_SOURCE"));report.addProperty("phase",System.getenv("FG_VILLAGE_PHASE"));
        report.addProperty("seed",level.getSeed());report.addProperty("settings",settings);
        Path locationsFile=Path.of(System.getenv("FG_VILLAGE_LOCATIONS"));List<Location> locations;
        if(Files.exists(locationsFile))locations=Arrays.asList(G.fromJson(Files.readString(locationsFile),Location[].class));
        else {
            locations=new ArrayList<>();
            var all=List.of("village_plains","village_desert","village_savanna","village_snowy","village_taiga");
            locations.add(locate(server,all,8192,8192,24));
            Location savanna=locate(server,List.of("village_savanna"),-8192,8192,48);
            if(!locations.contains(savanna))locations.add(savanna);
            Location west=locate(server,all,-8192,8192,32);
            if(!locations.contains(west))locations.add(west);
            if(locations.size()<3){Location south=locate(server,all,-12288,-8192,32);if(!locations.contains(south))locations.add(south);}
            if(locations.size()<3)throw new AssertionError("Insufficient distinct fixed-origin villages");
            Files.writeString(locationsFile,G.toJson(locations)+"\n");
        }
        List<Study> studies=new ArrayList<>();
        for(Location location:locations){
            var definition=server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse(location.id))).value();
            var start=level.getChunk(location.chunkX,location.chunkZ,ChunkStatus.STRUCTURE_STARTS).getStartForStructure(definition);
            if(start==null||!start.isValid())throw new AssertionError("Missing real village "+location);
            var sites=new ArrayList<VillageTerrainModel.Site>();
            for(var piece:start.getPieces())if(piece instanceof PoolElementStructurePiece pool){
                var b=piece.getBoundingBox();int ground=b.minY()+pool.getGroundLevelDelta();
                if(ground<generator.getSeaLevel()-4||ground>300)continue;
                boolean rigid=pool.getElement().getProjection()==StructureTemplatePool.Projection.RIGID
                    &&(long)(b.maxX()-b.minX()+1)*(b.maxZ()-b.minZ()+1)>=16&&b.maxY()-b.minY()>=3;
                sites.add(new VillageTerrainModel.Site(b.minX(),b.minZ(),b.maxX(),b.maxZ(),ground,rigid));
            }
            if(sites.isEmpty())throw new AssertionError("No plot footprints");
            studies.add(new Study(location,start,start.getBoundingBox(),new VillageTerrainModel(sites)));
        }
        JsonArray terrain=new JsonArray();
        for(Study s:studies){prepare(s,ChunkStatus.TERRAIN,3);terrain.add(scanTerrain(s));}
        report.add("terrain",terrain);
        if(!"reload".equals(System.getenv("FG_VILLAGE_PHASE")))Files.writeString(Path.of("village-terrain.json"),G.toJson(terrain)+"\n");
        for(var status:ChunkStatus.getStatusList())if(status.isAfter(ChunkStatus.TERRAIN))
            for(Study s:studies)prepare(s,status,status==ChunkStatus.FULL?1:2);
        // Persist generation/postprocessing before recording the saved-world
        // snapshot, with every decoration origin around the sample already FULL.
        server.saveEverything(true,true,true);
        JsonArray full=new JsonArray();for(Study s:studies)full.add(scanFull(s));report.add("villages",full);
        report.addProperty("explicit_full_chunks",fullChunks.size());
        report.addProperty("scope","Limited actual villages; raw pre-feature and FULL columns; no villager AI/client rendering; native piece layout preserved");
    }
    private Location locate(MinecraftServer server,List<String> names,int x,int z,int radius){
        var registry=server.registryAccess().lookupOrThrow(Registries.STRUCTURE);List<Holder<Structure>> holders=new ArrayList<>();
        for(String n:names)holders.add(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("minecraft:"+n))));
        var found=generator.findNearestMapStructure(level,HolderSet.direct(holders),new BlockPos(x,80,z),radius,false);
        if(found==null)throw new AssertionError("No village in bounded search: "+names);
        return new Location(found.getSecond().unwrapKey().orElseThrow().identifier().toString(),Math.floorDiv(found.getFirst().getX(),16),Math.floorDiv(found.getFirst().getZ(),16));
    }
    private void prepare(Study s,ChunkStatus stage,int halo){
        var b=s.box;int x0=Math.floorDiv(b.minX()-24,16),z0=Math.floorDiv(b.minZ()-24,16),x1=Math.floorDiv(b.maxX()+24,16),z1=Math.floorDiv(b.maxZ()+24,16);
        if((x1-x0+1)*(z1-z0+1)>625)throw new AssertionError("Village exceeds fixed audit budget");
        for(int x=x0-halo;x<=x1+halo;x++)for(int z=z0-halo;z<=z1+halo;z++){
            var chunk=level.getChunk(x,z,stage);if(chunk==null||!chunk.getPersistedStatus().isOrAfter(stage))throw new AssertionError("Incomplete stage");
            if(stage==ChunkStatus.FULL)fullChunks.add(((long)x<<32)|(z&0xffffffffL));
        }
        System.out.println("VILLAGE_STAGE "+s.location+" "+stage);
    }
    private JsonObject identity(Study s){var r=G.toJsonTree(s.location).getAsJsonObject();r.add("sites",G.toJsonTree(s.model.sites()));r.addProperty("pieces",s.start.getPieces().size());return r;}
    private JsonObject scanTerrain(Study s)throws Exception {
        JsonObject r=identity(s);var deep=MessageDigest.getInstance("SHA-256");var outside=MessageDigest.getInstance("SHA-256");
        int samples=0,roofColumns=0,roofFailures=0,openColumns=0,rigidColumns=0,gradeErrors=0,predecorated=0;
        double minH=Double.POSITIVE_INFINITY,maxH=Double.NEGATIVE_INFINITY,maxFill=0,maxCut=0;
        JsonArray failures=new JsonArray(),outsideColumns=new JsonArray();Map<String,Integer> palette=new LinkedHashMap<>();BlockPos.MutableBlockPos p=new BlockPos.MutableBlockPos();
        for(int x=s.box.minX()-32;x<=s.box.maxX()+32;x+=2)for(int z=s.box.minZ()-32;z<=s.box.maxZ()+32;z+=2){
            var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16),ChunkStatus.TERRAIN);
            if(chunk.getPersistedStatus()!=ChunkStatus.TERRAIN){predecorated++;continue;}
            double h=height(x,z);var col=s.model.column(x,z,h,generator.getSeaLevel());samples++;
            int top=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x&15,z&15)+1;
            if(col.influence()>0){minH=Math.min(minH,h);maxH=Math.max(maxH,h);maxFill=Math.max(maxFill,col.height()-h);maxCut=Math.max(maxCut,h-col.height());}
            if(col.influence()==1){
                roofColumns++;boolean bad=false;
                for(int y=(int)Math.floor(col.height())-2;y>=(int)Math.floor(col.height())-12;y--){p.set(x,y,z);if(!chunk.getBlockState(p).isSolid())bad=true;}
                if(bad){roofFailures++;if(failures.size()<20){JsonObject f=new JsonObject();f.addProperty("x",x);f.addProperty("z",z);f.addProperty("target",col.height());f.addProperty("actual",top);failures.add(f);}}
                if(top<col.height()-4)openColumns++;
            }
            List<VillageTerrainModel.Site> cores=new ArrayList<>();
            for(var site:s.model.sites())if(site.rigid()&&site.distance(x,z)==0)cores.add(site);
            if(cores.size()==1&&col.influence()==1){rigidColumns++;if(Math.abs(top-cores.getFirst().groundY())>1)gradeErrors++;}
            for(int y=-48;y<=Math.min(32,(int)Math.floor(Math.min(h,col.height()))-48);y++){
                p.set(x,y,z);hash(deep,x+","+y+","+z+"="+chunk.getBlockState(p)+"\n");
            }
            if(col.influence()==0){
                JsonArray raw=new JsonArray();raw.add(x);raw.add(z);
                raw.add(Float.floatToIntBits((float)h));
                raw.add(Float.floatToIntBits(currentHeights.sampleValue(context,x,0,z)));
                raw.add(drainage==null?0:(Integer)waterAt.invoke(drainage,x,z));
                int last=-1, length=0;
                for(int y=-64;y<320;y+=2){p.set(x,y,z);String block=chunk.getBlockState(p).toString();
                    hash(outside,x+","+y+","+z+"="+block+"\n");
                    int id=palette.computeIfAbsent(block,key->palette.size());
                    if(id!=last&&length!=0){raw.add(length);raw.add(last);length=0;}
                    last=id;
                    length++;
                }
                raw.add(length);raw.add(last);
                outsideColumns.add(raw);
            }
        }
        r.addProperty("terrain_columns",samples);r.addProperty("predecorated_columns_not_used",predecorated);
        r.addProperty("protected_roof_columns",roofColumns);r.addProperty("roof_failure_columns",roofFailures);
        r.addProperty("open_hole_columns",openColumns);r.addProperty("rigid_columns",rigidColumns);r.addProperty("rigid_grade_error_columns",gradeErrors);
        r.addProperty("natural_height_span",samples==0?0:maxH-minH);r.addProperty("maximum_planned_fill",maxFill);r.addProperty("maximum_planned_cut",maxCut);
        r.add("outside_palette",G.toJsonTree(palette.keySet()));r.add("outside_columns",outsideColumns);
        r.addProperty("deep_sha256",hash(deep));r.addProperty("outside_sha256",hash(outside));r.add("examples",failures);return r;
    }
    private JsonObject scanFull(Study s)throws Exception {
        JsonObject r=identity(s);var blocks=MessageDigest.getInstance("SHA-256");
        int pathBlocks=0,bridgeBlocks=0,naturalRoadBlocks=0,pathVoids=0,farmland=0,farmVoids=0,doors=0,blockedDoors=0;
        int protectedPathVoids=0,protectedFarmVoids=0,protectedLava=0;Set<Long> seenDoors=new HashSet<>();
        JsonArray gaps=new JsonArray(),roadSurface=new JsonArray(),roadWalkability=new JsonArray();
        // A street's bounding box includes air, grass borders and occasionally a
        // neighboring house roof. Only DIRT_PATH blocks in the original native
        // template establish the identity of a processed crossing or grass patch.
        Set<Long> streetColumns=new HashSet<>();var sourceColumns=new ArrayList<BlockPos>();
        for(var piece:s.start.getPieces())if(piece instanceof PoolElementStructurePiece pool
                &&pool.getElement() instanceof SinglePoolElement street
                &&street.getProjection()==StructureTemplatePool.Projection.TERRAIN_MATCHING
                &&street.getTemplateLocation().getPath().startsWith("village/")
                &&street.getTemplateLocation().getPath().contains("/streets/")){
            var template=level.getStructureTemplateManager().get(street.getTemplateLocation()).orElseThrow();
            var placement=new StructurePlaceSettings().setRotation(pool.getRotation());
            for(var block:template.filterBlocks(pool.getPosition(),placement,Blocks.DIRT_PATH)){
                var p=block.pos();long key=columnKey(p.getX(),p.getZ());
                if(streetColumns.add(key))sourceColumns.add(new BlockPos(p.getX(),0,p.getZ()));
            }
        }
        sourceColumns.sort(Comparator.<BlockPos>comparingInt(p->p.getX()).thenComparingInt(p->p.getZ()));
        var pathSources=new JsonArray();var sourceHash=MessageDigest.getInstance("SHA-256");
        for(var p:sourceColumns){var xy=new JsonArray();xy.add(p.getX());xy.add(p.getZ());pathSources.add(xy);
            hash(sourceHash,p.getX()+","+p.getZ()+"\n");}
        r.add("street_path_columns",pathSources);r.addProperty("street_path_sha256",hash(sourceHash));
        var fullPalette=new LinkedHashMap<String,Integer>();var fullColumns=new JsonArray();
        var encoded=new IdentityHashMap<BlockState,byte[]>();var pos=new BlockPos.MutableBlockPos();
        for(int x=s.box.minX()-8;x<=s.box.maxX()+8;x+=4)for(int z=s.box.minZ()-8;z<=s.box.maxZ()+8;z+=4){
            var chunk=fullChunk(x,z);
            var row=new JsonArray();row.add(x);row.add(z);
            int last=-1,length=0;
            for(int y=-64;y<320;y++){
                var state=chunk.getBlockState(pos.set(x,y,z));
                blocks.update(encoded.computeIfAbsent(state,b->(b+"\n").getBytes(StandardCharsets.UTF_8)));
                int id=fullPalette.computeIfAbsent(state.toString(),key->fullPalette.size());
                if(id!=last&&length!=0){row.add(length);row.add(last);length=0;}
                last=id;length++;
            }
            row.add(length);row.add(last);fullColumns.add(row);
        }
        for(int x=s.box.minX();x<=s.box.maxX();x++)for(int z=s.box.minZ();z<=s.box.maxZ();z++){
            var chunk=fullChunk(x,z);
            double h=height(x,z);var column=s.model.column(x,z,h,generator.getSeaLevel());double expected=column.height();
            boolean street=streetColumns.contains(columnKey(x,z));
            int roadY=Integer.MIN_VALUE;String roadBlock="";
            for(int y=Math.max(-60,(int)expected-24);y<=Math.min(318,(int)expected+32);y++){
                pos.set(x,y,z);var b=chunk.getBlockState(pos);String name=id(b);
                boolean path=name.equals("minecraft:dirt_path");
                boolean bridge=street&&isStreetBridge(s.location.id,name)
                        &&(chunk.getFluidState(pos.below()).is(FluidTags.WATER)
                            ||chunk.getBlockState(pos.below()).is(Blocks.ICE)
                            ||chunk.getBlockState(pos.below()).isSolid())
                        &&passable(pos.above())&&passable(pos.above(2));
                // The native street_plains processor can leave a grass patch;
                // a later native disk feature can replace that grass with gravel.
                // Accept neither arbitrary grass nor an entire rectangular plot:
                // the exact native DIRT_PATH stencil and safe headroom are required.
                boolean natural=street&&(name.equals("minecraft:grass_block")||name.equals("minecraft:gravel"))
                        &&b.isFaceSturdy(level,pos,Direction.UP)&&chunk.getBlockState(pos.below()).isSolid()
                        &&passable(pos.above())&&passable(pos.above(2));
                if(path||bridge||natural){
                    if(path)pathBlocks++;
                    roadY=y;roadBlock=name;
                }
                if(path||name.equals("minecraft:farmland")){
                    boolean gap=false,airOrLava=false,lava=false;int first=0;String gapBlock="";
                    for(int d=1;d<=8;d++){
                        var below=chunk.getBlockState(new BlockPos(x,y-d,z));
                        boolean hot=id(below).equals("minecraft:lava");lava|=hot;airOrLava|=hot||below.isAir();
                        if(!below.isSolid()){gap=true;if(first==0){first=d;gapBlock=below.toString();}}
                    }
                    if(path){if(gap)pathVoids++;}else{farmland++;if(gap)farmVoids++;}
                    if(column.influence()==1){
                        if(lava)protectedLava++;
                        if(airOrLava){if(name.equals("minecraft:dirt_path"))protectedPathVoids++;else protectedFarmVoids++;}
                    }
                    if(gap&&gaps.size()<32){JsonObject e=new JsonObject();e.addProperty("x",x);e.addProperty("y",y);e.addProperty("z",z);e.addProperty("block",name);
                        e.addProperty("base_height",h);e.addProperty("grade_height",expected);e.addProperty("influence",column.influence());e.addProperty("first_gap_depth",first);e.addProperty("gap_block",gapBlock);gaps.add(e);}
                }
                if(name.endsWith("_door")&&b.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)&&b.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER&&seenDoors.add(pos.asLong())){
                    doors++;Direction f=b.getValue(BlockStateProperties.HORIZONTAL_FACING);boolean exit=false;
                    for(Direction d:List.of(f,f.getOpposite()))for(int dy=-1;dy<=1;dy++){
                        BlockPos q=new BlockPos(x,y,z).relative(d).offset(0,dy,0);
                        if(passable(q)&&passable(q.above())&&level.getBlockState(q.below()).isFaceSturdy(level,q.below(),Direction.UP))exit=true;
                    }if(!exit)blockedDoors++;
                }
            }
            if(roadY!=Integer.MIN_VALUE){
                if(isStreetBridge(s.location.id,roadBlock))bridgeBlocks++;
                else if(!roadBlock.equals("minecraft:dirt_path"))naturalRoadBlocks++;
                var road=new JsonArray();road.add(x);road.add(roadY);road.add(z);road.add(roadBlock);roadSurface.add(road);
                if(!roadBlock.equals("minecraft:dirt_path")){
                    var p=new BlockPos(x,roadY,z);var below=chunk.getBlockState(p.below());var evidence=new JsonArray();
                    evidence.add(x);evidence.add(roadY);evidence.add(z);
                    evidence.add(chunk.getBlockState(p).isFaceSturdy(level,p,Direction.UP));
                    evidence.add(passable(p.above()));evidence.add(passable(p.above(2)));
                    evidence.add(below.isSolid()||(isStreetBridge(s.location.id,roadBlock)
                            &&(below.getFluidState().is(FluidTags.WATER)||below.is(Blocks.ICE))));
                    roadWalkability.add(evidence);
                }
            }
        }
        r.addProperty("full_sha256",hash(blocks));r.addProperty("path_blocks",pathBlocks);r.addProperty("path_columns_with_void_within_8",pathVoids);
        r.addProperty("bridge_blocks",bridgeBlocks);r.addProperty("natural_road_blocks",naturalRoadBlocks);
        r.add("road_surface",roadSurface);r.add("road_walkability",roadWalkability);
        r.add("full_palette",G.toJsonTree(fullPalette.keySet()));r.add("full_columns",fullColumns);
        r.addProperty("farmland_blocks",farmland);r.addProperty("farmland_columns_with_void_within_8",farmVoids);
        r.addProperty("protected_path_air_or_lava_columns",protectedPathVoids);r.addProperty("protected_farm_air_or_lava_columns",protectedFarmVoids);
        r.addProperty("protected_floor_lava_columns",protectedLava);
        r.addProperty("doors",doors);r.addProperty("door_exit_warnings",blockedDoors);r.add("support_warning_examples",gaps);return r;
    }
    private ChunkAccess fullChunk(int x,int z){
        int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16);
        return observedFullChunks.computeIfAbsent(((long)cx<<32)|(cz&0xffffffffL),key->level.getChunk(cx,cz));
    }
    private static long columnKey(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static boolean isStreetBridge(String village,String block){
        return switch(village){
            case "minecraft:village_plains" -> block.equals("minecraft:oak_planks");
            case "minecraft:village_savanna" -> block.equals("minecraft:acacia_planks");
            case "minecraft:village_snowy","minecraft:village_taiga" -> block.equals("minecraft:spruce_planks");
            default -> false;
        };
    }
    private boolean passable(BlockPos p){var b=level.getBlockState(p);return b.getCollisionShape(level,p).isEmpty()&&b.getFluidState().isEmpty();}
}
