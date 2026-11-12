package dev.freshgenerator;

import java.nio.file.Path;
import java.util.List;

/** Inspection-only native scheduling contracts. Not part of the game JAR. */
public final class ApiProbe {
 public static void main(String[] args)throws Exception {
  for(String name:List.of("net.minecraft.world.level.WorldGenContext",
      "net.minecraft.server.level.ChunkResultLookup",
      "net.minecraft.util.StaticCache2D",
      "net.minecraft.server.level.GenerationChunkHolder")) {
   try {Class.forName(name,false,ApiProbe.class.getClassLoader());}
   catch(ClassNotFoundException e){System.out.println("ABSENT_NATIVE_CLASS "+name);continue;}
   System.out.println("NATIVE_METADATA_API "+name);
   var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
       "-classpath",System.getProperty("java.class.path"),"-c","-p",name).redirectErrorStream(true).start();
   String text=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   if(process.waitFor()!=0)throw new IllegalStateException(text);
   if(!name.endsWith("GenerationChunkHolder"))System.out.print(text);
   else {boolean show=false;for(String line:text.split("\\R")){
    if(line.matches("^  (public|protected|private).*"))show=line.contains("getChunkIfPresent")||line.contains("getLatestChunk")||line.contains("getPos");
    if(show)System.out.println(line);
   }}
  }
 }
}
