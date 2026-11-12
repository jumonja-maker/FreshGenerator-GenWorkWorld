package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;

/** Inspection-only: actual game bytecode, never included in the runtime JAR. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.biome.Climate$Sampler",
      "net.minecraft.world.level.biome.Climate$ParameterList",
      "net.minecraft.world.level.biome.Climate$ParameterPoint",
      "net.minecraft.world.level.biome.Climate$RTree",
      "net.minecraft.world.level.biome.MultiNoiseBiomeSource")) {
   System.out.println("NATIVE_BIOME_BYTECODE "+name);
   var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
       "-classpath",System.getProperty("java.class.path"),"-c","-p",name).inheritIO().start();
   if(process.waitFor()!=0)throw new IllegalStateException("Cannot inspect actual class "+name);
  }
 }
}
