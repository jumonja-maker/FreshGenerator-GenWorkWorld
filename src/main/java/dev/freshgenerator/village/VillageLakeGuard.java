package dev.freshgenerator.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Reject the whole native lava-lake feature before it excavates a protected plot.
 * Exact 26.3 LakeFeature bounds: origin + [-8,7] X/Z and [-4,3] Y.
 * No existing-block writes, global cache, forced chunk requests or random draws.
 * Deep lakes and lakes away from village plots remain on the native path.
 */
public final class VillageLakeGuard {
    private VillageLakeGuard() { }
    public static boolean permit(WorldGenLevel level, ChunkGenerator generator, BlockPos origin) {
        if (!(level instanceof WorldGenRegion region) || !(generator instanceof NoiseBasedChunkGenerator noise)) return true;
        if (!noise.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("")
                .equals("freshgenerator:highlands_v2")) return true;
        var manager=region.getLevel().structureManager().forWorldGenRegion(region);
        if (!manager.shouldGenerateStructures()) return true;
        var state=region.getLevel().getChunkSource().randomState();
        int minX=origin.getX()-8,maxX=origin.getX()+7,minZ=origin.getZ()-8,maxZ=origin.getZ()+7;
        for(int cx=Math.floorDiv(minX,16);cx<=Math.floorDiv(maxX,16);cx++)
        for(int cz=Math.floorDiv(minZ,16);cz<=Math.floorDiv(maxZ,16);cz++) {
            var chunk=region.getChunk(cx,cz,ChunkStatus.STRUCTURE_STARTS,false);
            if(chunk==null)continue;
            var plan=VillageTerrainPlan.create(chunk,region,manager,noise.generatorSettings().value(),state);
            if(plan==VillageTerrainPlan.EMPTY)continue;
            for(int x=Math.max(minX,cx*16);x<=Math.min(maxX,cx*16+15);x++)
            for(int z=Math.max(minZ,cz*16);z<=Math.min(maxZ,cz*16+15);z++)
            for(int y=origin.getY()-4;y<=origin.getY()+3;y++)
                if(!plan.permitsCarving(x,y,z))return false;
        }
        return true;
    }
}
