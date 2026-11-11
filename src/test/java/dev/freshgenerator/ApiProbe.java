package dev.freshgenerator;
import java.util.*;import java.lang.reflect.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;
public final class ApiProbe {
 public static void main(String[]args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.levelgen.placement.FeaturePlacer","net.minecraft.world.level.levelgen.placement.InSquarePlacement","net.minecraft.world.level.levelgen.placement.RepeatingPlacement")) {
   var t=Class.forName(name,false,ApiProbe.class.getClassLoader());System.out.println("API_PROBE "+name);
   for(var m:t.getDeclaredMethods())System.out.println(m.toGenericString());for(var f:t.getDeclaredFields())System.out.println(f.toGenericString());
   String jar=Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
   var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-classpath",jar,"-c","-p",name).redirectErrorStream(true).start();
   String text=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new AssertionError(text);System.out.println(text);
  }
  for(String n:List.of("forest","birch_forest","old_growth_birch_forest","taiga","old_growth_pine_taiga","old_growth_spruce_taiga","snowy_taiga","jungle","sparse_jungle","bamboo_jungle","dark_forest")) {
   String path="data/minecraft/worldgen/biome/"+n+".json";try(var in=ApiProbe.class.getClassLoader().getResourceAsStream(path)){
    var o=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();System.out.println("BIOME_FEATURES "+n+" "+o.get("features"));
   }
  }
 }
}
