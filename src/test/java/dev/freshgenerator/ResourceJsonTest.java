package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResourceJsonTest {
 @Test void keysDoNotDependOnMapIterationOrder(){assertEquals(ResourceJson.encode(JsonParser.parseString("{\"z\":1,\"a\":{\"y\":2,\"b\":3}}")),ResourceJson.encode(JsonParser.parseString("{\"a\":{\"b\":3,\"y\":2},\"z\":1}")));}
 @Test void sequencePriorityIsNeverSorted(){var a=JsonParser.parseString("{\"sequence\":[3,1,2]}");assertEquals(a,JsonParser.parseString(ResourceJson.encode(a)));}
 @Test void primitiveTypesAndFloatsArePreserved(){var a=new JsonObject();a.addProperty("float",3.2258065f);a.addProperty("truth",false);a.addProperty("id","freshgenerator:world");a.add("null",JsonNull.INSTANCE);assertEquals(JsonParser.parseString(a.toString()),JsonParser.parseString(ResourceJson.encode(a)));}
 @Test void encodingDoesNotMutateInput(){var a=JsonParser.parseString("{\"z\":[{\"q\":1,\"a\":2}],\"b\":null}");String old=a.toString();assertTrue(ResourceJson.encode(a).endsWith("\n"));assertEquals(old,a.toString());assertThrows(NullPointerException.class,()->ResourceJson.encode(null));}
 @Test void separateCompilerInstancesYieldTheSameResourceBytes()throws Exception{var a=WorldgenCatalog.compile(new VanillaAssets());var b=WorldgenCatalog.compile(new VanillaAssets());for(String k:a.keySet())assertEquals(ResourceJson.encode(a.get(k)),ResourceJson.encode(b.get(k)),k);}
 @Test void unchangedResourcesRetainPublishedMeaning()throws Exception{new EcotoneCompilerTest().onlyThreeExistingResourcesChangeComparedToPublishedTerrainFix();}
}
