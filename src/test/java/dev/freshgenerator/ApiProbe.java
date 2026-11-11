package dev.freshgenerator;
import java.util.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class ApiProbe {
 public static void main(String[] args)throws Exception{
  List<String> names=List.of("net.minecraft.world.level.levelgen.placement.PlacedFeature","net.minecraft.world.level.levelgen.placement.PlacementContext","net.minecraft.world.level.levelgen.placement.PlacementModifier","net.minecraft.world.level.levelgen.placement.CountPlacement","net.minecraft.world.level.levelgen.feature.ConfiguredFeature","net.minecraft.world.level.levelgen.feature.TreeFeature","net.minecraft.server.level.WorldGenRegion","net.minecraft.world.level.WorldGenLevel","net.minecraft.world.level.levelgen.structure.StructureManager","net.minecraft.world.level.StructureManager","net.minecraft.world.level.biome.BiomeGenerationSettings");
  for(String name:names){try{
   Class<?> t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());for(var c:t.getDeclaredConstructors())System.out.println(c.toGenericString());
   if(name.endsWith("PlacedFeature")||name.endsWith("TreeFeature")||name.endsWith("PlacementContext")){
    String jar=Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",name).redirectErrorStream(true).start();
    String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);System.out.println("BYTECODE "+text);
   }
  }catch(ClassNotFoundException e){System.out.println("ABSENT "+name);}}
  for(String path:List.of("data/minecraft/worldgen/placed_feature/trees_birch_and_oak.json","data/minecraft/worldgen/placed_feature/trees_taiga.json","data/minecraft/worldgen/placed_feature/trees_jungle.json","data/minecraft/worldgen/placed_feature/trees_dark_forest.json","data/minecraft/worldgen/configured_feature/trees_birch_and_oak.json","data/minecraft/worldgen/biome/forest.json")){
   try(var in=ApiProbe.class.getClassLoader().getResourceAsStream(path)){System.out.println("RESOURCE "+path+" "+(in==null?"MISSING":new String(in.readAllBytes(),StandardCharsets.UTF_8)));}
  }
 }
}
