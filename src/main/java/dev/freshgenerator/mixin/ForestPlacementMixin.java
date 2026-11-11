package dev.freshgenerator.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.forest.ForestEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Native biome, height, water and survival filters remain in control. No extra attempts,
 * moved candidates or random draws. Saplings, commands, nested features and other worlds
 * stay on the original path. The name check is only a cheap hint; identity is checked next.
 */
@Mixin(FeaturePlacer.class)
abstract class ForestPlacementMixin {
 @Unique private boolean freshgenerator$forestChecked;
 @Unique private ForestEnvironment freshgenerator$forest;
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),require=2)
 private boolean freshgenerator$ecologicalForest(Feature feature,WorldGenLevel level,ChunkGenerator generator,
   RandomSource random,BlockPos at,Operation<Boolean> original,PlacedFeature placed,RandomSource sourceRandom,BlockPos origin,boolean biomeCheck){
  if(biomeCheck){
   String hint=placed.feature().unwrapKey().map(k->k.identifier().toString()).orElse("");
   if(hint.contains("tree")||hint.contains("birch")||hint.contains("taiga")||hint.contains("jungle")){
    if(!freshgenerator$forestChecked){freshgenerator$forest=ForestEnvironment.create(level,generator);freshgenerator$forestChecked=true;}
    if(freshgenerator$forest!=null&&!freshgenerator$forest.retain(placed,at))return false;
   }
  }
  return original.call(feature,level,generator,random,at);
 }
}
