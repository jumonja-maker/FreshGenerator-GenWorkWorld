package dev.freshgenerator;
import java.util.*;
import java.nio.file.*;
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.levelgen.NoiseGeneratorSettings","net.minecraft.world.level.levelgen.NoiseChunk","net.minecraft.world.level.levelgen.RandomState")) {
   var t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());
   if(name.endsWith("NoiseGeneratorSettings"))for(var m:t.getDeclaredMethods())if(m.getName().equals("aquifers")||m.getName().equals("noiseRouter")||m.getName().equals("debugFunctions")){
    var q=m.getReturnType();System.out.println("RETURN_API "+q);for(var n:q.getDeclaredMethods())System.out.println(n.toGenericString());
   }
  }
  var t=Class.forName("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",false,ApiProbe.class.getClassLoader());String jar=Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",t.getName()).redirectErrorStream(true).start();String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);
  boolean emit=false;for(String line:text.split("\\R")){if(line.startsWith("  ")&&!line.startsWith("    ")&&line.endsWith(";"))emit=line.contains("iterateNoiseColumn(")||line.contains("getBaseHeight(");if(emit)System.out.println(line);}
 }
}
