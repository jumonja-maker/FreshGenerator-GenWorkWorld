package dev.freshgenerator;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  var anchor=Class.forName("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",false,ApiProbe.class.getClassLoader());
  String jar=Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  List<String> names=new ArrayList<>();
  try(var z=new ZipFile(jar)) {
   for(var entry:Collections.list(z.entries()))if(entry.getName().contains("/carver/")&&entry.getName().endsWith(".class")&&!entry.getName().contains("$"))names.add(entry.getName().replace('/','.').replace(".class",""));
  }
  System.out.println("CARVER_CLASSES "+names);
  names.add("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator");
  for(String name:names) {
   var t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   for(var ctor:t.getDeclaredConstructors())System.out.println(ctor.toGenericString());
   for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());
   for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());
   boolean code=name.endsWith("CarvingContext")||name.endsWith("WorldCarver")||name.endsWith("CarverContext")||name.endsWith(".Carver");
   if(code){var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",name).redirectErrorStream(true).start();String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);System.out.println(text);}
  }
  for(String path:List.of("data/minecraft/worldgen/carver/cave.json","data/minecraft/worldgen/carver/cave_extra_underground.json","data/minecraft/worldgen/carver/canyon.json","data/minecraft/worldgen/biome/plains.json")){
   try(var in=ApiProbe.class.getClassLoader().getResourceAsStream(path)){System.out.println("NATIVE_CAVE_RESOURCE "+path+" "+(in==null?"MISSING":new String(in.readAllBytes(),StandardCharsets.UTF_8)));}
  }
 }
}
