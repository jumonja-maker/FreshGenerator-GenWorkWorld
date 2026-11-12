package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.hydrology.HydrologyAquifer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class HydrologyCarverMixin {
    @WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;applyCarvingMask(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/CarvingMask;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/NoiseChunk;Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/CarvingMask$Filter;)V"))
    private void freshgenerator$seal(NoiseBasedChunkGenerator generator, ChunkAccess chunk, CarvingMask mask,
            RandomState state, MaterialRule materials, WorldGenerationContext generation, NoiseChunk noise,
            Function<BlockPos, Holder<Biome>> biomes, CarvingMask.Filter filter, Operation<Void> original) {
        if (noise.aquifer() instanceof HydrologyAquifer aquifer) {
            var pos = chunk.getPos();
            var nativeFilter = filter;
            filter = (x, y, z) -> (nativeFilter == null || nativeFilter.test(x, y, z))
                    && !aquifer.protects(pos.getBlockX(x), y, pos.getBlockZ(z));
        }
        original.call(generator, chunk, mask, state, materials, generation, noise, biomes, filter);
    }
}
