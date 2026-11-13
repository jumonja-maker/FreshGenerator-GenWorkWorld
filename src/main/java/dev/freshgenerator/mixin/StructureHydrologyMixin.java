package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.freshgenerator.hydrology.HydrologyAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Predicate;

/** Native starts and pieces use the same elevation reference as the published world. */
@Mixin(Structure.class)
abstract class StructureHydrologyMixin {
    @WrapMethod(method = "generate")
    private StructureStart freshgenerator$planOnOriginalTerrain(Holder<Structure> structure, ResourceKey<Level> dimension,
            RegistryAccess registry, ChunkGenerator generator, BiomeSource biomes, Climate.Sampler climate, RandomState state,
            StructureTemplateManager templates, long seed, ChunkPos pos, int references, LevelHeightAccessor height,
            Predicate<Holder<Biome>> valid, Operation<StructureStart> original) {
        if (((HydrologyAccess) (Object) state).freshgenerator$drainage() == null) {
            return original.call(structure, dimension, registry, generator, biomes, climate, state, templates, seed,
                    pos, references, height, valid);
        }
        return ScopedValue.where(HydrologyAccess.STRUCTURE_PLANNING, true).call(() ->
                original.call(structure, dimension, registry, generator, biomes, climate, state, templates, seed,
                        pos, references, height, valid));
    }
}
