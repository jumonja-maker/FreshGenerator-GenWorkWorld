package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceJsonTest {
    @Test void keysDoNotDependOnMapIterationOrder() {
        var a=JsonParser.parseString("{\"z\":1,\"a\":{\"y\":2,\"b\":3}}");
        var b=JsonParser.parseString("{\"a\":{\"b\":3,\"y\":2},\"z\":1}");
        assertEquals(ResourceJson.encode(a),ResourceJson.encode(b));
    }
    @Test void sequencePriorityIsNeverSorted() {
        var a=JsonParser.parseString("{\"sequence\":[3,1,2]}");
        assertEquals(a,JsonParser.parseString(ResourceJson.encode(a)));
    }
    @Test void primitiveTypesAndFloatsArePreserved() {
        var a=new JsonObject();a.addProperty("float",3.2258065f);a.addProperty("truth",false);
        a.addProperty("id","freshgenerator:world");a.add("null",JsonNull.INSTANCE);
        assertEquals(JsonParser.parseString(a.toString()),JsonParser.parseString(ResourceJson.encode(a)));
    }
    @Test void encodingDoesNotMutateInput() {
        var a=JsonParser.parseString("{\"z\":[{\"q\":1,\"a\":2}],\"b\":null}");String original=a.toString();
        assertTrue(ResourceJson.encode(a).endsWith("\n"));assertEquals(original,a.toString());
        assertThrows(NullPointerException.class,()->ResourceJson.encode(null));
    }
    @Test void separateCompilerInstancesYieldTheSameResourceBytes() throws Exception {
        var a=WorldgenCatalog.compile(new VanillaAssets());var b=WorldgenCatalog.compile(new VanillaAssets());
        for(String key:a.keySet())assertEquals(ResourceJson.encode(a.get(key)),ResourceJson.encode(b.get(key)),key);
    }
    @Test void allCurrentWorldDataRemainEquivalentToPublishedRelease() throws Exception {
        var data=WorldgenCatalog.compile(new VanillaAssets());
        try(var jar=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.1-alpha.1.jar")) {
            for(var entry:data.entrySet()) {
                var stored=jar.getEntry(entry.getKey());assertNotNull(stored,entry.getKey());
                try(var in=jar.getInputStream(stored)) {
                    var before=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
                    assertEquals(before,JsonParser.parseString(ResourceJson.encode(entry.getValue())),entry.getKey());
                }
            }
        }
    }
}
