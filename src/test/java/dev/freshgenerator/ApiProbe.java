package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;

/** Inspection-only: actual game bytecode, never included in the runtime JAR. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.block.SculkBehaviour",
      "net.minecraft.world.level.block.SculkBehaviour$1",
      "net.minecraft.server.level.WorldGenRegion",
      "net.minecraft.world.level.levelgen.RandomSupport")) {
   try {Class.forName(name,false,ApiProbe.class.getClassLoader());}
   catch(ClassNotFoundException e){System.out.println("ABSENT_NATIVE_CLASS "+name);continue;}
   System.out.println("NATIVE_RANDOM_BYTECODE "+name);
   var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
       "-classpath",System.getProperty("java.class.path"),"-c","-p",name).inheritIO().start();
   if(process.waitFor()!=0)throw new IllegalStateException("Cannot inspect actual class "+name);
  }
 }
}
