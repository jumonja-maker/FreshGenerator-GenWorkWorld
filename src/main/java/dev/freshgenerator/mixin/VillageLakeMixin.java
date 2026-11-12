package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.village.VillageLakeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Generation-only, identified native lake; commands/nested features/other worlds are untouched. */
@Mixin(FeaturePlacer.class)
abstract class VillageLakeMixin {
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),require=2)
 private boolean freshgenerator$protectVillageFromLake(Feature feature,WorldGenLevel level,ChunkGenerator generator,
   RandomSource random,BlockPos at,Operation<Boolean> original,PlacedFeature placed,RandomSource sourceRandom,BlockPos origin,boolean biomeCheck) {
  if(biomeCheck && feature instanceof LakeFeature
      && placed.feature().unwrapKey().map(k->k.identifier().toString()).orElse("").equals("minecraft:lake_lava")
      && !VillageLakeGuard.permit(level,generator,at)) return false;
  return original.call(feature,level,generator,random,at);
 }
}
