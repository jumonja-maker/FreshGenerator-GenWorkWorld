package dev.freshgenerator.hydrology;

import dev.freshgenerator.village.VillageTerrainPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/** Keep naturally generated tree trunks out of already planned native streets.
 * Uses prepared structure references only: no forced chunks, global cache or RNG.
 */
public final class VillageTreeGuard {
    private VillageTreeGuard() { }

    public static boolean permit(WorldGenLevel level, BlockPos origin, DrainageDensity.Sampler drainage) {
        if (!(level instanceof WorldGenRegion region)) return true;
        // The direct block check also protects a road/farm already placed by a
        // neighboring feature origin. The metadata check below does not depend
        // on whether that origin has decorated its street yet.
        for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++) {
            var ground = region.getBlockState(origin.offset(dx, -1, dz));
            if (ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)) return false;
        }
        var registry = region.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (int cz = Math.floorDiv(origin.getZ(), 16); cz <= Math.floorDiv(origin.getZ() + 1, 16); cz++) {
            for (int cx = Math.floorDiv(origin.getX(), 16); cx <= Math.floorDiv(origin.getX() + 1, 16); cx++) {
                var target = region.getChunk(cx, cz, ChunkStatus.STRUCTURE_REFERENCES, false);
                if (target == null) continue;
                for (var reference : target.getAllReferences().entrySet()) {
                    var key = registry.getResourceKey(reference.getKey());
                    if (key.isEmpty() || !VillageTerrainPlan.villageId(key.get().identifier().toString())) continue;
                    for (long packed : reference.getValue()) {
                        var startPos = ChunkPos.unpack(packed);
                        var source = region.getChunk(startPos.x(), startPos.z(), ChunkStatus.STRUCTURE_STARTS, false);
                        if (source == null) continue;
                        var start = source.getStartForStructure(reference.getKey());
                        if (start == null || !start.isValid()) continue;
                        for (var piece : start.getPieces()) {
                            if (!(piece instanceof PoolElementStructurePiece pool)
                                    || !(pool.getElement() instanceof SinglePoolElement street)
                                    || street.getProjection() != StructureTemplatePool.Projection.TERRAIN_MATCHING
                                    || !street.getTemplateLocation().getPath().startsWith("village/")
                                    || !street.getTemplateLocation().getPath().contains("/streets/")) continue;
                            if (!intersectsTrunk(pool.getBoundingBox(), origin.getX(), origin.getZ())) continue;
                            var context = SamplerContext.builder().enableCaches().build();
                            float surface = drainage.originalHeightAt(context, origin.getX(), origin.getZ());
                            // A cave tree far below an elevated street is not a
                            // street obstruction. Surface trunks use its terrain
                            // reference, not the unprojected template Y position.
                            if (origin.getY() >= Math.floor(surface) - 8) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    static boolean intersectsTrunk(BoundingBox street, int x, int z) {
        return x <= street.maxX() && (long) x + 1 >= street.minX()
                && z <= street.maxZ() && (long) z + 1 >= street.minZ();
    }
}
