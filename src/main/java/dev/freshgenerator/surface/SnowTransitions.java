package dev.freshgenerator.surface;

import dev.freshgenerator.core.EcotoneModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Changes only the climate gate of native snow placement; native survival/light/height tests remain.
 * No chunk requests, global world caches, or writes. Water freezing is deliberately not redirected.
 */
public final class SnowTransitions {
    private static final ResourceKey<DensityFunction> HEIGHT=key("coherent/height"),TEMPERATURE=key("temperature");
    private SnowTransitions() { }
    private static ResourceKey<DensityFunction> key(String path){return ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(EcotonePolicy.SETTINGS+"/"+path));}
    public static Biome.Precipitation precipitation(LevelReader reader,BlockPos pos,Biome.Precipitation original){
        if(original==Biome.Precipitation.NONE)return original;
        ServerLevel level=reader instanceof ServerLevel s?s:reader instanceof WorldGenRegion r?r.getLevel():null;
        if(level==null||pos.getY()<=level.getSeaLevel()+8)return original;
        if(!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g))return original;
        if(!g.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(EcotonePolicy.SETTINGS))return original;
        String biome=reader.getBiome(pos).unwrapKey().map(k->k.identifier().toString()).orElse("");
        if(!EcotonePolicy.BIOMES.contains(biome))return original;
        var registry=level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var state=level.getChunkSource().randomState();
        var ctx=SamplerContext.EMPTY_UNCACHED;
        float h=state.getSampler(registry.getOrThrow(HEIGHT).value()).sampleValue(ctx,pos.getX(),0,pos.getZ());
        if(pos.getY()<h-6||pos.getY()>h+48)return original;
        float t=state.getSampler(registry.getOrThrow(TEMPERATURE).value()).sampleValue(ctx,pos.getX(),0,pos.getZ());
        long seed=state.getOrCreateRandomFactory(EcotonePolicy.SEED_ID).at(0,0,0).nextLong();
        var fields=EcotoneModel.sample(seed,pos.getX(),pos.getZ(),h,t);
        if(!fields.active())return original;
        return fields.snow()?Biome.Precipitation.SNOW:Biome.Precipitation.RAIN;
    }
}
