package dev.freshgenerator;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PublishedRuntimeSafetyTest {
 @Test void everyPreviouslyPublishedRuntimeClassRemainsByteIdentical()throws Exception {
  try (var zip = new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar")) {
   int classes = 0;
   for (var entry : Collections.list(zip.entries())) {
    if (!entry.getName().endsWith(".class")) continue;
    Path path = Path.of("build/classes/java/main", entry.getName());
    if (!Files.exists(path)) path = Path.of("build/classes/java/core", entry.getName());
    try (var in = zip.getInputStream(entry)) {
     assertArrayEquals(in.readAllBytes(), Files.readAllBytes(path), entry.getName());
    }
    classes++;
   }
   assertEquals(37, classes);
  }
 }
 @Test void caveAdapterIsTheExactPublishedSource()throws Exception {
  byte[] source=Files.readAllBytes(Path.of("src/main/java/dev/freshgenerator/cave/CaveCarverGuard.java"));
  var digest=java.security.MessageDigest.getInstance("SHA-1");digest.update(("blob "+source.length+"\0").getBytes(StandardCharsets.UTF_8));digest.update(source);
  assertEquals("5f824d8412787275f8b55209a8a1dd581e5521dc",HexFormat.of().formatHex(digest.digest()));
 }
}
