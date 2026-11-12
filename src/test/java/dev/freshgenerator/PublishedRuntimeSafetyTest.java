package dev.freshgenerator;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PublishedRuntimeSafetyTest {
 @Test void everyPreviouslyPublishedRuntimeClassRemainsByteIdentical()throws Exception {
  try(var zip=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.3-alpha.1.jar")){
   Set<String> old=new TreeSet<>();
   for(var e:Collections.list(zip.entries()))if(e.getName().endsWith(".class")){
    old.add(e.getName());Path p=Path.of("build/classes/java/main",e.getName());
    if(!Files.exists(p))p=Path.of("build/classes/java/core",e.getName());
    try(var in=zip.getInputStream(e)){assertArrayEquals(in.readAllBytes(),Files.readAllBytes(p),e.getName());}
   }
   assertEquals(28,old.size());Set<String> actual=new TreeSet<>();
   for(String dir:List.of("build/classes/java/main","build/classes/java/core"))try(var paths=Files.walk(Path.of(dir))){
    for(Path p:paths.filter(p->p.toString().endsWith(".class")).toList())actual.add(Path.of(dir).relativize(p).toString().replace('\\','/'));
   }
   Set<String> expected=new TreeSet<>(old);
   for(String p:List.of("village/VillageTerrainModel","village/VillageTerrainModel$Site","village/VillageTerrainModel$Column",
       "village/VillageTerrainPlan","village/VillageNoiseAccess","mixin/NoiseChunkVillageMixin","mixin/VillageTerrainMixin",
       "village/VillageLakeGuard","mixin/VillageLakeMixin","core/DrainageModel","core/DrainageModel$Field",
       "core/DrainageModel$Tile","core/DrainageModel$Grid","hydrology/DrainageDensity",
       "hydrology/DrainageDensity$Sampler","hydrology/HydrologyInitializer"))expected.add("dev/freshgenerator/"+p+".class");
   assertEquals(expected,actual);
  }
 }
 @Test void caveAdapterIsTheExactPublishedSource()throws Exception {
  byte[] source=Files.readAllBytes(Path.of("src/main/java/dev/freshgenerator/cave/CaveCarverGuard.java"));
  var digest=java.security.MessageDigest.getInstance("SHA-1");digest.update(("blob "+source.length+"\0").getBytes(StandardCharsets.UTF_8));digest.update(source);
  assertEquals("5f824d8412787275f8b55209a8a1dd581e5521dc",HexFormat.of().formatHex(digest.digest()));
 }
}
