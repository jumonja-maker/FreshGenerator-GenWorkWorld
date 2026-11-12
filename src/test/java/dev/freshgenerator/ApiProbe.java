package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/** Inspection only: signatures and selected real bytecode, never packaged in the mod. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  String root="net.minecraft.world.level.";
  for(String name:List.of(root+"levelgen.NoiseBasedChunkGenerator",root+"levelgen.NoiseChunk",
    root+"levelgen.Beardifier",root+"StructureManager",root+"levelgen.structure.StructureStart",
    root+"levelgen.structure.StructurePiece",root+"levelgen.structure.PoolElementStructurePiece",
    root+"levelgen.structure.TerrainAdjustment",root+"chunk.status.ChunkStatus",
    root+"levelgen.densityfunction.DensitySampler",root+"levelgen.densityfunction.SamplerContext")) {
   Class<?> c;try{c=Class.forName(name,false,ApiProbe.class.getClassLoader());}
   catch(ClassNotFoundException e){System.out.println("ABSENT_NATIVE_CLASS "+name);continue;}
   System.out.println("NATIVE_SIGNATURES "+name);
   var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
    "-classpath",System.getProperty("java.class.path"),"-p",name).inheritIO().start();
   if(p.waitFor()!=0)throw new IllegalStateException(name);
  }
  Class<?> c=Class.forName(root+"levelgen.NoiseBasedChunkGenerator",false,ApiProbe.class.getClassLoader());
  var location=c.getProtectionDomain().getCodeSource().getLocation();
  System.out.println("NATIVE_LOCATION "+location);
  if(location.getPath().endsWith(".jar"))try(var jar=new JarFile(Path.of(location.toURI()).toFile())) {
   jar.stream().map(e->e.getName()).filter(n->n.endsWith(".class")&&(n.toLowerCase().contains("beard")||n.contains("Structure")&&n.contains("density")))
    .sorted().forEach(n->System.out.println("TERRAIN_CLASS "+n));
  }
  for(String name:List.of(root+"levelgen.NoiseBasedChunkGenerator",root+"levelgen.Beardifier")){
   try{Class.forName(name,false,ApiProbe.class.getClassLoader());}catch(ClassNotFoundException e){continue;}
   var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
    "-classpath",System.getProperty("java.class.path"),"-c","-p",name).redirectErrorStream(true).start();
   String s=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   if(p.waitFor()!=0)throw new IllegalStateException(name);
   boolean print=false;
   for(String line:s.split("\n")) {
    if(line.matches("  (public|private|protected|static).*\\(.*"))
     print=line.contains("fill")||line.contains("Fill")||line.contains("NoiseChunk")||line.contains("Terrain")||line.contains("compute")||line.contains("Structures")||line.contains("generateCarvers");
    if(print)System.out.println(name+" "+line);
   }
  }
 }
}
