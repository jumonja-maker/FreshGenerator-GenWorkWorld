package dev.freshgenerator.village;

import dev.freshgenerator.core.CaveAccessModel;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import java.util.*;

/** Immutable per-chunk plan from real, already prepared starts. No forced chunks or RNG. */
public final class VillageTerrainPlan {
    public static final VillageTerrainPlan EMPTY = new VillageTerrainPlan();
    private static final Set<String> VILLAGES = Set.of("minecraft:village_plains", "minecraft:village_desert",
        "minecraft:village_savanna", "minecraft:village_snowy", "minecraft:village_taiga");
    private final int x0, z0;
    private final VillageTerrainModel.Column[] columns;
    private VillageTerrainPlan() { x0=0; z0=0; columns=null; }
    private VillageTerrainPlan(ChunkPos pos, VillageTerrainModel model, NoiseGeneratorSettings settings, RandomState state) {
        x0=pos.getMinBlockX(); z0=pos.getMinBlockZ(); columns=new VillageTerrainModel.Column[256];
        DensitySampler height=state.getSampler(settings.debugFunctions().functions().stream()
            .filter(e->e.label().equals(CaveAccessModel.HEIGHT_LABEL)).findFirst().orElseThrow().function());
        SamplerContext context=SamplerContext.builder().enableCaches().build();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            int wx=x0+x,wz=z0+z;
            // Outside a village apron, a null slot is precisely a zero-influence Column.
            if(!model.hasNearbySite(wx,wz))continue;
            var column=model.column(wx,wz,height.sampleValue(context,wx,0,wz),settings.seaLevel());
            if(column.influence()!=0)columns[x*16+z]=column;
        }
    }
    public static boolean villageId(String id) { return VILLAGES.contains(id); }
    public static VillageTerrainPlan create(ChunkAccess target,WorldGenRegion region,StructureManager manager,
                                             NoiseGeneratorSettings settings,RandomState state) {
        if(region==null||!manager.shouldGenerateStructures())return EMPTY;
        checkedOrigin(target,region);
        return createWithPieces(target,region,settings,state,villagePieces(region,manager));
    }
    private static ChunkPos checkedOrigin(ChunkAccess target,WorldGenRegion region) {
        ChunkPos origin=target.getPos(),metadataOrigin=region.getCenter();
        if(Math.abs((long)origin.x()-metadataOrigin.x())>1||Math.abs((long)origin.z()-metadataOrigin.z())>1)
            throw new IllegalArgumentException("Village target outside the current terrain/lake footprint");
        return origin;
    }
    /** One local, immutable snapshot per native lava-lake invocation; never global. */
    public static List<PoolElementStructurePiece> villagePieces(WorldGenRegion region,StructureManager manager) {
        if(region==null||!manager.shouldGenerateStructures())return List.of();
        ChunkPos metadataOrigin=region.getCenter();
        var registry=manager.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Set<StructureStart> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        List<PoolElementStructurePiece> pieces=new ArrayList<>();
        // The prepared dependency radius belongs to region.getCenter(), not a lake target.
        // Original +/-8 start horizon and original oversized-layout fallback are preserved.
        for(int cx=metadataOrigin.x()-8;cx<=metadataOrigin.x()+8;cx++)for(int cz=metadataOrigin.z()-8;cz<=metadataOrigin.z()+8;cz++) {
            ChunkAccess chunk=region.getChunk(cx,cz,ChunkStatus.STRUCTURE_STARTS,false);
            if(chunk==null)continue;
            for(StructureStart start:chunk.getAllStarts().values()) {
                if(!start.isValid()||!seen.add(start))continue;
                var key=registry.getResourceKey(start.getStructure());
                if(key.isEmpty()||!villageId(key.get().identifier().toString()))continue;
                int sx=start.getChunkPos().getMinBlockX(),sz=start.getChunkPos().getMinBlockZ();
                if(start.getPieces().stream().anyMatch(p->{var b=p.getBoundingBox();return b.minX()<sx-96
                    ||b.maxX()>sx+96||b.minZ()<sz-96||b.maxZ()>sz+96;}))continue;
                for(StructurePiece piece:start.getPieces())
                    if(piece instanceof PoolElementStructurePiece pool)pieces.add(pool);
            }
        }
        return pieces.isEmpty()?List.of():List.copyOf(pieces);
    }
    /** Target-specific piece selection still runs independently for each overlapped chunk. */
    public static VillageTerrainPlan createWithPieces(ChunkAccess target,WorldGenRegion region,
            NoiseGeneratorSettings settings,RandomState state,List<PoolElementStructurePiece> pieces) {
        if(region==null||pieces.isEmpty())return EMPTY;
        ChunkPos origin=checkedOrigin(target,region);
        List<VillageTerrainModel.Site> sites=new ArrayList<>();
        for(PoolElementStructurePiece pool:pieces) {
            if(!pool.isCloseToChunk(origin,24))continue;
            BoundingBox b=pool.getBoundingBox();
            int ground=b.minY()+pool.getGroundLevelDelta();
            if(ground<settings.seaLevel()-4||ground>300)continue;
            boolean rigid=pool.getElement().getProjection()==StructureTemplatePool.Projection.RIGID
                &&(long)(b.maxX()-b.minX()+1)*(b.maxZ()-b.minZ()+1)>=16&&b.maxY()-b.minY()>=3;
            sites.add(new VillageTerrainModel.Site(b.minX(),b.minZ(),b.maxX(),b.maxZ(),ground,rigid));
        }
        return sites.isEmpty()?EMPTY:new VillageTerrainPlan(origin,new VillageTerrainModel(sites),settings,state);
    }
    private VillageTerrainModel.Column column(int x,int z) {
        if(columns==null)return null;
        int dx=x-x0,dz=z-z0;
        return (dx|dz)<0||dx>=16||dz>=16?null:columns[dx*16+dz];
    }
    public double density(double original,int x,int y,int z) {
        var column=column(x,z);return column==null?original:column.density(original,y);
    }
    public boolean permitsCarving(int x,int y,int z) {
        var column=column(x,z);return column==null||column.permitsCarving(y);
    }
}
