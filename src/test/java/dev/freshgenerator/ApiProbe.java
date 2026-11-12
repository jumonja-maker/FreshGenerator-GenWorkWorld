package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;

/** Test-only inspection of the exact target game, never packaged in the mod. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.levelgen.feature.LakeFeature",
      "net.minecraft.world.level.levelgen.placement.FeaturePlacer",
      "net.minecraft.server.level.WorldGenRegion",
      "net.minecraft.world.level.StructureManager",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.level.chunk.ChunkAccess")) {
   try {Class.forName(name,false,ApiProbe.class.getClassLoader());}
   catch(ClassNotFoundException e){System.out.println("ABSENT_NATIVE_CLASS "+name);continue;}
   System.out.println("VILLAGE_FEATURE_NATIVE_API "+name);
   var command=new java.util.ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
       "-classpath",System.getProperty("java.class.path"),"-p"));
   if(name.endsWith("LakeFeature"))command.add("-c");command.add(name);
   var process=new ProcessBuilder(command).inheritIO().start();
   if(process.waitFor()!=0)throw new IllegalStateException("Cannot inspect actual class "+name);
  }
  for(String path:List.of("/data/minecraft/worldgen/feature/lake_lava.json",
      "/data/minecraft/worldgen/configured_feature/lake_lava.json",
      "/data/minecraft/worldgen/placed_feature/lake_lava_surface.json",
      "/data/minecraft/worldgen/placed_feature/lake_lava_underground.json")) {
   try(var in=ApiProbe.class.getResourceAsStream(path)){
    System.out.println("NATIVE_LAKE_DATA "+path+" "+(in==null?"ABSENT":new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
   }
  }
 }
}
