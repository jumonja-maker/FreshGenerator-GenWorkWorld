package dev.freshgenerator;
import java.lang.reflect.*;
import java.util.*;
/** Inspection of the actual resolved game classes. Not packaged in the mod. */
public final class ApiProbe {
    public static void main(String[] args) throws Exception {
        for (String name : List.of(
            "net.minecraft.world.level.chunk.ChunkGenerator",
            "net.minecraft.world.level.chunk.ChunkAccess",
            "net.minecraft.world.level.levelgen.structure.Structure",
            "net.minecraft.world.level.levelgen.structure.StructureStart",
            "net.minecraft.world.level.levelgen.structure.StructurePiece",
            "net.minecraft.world.level.levelgen.structure.StructureSet",
            "net.minecraft.world.level.levelgen.structure.placement.StructurePlacement",
            "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement",
            "net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement",
            "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement",
            "net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece",
            "net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement",
            "net.minecraft.world.level.levelgen.RandomState",
            "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.level.levelgen.structure.BoundingBox",
            "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
            "net.minecraft.world.level.levelgen.structure.Structure$StructureSettings")) {
            Class<?> t = Class.forName(name, false, ApiProbe.class.getClassLoader());
            System.out.println("API_PROBE " + name);
            Arrays.stream(t.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers()))
                .sorted(Comparator.comparing(Method::toGenericString)).forEach(m->System.out.println(m.toGenericString()));
            Arrays.stream(t.getDeclaredFields()).filter(f->Modifier.isPublic(f.getModifiers()))
                .forEach(f->System.out.println(f.toGenericString()));
        }
    }
}
