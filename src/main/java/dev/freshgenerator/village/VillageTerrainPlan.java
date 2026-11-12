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
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)
            columns[x*16+z]=model.column(x0+x,z0+z,height.sampleValue(context,x0+x,0,z0+z),settings.seaLevel());
    }
    public static boolean villageId(String id) { return VILLAGES.contains(id); }
    public static VillageTerrainPlan create(ChunkAccess target, WorldGenRegion region, StructureManager manager,
                                             NoiseGeneratorSettings settings, RandomState state) {
        if(region==null || !manager.shouldGenerateStructures())return EMPTY;
        ChunkPos origin=target.getPos();
        var registry=manager.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Set<StructureStart> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        List<VillageTerrainModel.Site> sites=new ArrayList<>();
        // The same already available radius used by native carvers. create=false never upgrades chunks.
        for(int cx=origin.x()-8;cx<=origin.x()+8;cx++)for(int cz=origin.z()-8;cz<=origin.z()+8;cz++) {
            ChunkAccess chunk=region.getChunk(cx,cz,ChunkStatus.STRUCTURE_STARTS,false);
            if(chunk==null)continue;
            for(StructureStart start:chunk.getAllStarts().values()) {
                if(!start.isValid() || !seen.add(start))continue;
                var key=registry.getResourceKey(start.getStructure());
                if(key.isEmpty() || !villageId(key.get().identifier().toString()))continue;
                // Consistent finite metadata horizon for unusually enlarged datapack villages.
                int sx=start.getChunkPos().getMinBlockX(),sz=start.getChunkPos().getMinBlockZ();
                if(start.getPieces().stream().anyMatch(p->{var b=p.getBoundingBox();return b.minX()<sx-96
                    || b.maxX()>sx+96 || b.minZ()<sz-96 || b.maxZ()>sz+96;}))continue;
                for(StructurePiece piece:start.getPieces()) {
                    if(!(piece instanceof PoolElementStructurePiece pool) || !piece.isCloseToChunk(origin,24))continue;
                    BoundingBox b=piece.getBoundingBox();
                    int ground=b.minY()+pool.getGroundLevelDelta();
                    if(ground<settings.seaLevel()-4 || ground>300)continue;
                    boolean rigid=pool.getElement().getProjection()==StructureTemplatePool.Projection.RIGID
                        && (long)(b.maxX()-b.minX()+1)*(b.maxZ()-b.minZ()+1)>=16 && b.maxY()-b.minY()>=3;
                    sites.add(new VillageTerrainModel.Site(b.minX(),b.minZ(),b.maxX(),b.maxZ(),ground,rigid));
                }
            }
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
