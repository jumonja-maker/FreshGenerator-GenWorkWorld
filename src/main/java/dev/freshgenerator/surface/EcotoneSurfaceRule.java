package dev.freshgenerator.surface;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.EcotoneModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/** Native material rule, before structures/vegetation. Never edits a loaded world's blocks. */
public record EcotoneSurfaceRule(DensityFunction height,DensityFunction temperature,DensityFunction erosion,
        boolean subsurface) implements MaterialRule {
 public static final MapCodec<EcotoneSurfaceRule> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
  DensityFunction.CODEC.fieldOf("height").forGetter(EcotoneSurfaceRule::height),
  DensityFunction.CODEC.fieldOf("temperature").forGetter(EcotoneSurfaceRule::temperature),
  DensityFunction.CODEC.fieldOf("erosion").forGetter(EcotoneSurfaceRule::erosion),
  Codec.BOOL.optionalFieldOf("subsurface",false).forGetter(EcotoneSurfaceRule::subsurface)
 ).apply(i,EcotoneSurfaceRule::new));
 @Override public MapCodec<EcotoneSurfaceRule> codec(){return CODEC;}
 @Override public RuleEvaluator compile(MaterialRuleContext c){
  // false selects lazy native sampling, not three unnecessary full 3-D buffers for 2-D fields.
  var h=c.getDensitiesInChunk(height,false);var t=c.getDensitiesInChunk(temperature,false);var e=c.getDensitiesInChunk(erosion,false);
  long seed=c.getOrCreateRandomFactory(EcotonePolicy.SEED_ID).at(0,0,0).nextLong();
  return new RuleEvaluator(){
   int lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE;float elevation,climate,eroded;EcotoneModel.Fields fields;
   @Override public BlockState tryApply(int x,int y,int z){
    String biome=c.getBiome().unwrapKey().map(k->k.identifier().toString()).orElse("");
    if(!EcotonePolicy.BIOMES.contains(biome)||y<=c.getSeaLevel()+8)return null;
    if(x!=lastX||z!=lastZ){elevation=h.get();climate=t.get();eroded=e.get();fields=EcotoneModel.sample(seed,x,z,elevation,climate);lastX=x;lastZ=z;}
    if(!fields.active()||y<elevation-6||y>elevation+2||c.waterHeight()>y)return null;
    if(fields.snow()&&fields.snowCover()>=0.98&&EcotonePolicy.COLD_PALETTES.contains(biome))return null;
    double slope=0.5*Math.hypot(c.surfaceGradientX(),c.surfaceGradientZ());
    if(fields.snow())return Blocks.SNOW_BLOCK.defaultBlockState();
    if(EcotoneModel.rock(fields,elevation,slope,eroded))return Blocks.STONE.defaultBlockState();
    return(subsurface?Blocks.DIRT:Blocks.GRASS_BLOCK).defaultBlockState();
   }
  };
 }
}
