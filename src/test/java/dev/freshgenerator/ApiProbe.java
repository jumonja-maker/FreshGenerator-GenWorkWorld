package dev.freshgenerator;
import java.util.*;
import java.nio.file.*;
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.chunk.CarverOutput","net.minecraft.world.level.chunk.CarvingMask","net.minecraft.world.level.chunk.CarvingMask$Filter","net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator","net.minecraft.world.level.NoiseColumn")) {
   var t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   for(var ctor:t.getDeclaredConstructors())System.out.println(ctor.toGenericString());
   for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());
   String jar=Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
   var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",name).redirectErrorStream(true).start();
   String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);
   if(!name.endsWith("NoiseBasedChunkGenerator")){System.out.println(text);continue;}
   boolean emit=false;for(String line:text.split("\\R")){
    if(line.startsWith("  ")&&!line.startsWith("    ")&&line.endsWith(";"))emit=line.contains("generateCarvers(")||line.contains("applyCarvingMask(")||line.contains("lambda$applyCarvingMask$0(");
    if(emit)System.out.println(line);
   }
  }
 }
}
