package dev.freshgenerator;
import java.util.*;
import java.lang.reflect.*;
import java.nio.file.*;
public final class ApiProbe {
 public static void main(String[] args)throws Exception{
  for(String name:List.of("net.minecraft.world.level.biome.Biome","net.minecraft.world.level.levelgen.densityfunction.DensityFunction","net.minecraft.core.registries.BuiltInRegistries")){
   Class<?> t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   Arrays.stream(t.getDeclaredFields()).filter(f->!name.endsWith("BuiltInRegistries")||f.getName().contains("MATERIAL")).forEach(f->System.out.println(f.toGenericString()));
  }
  Class<?> t=Class.forName("net.minecraft.world.level.biome.Biome",false,ApiProbe.class.getClassLoader());
  String jar=Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  for(String name:List.of("net.minecraft.world.level.biome.Biome","net.minecraft.world.level.levelgen.material.MaterialRuleContext")){
   var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",name).redirectErrorStream(true).start();
   String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);
   for(String section:text.split("\n  (?=(?:public|private|protected|static))"))
    if(section.contains("shouldSnow(")||section.contains("coldEnoughToSnow(")||section.contains("getDensitiesInChunk("))System.out.println("BYTECODE "+section);
  }
 }
}
