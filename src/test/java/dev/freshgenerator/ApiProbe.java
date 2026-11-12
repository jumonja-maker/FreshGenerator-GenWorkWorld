package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;

/** Inspection only: exact target bytecode, excluded from the installable mod. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.server.level.WorldGenRegion",
      "net.minecraft.world.level.StructureManager",
      "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
      "net.minecraft.world.level.chunk.status.ChunkStatus",
      "net.minecraft.server.level.GenerationChunkHolder")) {
   try {Class.forName(name,false,ApiProbe.class.getClassLoader());}
   catch(ClassNotFoundException e){System.out.println("ABSENT_NATIVE_CLASS "+name);continue;}
   System.out.println("VILLAGE_SCHEDULER_NATIVE_API "+name);
   var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
       "-classpath",System.getProperty("java.class.path"),"-c","-p",name).inheritIO().start();
   if(process.waitFor()!=0)throw new IllegalStateException("Cannot inspect actual class "+name);
  }
 }
}
