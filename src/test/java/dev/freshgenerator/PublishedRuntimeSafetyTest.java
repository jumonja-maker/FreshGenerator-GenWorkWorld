package dev.freshgenerator;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** A maintenance release must not silently include either rejected runtime experiment. */
class PublishedRuntimeSafetyTest {
    @Test void caveAdapterIsTheExactPublishedSource() throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/java/dev/freshgenerator/cave/CaveCarverGuard.java"));
        var digest=MessageDigest.getInstance("SHA-1");
        digest.update(("blob "+source.length+"\0").getBytes(StandardCharsets.UTF_8));
        digest.update(source);
        assertEquals("5f824d8412787275f8b55209a8a1dd581e5521dc",HexFormat.of().formatHex(digest.digest()));
    }
    @Test void everyPublishedRuntimeClassRemainsByteIdentical() throws Exception {
        Set<String> expected=new TreeSet<>();
        try(var zip=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.2-alpha.1.jar")) {
            for(var entry:Collections.list(zip.entries())) {
                String name=entry.getName();if(!name.endsWith(".class"))continue;
                expected.add(name);List<Path> matches=new ArrayList<>();
                for(String part:List.of("main","core")) {
                    Path file=Path.of("build/classes/java",part,name);
                    if(Files.isRegularFile(file))matches.add(file);
                }
                assertEquals(1,matches.size(),name);
                try(var in=zip.getInputStream(entry)) {
                    assertArrayEquals(in.readAllBytes(),Files.readAllBytes(matches.getFirst()),name);
                }
            }
        }
        Set<String> actual=new TreeSet<>();
        for(String part:List.of("main","core")) {
            Path root=Path.of("build/classes/java",part);
            try(var files=Files.walk(root)) {
                for(var file:files.filter(p->p.toString().endsWith(".class")).toList())
                    assertTrue(actual.add(root.relativize(file).toString().replace('\\','/')),file.toString());
            }
        }
        assertEquals(28,expected.size());assertEquals(expected,actual);
    }
}
