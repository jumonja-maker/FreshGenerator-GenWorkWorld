package dev.freshgenerator.audit;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only observer of isolated CI worlds. This source set is excluded from the installable JAR. */
public final class TechnicalWorldAudit implements ModInitializer {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> NATURAL=Set.of("stone","deepslate","andesite","diorite","granite",
        "tuff","calcite","dripstone_block","dirt","grass_block","coarse_dirt","podzol","rooted_dirt",
        "mud","packed_mud","clay","gravel","sand","red_sand","sandstone","red_sandstone",
        "snow_block","ice","packed_ice","blue_ice","mycelium","moss_block","terracotta");
    private final Set<Long> requestedFullChunks=new HashSet<>();
    private final Map<Structure,String> structureNames=new IdentityHashMap<>();
    private final Map<String,StructureSet> sets=new TreeMap<>();
    private final IdentityHashMap<BlockState,Byte> classification=new IdentityHashMap<>();
    private final JsonObject report=new JsonObject();
    private final JsonArray warnings=new JsonArray();
    private ServerLevel level;
    private NoiseBasedChunkGenerator generator;
    private RandomState state;

    @Override public void onInitialize() {
        if(!Boolean.getBoolean("freshgenerator.audit"))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            boolean completed=false;
            try {run(server);completed=true;}
            catch(Throwable t){report.addProperty("failure",t.toString());t.printStackTrace();}
            finally {
                report.addProperty("completed",completed);
                report.addProperty("explicit_full_chunks_requested",requestedFullChunks.size());
                report.add("warnings",warnings);
                report.addProperty("scope","bounded native world audit; not exhaustive, no player navigation, no client render, no modpack");
                try {Files.writeString(Path.of("freshgenerator-audit.json"),GSON.toJson(report)+"\n");}
                catch(IOException e){e.printStackTrace();completed=false;}
                System.out.println((completed?"FRESHGENERATOR_AUDIT_PASS ":"FRESHGENERATOR_AUDIT_FAIL ")+new Gson().toJson(report));
                server.halt(false);
            }
        });
    }
    private void run(MinecraftServer server) throws Exception {
        level=server.overworld();
        if(!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g))throw new AssertionError("Not native noise generator");
        generator=g;
        String expected=Objects.requireNonNull(System.getenv("FG_AUDIT_SETTINGS"));
        String actual=generator.generatorSettings().unwrapKey().orElseThrow().toString();
        if(!actual.contains(expected))throw new AssertionError("World preset silently fell back: "+actual);
        report.addProperty("case",System.getenv("FG_AUDIT_CASE"));report.addProperty("seed",level.getSeed());
        report.addProperty("settings",actual);
        report.addProperty("structure_generation_enabled",server.getWorldData().worldGenOptions().generateStructures());
        if(!server.getWorldData().worldGenOptions().generateStructures())throw new AssertionError("Structures disabled");
        state=RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed(),g.generatorSettings().value());
        verifyNativePlacementRules(server);
        verifySpawn();
        JsonArray regions=new JsonArray();
        BlockPos spawn=level.getRespawnData().pos();
        regions.add(scanRegion("spawn",Math.floorDiv(spawn.getX(),16),Math.floorDiv(spawn.getZ(),16),3));
        regions.add(scanRegion("positive-negative-chunk-seams",0,0,3));
        // Matched geographic coordinates in FreshGenerator and vanilla, selected from the same FG seed.
        var fgSettings=server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(
            ResourceKey.create(Registries.NOISE_SETTINGS,Identifier.parse("freshgenerator:highlands_v2"))).value();
        var fgState=RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed(),fgSettings);
        var delta=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(
            ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse("freshgenerator:highlands_v2/height_delta"))).value();
        record Peak(int x,int z,float uplift){}
        List<Peak> candidates=new ArrayList<>();
        for(int x=-32;x<=32;x++)for(int z=-32;z<=32;z++)
            candidates.add(new Peak(x*512,z*512,fgState.sampleBlockValueUncached(delta,x*512,96,z*512)));
        candidates.sort(Comparator.comparingDouble((Peak p)->p.uplift()).reversed());
        Peak first=candidates.getFirst(),second=candidates.stream().filter(p->Math.abs(p.x()-first.x())+Math.abs(p.z()-first.z())>4096).findFirst().orElseThrow();
        if(first.uplift()<4)throw new AssertionError("No mountain signal");
        int index=0;
        for(Peak peak:List.of(first,second)){
            JsonObject region=scanRegion("mountain-"+(++index),Math.floorDiv(peak.x(),16),Math.floorDiv(peak.z(),16),5);
            region.addProperty("fg_selection_uplift",peak.uplift());regions.add(region);
        }
        report.add("regions",regions);
        JsonArray structures=new JsonArray();Set<String> inspected=new HashSet<>();
        List<String> villages=List.of("village_plains","village_desert","village_savanna","village_snowy","village_taiga");
        inspectNearest(server,villages,0,0,true,inspected,structures);
        inspectNearest(server,villages,-8192,8192,true,inspected,structures);
        inspectNearest(server,List.of("pillager_outpost"),0,0,false,inspected,structures);
        inspectNearest(server,List.of("desert_pyramid"),0,0,false,inspected,structures);
        inspectNearest(server,List.of("stronghold"),0,0,true,inspected,structures);
        report.add("structures",structures);
        long villageCount=structures.asList().stream().filter(e->e.getAsJsonObject().get("id").getAsString().contains("village_")).count();
        if(villageCount<1)throw new AssertionError("No village actually examined");
        report.addProperty("villages_examined",villageCount);
        report.addProperty("structure_types_examined",structures.asList().stream().map(e->e.getAsJsonObject().get("id").getAsString()).distinct().count());
        System.out.println("AUDIT_STAGE done seed="+level.getSeed());
    }
    private static String id(Holder<?> h){return h.unwrapKey().orElseThrow().identifier().toString();}
    private static JsonElement nativeJson(String registry,String id) throws IOException {
        String[] p=id.split(":",2);String path="/data/"+p[0]+"/worldgen/"+registry+"/"+p[1]+".json";
        try(InputStream in=TechnicalWorldAudit.class.getResourceAsStream(path)){
            if(in==null)throw new IOException("Missing native source "+path);
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private void verifyNativePlacementRules(MinecraftServer server) throws Exception {
        var ops=RegistryOps.create(JsonOps.INSTANCE,server.registryAccess());
        JsonObject definitions=new JsonObject();int structureCount=0,setCount=0,positions=0;
        for(var holder:server.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
            String name=id(holder);structureNames.put(holder.value(),name);if(!name.startsWith("minecraft:"))continue;
            Structure reference=Structure.DIRECT_CODEC.parse(ops,nativeJson("structure",name)).getOrThrow();
            JsonElement actual=Structure.DIRECT_CODEC.encodeStart(ops,holder.value()).getOrThrow();
            JsonElement original=Structure.DIRECT_CODEC.encodeStart(ops,reference).getOrThrow();
            if(!actual.equals(original))throw new AssertionError("Structure rules changed: "+name);
            definitions.add(name,actual);structureCount++;
        }
        for(var holder:server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList()) {
            String name=id(holder);sets.put(name,holder.value());if(!name.startsWith("minecraft:"))continue;
            var reference=StructureSet.DIRECT_CODEC.parse(ops,nativeJson("structure_set",name)).getOrThrow();
            var actual=StructureSet.DIRECT_CODEC.encodeStart(ops,holder.value()).getOrThrow();
            var original=StructureSet.DIRECT_CODEC.encodeStart(ops,reference).getOrThrow();
            if(!actual.equals(original))throw new AssertionError("Placement rules changed: "+name);
            definitions.add(name+"/placement",actual);setCount++;
            if(holder.value().placement() instanceof RandomSpreadStructurePlacement p && reference.placement() instanceof RandomSpreadStructurePlacement q) {
                for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++) {
                    int cx=x*p.spacing(),cz=z*p.spacing();
                    if(!p.getPotentialStructureChunk(level.getSeed(),cx,cz).equals(q.getPotentialStructureChunk(level.getSeed(),cx,cz)))
                        throw new AssertionError("Placement candidate changed: "+name);
                    positions++;
                }
            }
        }
        if(structureCount<20||setCount<10||positions==0)throw new AssertionError("Insufficient registry audit");
        JsonObject r=new JsonObject();r.addProperty("structures_equal_to_native",structureCount);r.addProperty("structure_sets_equal_to_native",setCount);
        r.addProperty("candidate_positions_compared",positions);r.addProperty("comparison","actual decoded registry codecs including salts, spacing, weights, biome tags, terrain adaptation");
        r.add("definitions",definitions);report.add("placement_rules",r);
        System.out.println("AUDIT_STAGE placement rules "+structureCount+" structures, "+positions+" candidates");
    }
    private void full(int cx,int cz){level.getChunk(cx,cz);requestedFullChunks.add(ChunkPos.asLong(cx,cz));}
    private static String blockId(BlockState s){return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();}
    private byte classify(BlockState s){return classification.computeIfAbsent(s,b->{
        if(b.isAir()||(!b.isSolid()&&!b.getFluidState().isEmpty()))return (byte)0;
        String name=blockId(b);boolean nativeBlock=name.startsWith("minecraft:");String path=nativeBlock?name.substring(10):name;
        return (byte)(nativeBlock&&(NATURAL.contains(path)||path.endsWith("_ore")||path.endsWith("_terracotta"))?3:1);
    });}
    private void verifySpawn(){
        BlockPos spawn=level.getRespawnData().pos();int safe=0;JsonArray examples=new JsonArray();
        for(int dx=-16;dx<=16;dx+=2)for(int dz=-16;dz<=16;dz+=2){
            int x=spawn.getX()+dx,z=spawn.getZ()+dz;full(Math.floorDiv(x,16),Math.floorDiv(z,16));
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);BlockPos feet=new BlockPos(x,y,z);
            if(passable(feet)&&passable(feet.above())&&level.getBlockState(feet.below()).isFaceSturdy(level,feet.below(),Direction.UP)){
                String ground=blockId(level.getBlockState(feet.below()));
                if(!Set.of("minecraft:magma_block","minecraft:cactus","minecraft:powder_snow").contains(ground)){
                    safe++;if(examples.size()<4)examples.add(GSON.toJsonTree(List.of(x,y,z)));
                }
            }
        }
        JsonObject r=new JsonObject();r.add("shared_spawn",GSON.toJsonTree(List.of(spawn.getX(),spawn.getY(),spawn.getZ())));
        r.addProperty("columns_checked",289);r.addProperty("dry_supported_two_block_spaces",safe);r.add("examples",examples);
        r.addProperty("scope","standing space near shared spawn, not actual player spawn packet/navigation");report.add("spawn",r);
        if(safe==0)throw new AssertionError("No dry supported standing space near spawn");
    }
    private boolean passable(BlockPos p){var b=level.getBlockState(p);return b.getFluidState().isEmpty()&&b.getCollisionShape(level,p).isEmpty();}
    private JsonObject scanRegion(String label,int cx,int cz,int side){
        int half=side/2,minCX=cx-half,minCZ=cz-half,width=side*16,depth=width,minY=48,height=272;
        // FULL halo first: no implicit missing neighbour is ever treated as air.
        for(int x=minCX-1;x<=minCX+side;x++)for(int z=minCZ-1;z<=minCZ+side;z++)full(x,z);
        Set<BoundingBox> protectedBoxes=new HashSet<>();
        for(int x=minCX;x<minCX+side;x++)for(int z=minCZ;z<minCZ+side;z++) {
            var chunk=level.getChunk(x,z);
            chunk.getAllStarts().values().stream().filter(StructureStart::isValid).forEach(s->s.getPieces().forEach(p->protectedBoxes.add(p.getBoundingBox())));
            for(var entry:chunk.getAllReferences().entrySet())for(long key:entry.getValue()) {
                ChunkPos pos=new ChunkPos(key);var root=level.getChunk(pos.x,pos.z,ChunkStatus.STRUCTURE_STARTS);
                var start=root.getStartForStructure(entry.getKey());
                if(start!=null&&start.isValid())start.getPieces().forEach(p->protectedBoxes.add(p.getBoundingBox()));
            }
        }
        byte[] cells=new byte[width*depth*height];BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++) {
            int wx=minCX*16+x,wz=minCZ*16+z;var chunk=level.getChunk(Math.floorDiv(wx,16),Math.floorDiv(wz,16));
            for(int y=0;y<height;y++){
                pos.set(wx,minY+y,wz);byte kind=classify(chunk.getBlockState(pos));
                if((kind&1)!=0){for(var box:protectedBoxes)if(box.isInside(pos)){kind|=4;break;}}
                cells[y*width*depth+z*width+x]=kind;
            }
        }
        var components=VoxelConnectivity.scan(cells,width,height,depth);
        JsonArray candidates=new JsonArray();int sky=0,belowSea=0,protectedCount=0,cave=0,singletons=0;long naturalBlocks=0;
        for(var c:components.detached()) {
            if(c.protectedStructure()){protectedCount++;continue;}
            if(c.minY()+minY<=level.getSeaLevel()){belowSea++;continue;}
            if(!c.skyExposed()){cave++;continue;}
            sky++;naturalBlocks+=c.naturalBlocks();if(c.blocks()==1)singletons++;
            JsonObject q=new JsonObject();q.addProperty("component_blocks",c.blocks());q.addProperty("natural_blocks",c.naturalBlocks());
            q.add("min",GSON.toJsonTree(List.of(minCX*16+c.minX(),minY+c.minY(),minCZ*16+c.minZ())));
            q.add("max",GSON.toJsonTree(List.of(minCX*16+c.maxX(),minY+c.maxY(),minCZ*16+c.maxZ())));
            candidates.add(q);
        }
        JsonObject r=new JsonObject();r.addProperty("label",label);r.addProperty("min_chunk_x",minCX);r.addProperty("min_chunk_z",minCZ);
        r.addProperty("full_chunks_scanned",side*side);r.addProperty("scanned_block_positions",cells.length);
        r.addProperty("y_min",minY);r.addProperty("y_max",319);r.addProperty("occupied_blocks",components.occupiedBlocks());
        r.addProperty("grounded_components",components.groundedComponents());r.addProperty("boundary_unknown_components",components.boundaryUnknownComponents());
        r.addProperty("sky_exposed_detached_terrain_components",sky);r.addProperty("detached_natural_blocks",naturalBlocks);
        r.addProperty("isolated_single_blocks",singletons);r.addProperty("structure_components_excluded",protectedCount);
        r.addProperty("below_sea_components_excluded",belowSea);r.addProperty("non_sky_cave_components",cave);r.add("detached_candidates",candidates);
        int min=320,max=-64;Set<Integer> heights=new HashSet<>();Set<String> biomes=new TreeSet<>();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++){
            int wx=cx*16+x*16,wz=cz*16+z*16,h=generator.getBaseHeight(wx,wz,Heightmap.Types.WORLD_SURFACE_WG,level,state);
            if(h < -64||h>320)throw new AssertionError("Height outside dimension");
            min=Math.min(min,h);max=Math.max(max,h);heights.add(h);
            biomes.add(id(level.getUncachedNoiseBiome(Math.floorDiv(wx,4),Math.floorDiv(h,4),Math.floorDiv(wz,4))));
        }
        r.addProperty("height_columns",81);r.addProperty("min_base_height",min);r.addProperty("max_base_height",max);r.addProperty("distinct_base_heights",heights.size());
        r.add("sample_biomes",GSON.toJsonTree(biomes));
        if(sky>0)warnings.add(label+": "+sky+" sky-exposed detached natural components; inspect coordinates, no automatic deletion");
        System.out.println("AUDIT_STAGE region "+label+" detached="+sky+" singletons="+singletons+" min="+min+" max="+max);
        return r;
    }
    private void inspectNearest(MinecraftServer server,List<String> names,int x,int z,boolean required,Set<String> inspected,JsonArray out){
        var registry=server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> holders=new ArrayList<>();
        for(String name:names)holders.add(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("minecraft:"+name))));
        var found=generator.findNearestMapStructure(level,HolderSet.direct(holders),new BlockPos(x,64,z),16,false);
        if(found==null){if(required)throw new AssertionError("Required structure not located: "+names);warnings.add("Not located in bounded search: "+names);return;}
        String name=id(found.getSecond());Structure structure=found.getSecond().value();ChunkPos at=new ChunkPos(found.getFirst());
        StructureStart start=level.getChunk(at.x,at.z,ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure);
        if(start==null||!start.isValid()||start.getPieces().isEmpty())throw new AssertionError("Locate gave missing structure start: "+name);
        String unique=name+"@"+start.getChunkPos();if(!inspected.add(unique))return;
        BoundingBox box=start.getBoundingBox();
        int minCX=Math.floorDiv(box.minX(),16)-1,minCZ=Math.floorDiv(box.minZ(),16)-1;
        int maxCX=Math.floorDiv(box.maxX(),16)+1,maxCZ=Math.floorDiv(box.maxZ(),16)+1;
        int footprint=(maxCX-minCX+1)*(maxCZ-minCZ+1);
        if(footprint>625)throw new AssertionError("Structure exceeds explicit audit budget: "+name+" "+footprint);
        for(int cx=minCX;cx<=maxCX;cx++)for(int cz=minCZ;cz<=maxCZ;cz++)full(cx,cz);
        int validPositions=0;
        JsonArray placements=new JsonArray();
        for(var entry:sets.entrySet()){
            if(entry.getValue().structures().stream().noneMatch(e->e.structure().value()==structure))continue;
            if(entry.getValue().placement() instanceof RandomSpreadStructurePlacement p){
                boolean match=p.getPotentialStructureChunk(level.getSeed(),start.getChunkPos().x,start.getChunkPos().z).equals(start.getChunkPos());
                if(!match)throw new AssertionError("Start violates placement candidate: "+unique);
                validPositions++;placements.add(entry.getKey());
            }else placements.add(entry.getKey()+" (non-random-spread; codec rules checked)");
        }
        if(placements.isEmpty())throw new AssertionError("No structure set for "+name);
        JsonObject r=new JsonObject();r.addProperty("id",name);r.add("start_chunk",GSON.toJsonTree(List.of(start.getChunkPos().x,start.getChunkPos().z)));
        r.add("bounds",GSON.toJsonTree(List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ())));
        r.addProperty("pieces",start.getPieces().size());r.addProperty("full_footprint_chunks",footprint);r.add("placement_sets",placements);
        r.addProperty("random_spread_start_positions_verified",validPositions);r.addProperty("terrain_adaptation",structure.terrainAdaptation().toString());
        int eligible=0;Set<String> biomeSamples=new TreeSet<>();
        for(var piece:start.getPieces()){
            BlockPos p=piece.getLocatorPosition();var b=level.getUncachedNoiseBiome(Math.floorDiv(p.getX(),4),Math.floorDiv(p.getY(),4),Math.floorDiv(p.getZ(),4));
            biomeSamples.add(id(b));if(structure.biomes().contains(b))eligible++;
        }
        r.addProperty("piece_locator_biome_samples_allowed",eligible);r.add("piece_locator_biomes",GSON.toJsonTree(biomeSamples));
        r.addProperty("biome_check_scope","native selector/rules retained; piece locators are observations, not the original generation stub");
        if(name.contains("village_"))r.add("village_support",inspectVillage(start));
        out.add(r);
        System.out.println("AUDIT_STAGE structure "+unique+" pieces="+start.getPieces().size()+" footprint="+footprint);
    }
    private JsonObject inspectVillage(StructureStart start){
        JsonArray pieces=new JsonArray();int rigid=0,columns=0,largeGaps=0,suspendedPieces=0,doors=0,blockedDoors=0,bedBlocks=0;
        Set<Long> checkedDoors=new HashSet<>(),checkedBeds=new HashSet<>();
        for(var piece:start.getPieces()){
            if(!(piece instanceof PoolElementStructurePiece pool)||pool.getElement().getProjection()!=StructureTemplatePool.Projection.RIGID)continue;
            BoundingBox box=piece.getBoundingBox();
            int pieceCols=0,deep=0,maxGap=0;
            int floorTop=Math.min(box.maxY(),box.minY()+Math.max(4,pool.getGroundLevelDelta()+2));
            for(int x=box.minX();x<=box.maxX();x++)for(int z=box.minZ();z<=box.maxZ();z++){
                int lowest=-1;
                for(int y=Math.max(-64,box.minY());y<=Math.min(319,floorTop);y++){
                    var s=level.getBlockState(new BlockPos(x,y,z));if(s.isSolid()){lowest=y;break;}
                }
                if(lowest<0)continue;
                int gap=0;
                for(int y=lowest-1;y>=-64&&gap<=32;y--){var b=level.getBlockState(new BlockPos(x,y,z));if(b.isSolid()||!b.getFluidState().isEmpty())break;gap++;}
                pieceCols++;if(gap>8)deep++;maxGap=Math.max(maxGap,gap);
            }
            for(int x=box.minX();x<=box.maxX();x++)for(int z=box.minZ();z<=box.maxZ();z++)for(int y=Math.max(-64,box.minY());y<=Math.min(319,box.maxY());y++){
                BlockPos p=new BlockPos(x,y,z);var b=level.getBlockState(p);String name=blockId(b);
                if(name.endsWith("_bed")&&checkedBeds.add(p.asLong()))bedBlocks++;
                if(name.endsWith("_door")&&b.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    &&b.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER&&checkedDoors.add(p.asLong())){
                    doors++;Direction f=b.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    boolean exit=false;
                    for(Direction d:List.of(f,f.getOpposite()))for(int step=-1;step<=1;step++){
                        BlockPos q=p.relative(d).offset(0,step,0);
                        if(passable(q)&&passable(q.above())&&level.getBlockState(q.below()).isFaceSturdy(level,q.below(),Direction.UP))exit=true;
                    }
                    if(!exit)blockedDoors++;
                }
            }
            rigid++;columns+=pieceCols;largeGaps+=deep;
            boolean suspended=pieceCols>=4&&deep==pieceCols;if(suspended)suspendedPieces++;
            if(deep>0||suspended){JsonObject p=new JsonObject();p.add("bounds",GSON.toJsonTree(List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ())));p.addProperty("floor_columns",pieceCols);p.addProperty("air_gaps_over_8",deep);p.addProperty("max_air_gap",maxGap);p.addProperty("all_sampled_columns_over_void",suspended);pieces.add(p);}
        }
        JsonObject r=new JsonObject();r.addProperty("rigid_pieces_checked",rigid);r.addProperty("solid_floor_columns_checked",columns);
        r.addProperty("floor_columns_with_air_gap_over_8",largeGaps);r.addProperty("all_columns_over_void_piece_candidates",suspendedPieces);
        r.addProperty("doors_checked",doors);r.addProperty("doors_without_local_supported_exit",blockedDoors);r.addProperty("bed_blocks",bedBlocks);r.add("piece_warnings",pieces);
        r.addProperty("scope","geometric floor/door screening; overhangs and terrain-matching roads are not declared broken; no villager navigation simulation");
        if(suspendedPieces>0||blockedDoors>0)warnings.add("Village "+start.getChunkPos()+": suspended-piece candidates="+suspendedPieces+", locally blocked door candidates="+blockedDoors);
        return r;
    }
}
