package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.core.CaveAccessModel;
import dev.freshgenerator.village.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.Function;

/** Local integration BEFORE materials, features, houses and trees. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class VillageTerrainMixin {
    @WrapOperation(method="lambda$buildTerrain$0",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;createNoiseChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/NoiseSettings;)Lnet/minecraft/world/level/levelgen/NoiseChunk;"),require=1)
    private NoiseChunk freshgenerator$plan(NoiseBasedChunkGenerator generator,ChunkAccess chunk,StructureManager manager,
        Blender blender,RandomState state,NoiseSettings noiseSettings,Operation<NoiseChunk> original,
        @Local(argsOnly=true) WorldGenRegion region) {
        NoiseChunk noise=original.call(generator,chunk,manager,blender,state,noiseSettings);
        if(generator.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(CaveAccessModel.SETTINGS))
            ((VillageNoiseAccess)noise).freshgenerator$villagePlan(VillageTerrainPlan.create(chunk,region,manager,generator.generatorSettings().value(),state));
        return noise;
    }
    @WrapOperation(method="doFill",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance(IIID)Lnet/minecraft/world/level/block/state/BlockState;"),require=1)
    private BlockState freshgenerator$grade(Aquifer aquifer,int x,int y,int z,double density,Operation<BlockState> original,
        NoiseChunk noise,ChunkAccess chunk) {
        return original.call(aquifer,x,y,z,((VillageNoiseAccess)noise).freshgenerator$villagePlan().density(density,x,y,z));
    }
    @WrapOperation(method="generateCarvers",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;applyCarvingMask(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/CarvingMask;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/NoiseChunk;Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/CarvingMask$Filter;)V"),require=1)
    private void freshgenerator$roof(NoiseBasedChunkGenerator generator,ChunkAccess chunk,CarvingMask mask,RandomState state,
        MaterialRule materials,WorldGenerationContext generation,NoiseChunk noise,Function<BlockPos,Holder<Biome>> biomes,
        CarvingMask.Filter filter,Operation<Void> original) {
        VillageTerrainPlan plan=((VillageNoiseAccess)noise).freshgenerator$villagePlan();
        if(plan!=VillageTerrainPlan.EMPTY) {
            CarvingMask.Filter nativeFilter=filter;
            filter=(x,y,z)->(nativeFilter==null||nativeFilter.test(x,y,z))
                &&plan.permitsCarving(chunk.getPos().getBlockX(x),y,chunk.getPos().getBlockZ(z));
        }
        original.call(generator,chunk,mask,state,materials,generation,noise,biomes,filter);
    }
}
