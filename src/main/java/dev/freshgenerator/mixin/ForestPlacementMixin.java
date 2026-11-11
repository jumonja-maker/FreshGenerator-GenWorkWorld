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

/** Native biome, height, water and survival placement filters execute BEFORE this hook.
 * Does not add attempts, move candidates, change tree selection tables or consume random values.
 * Saplings, commands, nested features and non-FreshGenerator worlds remain on the original path.
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
   if(!freshgenerator$forestChecked){freshgenerator$forest=ForestEnvironment.create(level,generator);freshgenerator$forestChecked=true;}
   if(freshgenerator$forest!=null&&!freshgenerator$forest.retain(placed,at))return false;
  }
  return original.call(feature,level,generator,random,at);
 }
}
