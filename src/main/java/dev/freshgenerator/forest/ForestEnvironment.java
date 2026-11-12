package dev.freshgenerator.forest;

import dev.freshgenerator.core.ColumnFloatCache;
import dev.freshgenerator.core.ForestEcology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.util.*;

/** Read-only environment scoped to ONE native FeaturePlacer. No global world cache,
 * no neighbouring chunk requests, no post-load edits, no candidate retries.
 */
public final class ForestEnvironment {
    public static final String SETTINGS="freshgenerator:highlands_v2";
    public static final List<String> FEATURES=List.of("trees_birch_and_oak_leaf_litter","trees_birch","birch_tall",
        "trees_taiga","trees_old_growth_pine_taiga","trees_old_growth_spruce_taiga","trees_jungle","trees_sparse_jungle");
    // Preserve the old sampling order; allocating this stencil for each site is unnecessary.
    private static final int[] NEIGHBOUR_X={16,-16,0,0},NEIGHBOUR_Z={0,0,16,-16};
    private final Set<PlacedFeature> features=Collections.newSetFromMap(new IdentityHashMap<>());
    private final DensitySampler height,temperature,moisture;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private final BiomeManager biomes;
    private final long seed;
    private final int sea;
    private final ColumnFloatCache heights=new ColumnFloatCache(1024);
    // Sites include native biome lookups: keep their previous cache/lookup sequence unchanged.
    private final Map<Long,ForestEcology.Site> sites=new HashMap<>();

    private ForestEnvironment(WorldGenRegion region,NoiseBasedChunkGenerator generator) {
        var level=region.getLevel();var registry=level.registryAccess();var state=level.getChunkSource().randomState();
        height=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("coherent/height")).value());
        temperature=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("temperature")).value());
        moisture=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("vegetation")).value());
        for(String name:FEATURES) features.add(registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(
                ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+name))).value());
        biomes=level.getBiomeManager().withDifferentSource(generator.getBiomeSource().createCachingResolver(state));
        seed=region.getSeed()^0x6F7265737445434FL;sea=region.getSeaLevel();
    }
    private static DensitySampler sampler(RandomState state,DensityFunction f){return state.getSampler(f);}
    private static ResourceKey<DensityFunction> key(String name){return ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(SETTINGS+"/"+name));}
    public static ForestEnvironment create(WorldGenLevel level,ChunkGenerator generator) {
        if(!(level instanceof WorldGenRegion r)||!(generator instanceof NoiseBasedChunkGenerator g))return null;
        if(!g.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(SETTINGS))return null;
        return new ForestEnvironment(r,g);
    }
    public static ForestEcology.Family family(String id) {
        return switch(id) {
            case "minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest" -> ForestEcology.Family.TEMPERATE;
            case "minecraft:taiga","minecraft:snowy_taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga" -> ForestEcology.Family.CONIFER;
            case "minecraft:jungle","minecraft:sparse_jungle" -> ForestEcology.Family.TROPICAL;
            default -> null;
        };
    }
    public boolean retain(PlacedFeature feature,BlockPos at) {
        if(!features.contains(feature))return true;
        String id=biomes.getBiome(at.getX(),at.getY(),at.getZ()).unwrapKey().map(k->k.identifier().toString()).orElse("");
        var family=family(id);if(family==null)return true;
        int x=at.getX(),z=at.getZ();float h=height(x,z);
        if(at.getY()<h-6||at.getY()>h+8)return true;
        long key=packed(x,z);var site=sites.get(key);
        if(site==null){site=site(x,z,h);if(sites.size()>=256)sites.clear();sites.put(key,site);}
        var fields=ForestEcology.sample(seed,x,z,family,site);
        boolean retain=ForestEcology.retain(seed,x,z,fields);
        ForestTelemetry.record(family,retain,fields.opening());return retain;
    }
    private ForestEcology.Site site(int x,int z,float h) {
        double sx=(height(x+4,z)-height(x-4,z))/8.0,sz=(height(x,z+4)-height(x,z-4))/8.0;
        double neighbour=0,shore=0,edge=0;
        for(int i=0;i<NEIGHBOUR_X.length;i++){
            int nx=x+NEIGHBOUR_X[i],nz=z+NEIGHBOUR_Z[i];float nh=height(nx,nz);neighbour+=nh;
            String id=biomes.getBiome(nx,(int)Math.ceil(nh),nz).unwrapKey().map(k->k.identifier().toString()).orElse("");
            if(nh<sea+1&&(id.contains("ocean")||id.equals("minecraft:river")||id.equals("minecraft:frozen_river")))shore+=.25;
            if(family(id)==null)edge+=.25;
        }
        return new ForestEcology.Site(h,sea,Math.hypot(sx,sz),h-neighbour/4,
            temperature.sampleValue(context,x,0,z),moisture.sampleValue(context,x,0,z),shore,edge);
    }
    private float height(int x,int z){long k=packed(x,z);float h=heights.get(k);if(!Float.isNaN(h))return h;
        float value=height.sampleValue(context,x,0,z);if(!Float.isFinite(value))throw new IllegalStateException("Invalid forest height");
        heights.put(k,value);return value;}
    private static long packed(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
