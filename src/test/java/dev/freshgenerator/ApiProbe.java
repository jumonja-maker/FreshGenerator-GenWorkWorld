package dev.freshgenerator;
import java.util.*;
import dev.freshgenerator.pack.*;
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  var vanilla=new VanillaAssets();var pack=WorldgenCatalog.compile(vanilla);
  for(String path:List.of("data/freshgenerator/worldgen/noise_settings/highlands_v2.json","data/freshgenerator/worldgen/density_function/highlands_v2/temperature.json","data/freshgenerator/worldgen/density_function/highlands_v2/vegetation.json","data/freshgenerator/worldgen/density_function/highlands_v2/erosion.json","data/freshgenerator/worldgen/density_function/highlands_v2/ridges.json","data/freshgenerator/worldgen/density_function/highlands_v2/continents.json","data/freshgenerator/worldgen/world_preset/world.json"))System.out.println("BIOME_RESOURCE "+path+" "+pack.get(path));
  for(String name:List.of("net.minecraft.world.level.biome.BiomeSource","net.minecraft.world.level.biome.MultiNoiseBiomeSource","net.minecraft.world.level.biome.Climate","net.minecraft.world.level.biome.Climate$Sampler","net.minecraft.world.level.biome.Climate$TargetPoint","net.minecraft.world.level.biome.BiomeResolver","net.minecraft.world.level.levelgen.RandomState","net.minecraft.world.level.levelgen.densityfunction.DensityFunction","net.minecraft.world.level.levelgen.densityfunction.DensitySampler","net.minecraft.world.level.levelgen.densityfunction.SamplerContext")){
   try{var t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
    for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());for(var c:t.getDeclaredConstructors())System.out.println(c.toGenericString());
    for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());
   }catch(ClassNotFoundException e){System.out.println("ABSENT_API "+name);}
  }
 }
}
