package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.*;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.freshgenerator.cave.CaveCarverGuard;
import dev.freshgenerator.core.CaveAccessModel;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve native carver start chances, paths, RNG calls and downstream safety filters.
 * Only the three registered vanilla Overworld carvers in our single current world are scoped.
 * Shared state is a local variable of this invocation, safe for parallel chunk generation.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class CaveCarverMixin {
 @WrapOperation(method="generateCarvers",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/carver/WorldCarver;carve(Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/CarverOutput;)Z"),require=1)
 private boolean freshgenerator$surfaceAccess(WorldCarver carver,WorldGenerationContext generation,RandomSource random,
   ChunkPos target,ChunkPos source,CarverOutput output,Operation<Boolean> original,
   ChunkAccess chunk,Blender blender,NoiseChunk noise,RandomState state,BiomeManager biomes,WorldGenRegion region,MaterialRule materials,
   @Local(ordinal=0) Holder<WorldCarver> definition,@Share("surfaceCaveGuard") LocalRef<CaveCarverGuard> shared) {
  var generator=(NoiseBasedChunkGenerator)(Object)this;
  if(generator.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(CaveAccessModel.SETTINGS)) {
   String id=definition.unwrapKey().map(k->k.identifier().toString()).orElse("");
   if(id.equals("minecraft:cave")||id.equals("minecraft:cave_extra_underground")||id.equals("minecraft:canyon")) {
    var guard=shared.get();if(guard==null){guard=new CaveCarverGuard(generator.generatorSettings().value(),state,target);shared.set(guard);}
    output=guard.wrap(output,id.equals("minecraft:canyon"));
   }
  }
  return original.call(carver,generation,random,target,source,output);
 }
}
