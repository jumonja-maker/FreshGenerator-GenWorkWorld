package dev.freshgenerator;
import com.google.gson.JsonParser;
import dev.freshgenerator.forest.ForestEnvironment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ForestNativeHintTest {
 @Test void allSupportedNativePlacedFeaturesPassTheCheapHint()throws Exception {
  for(String name:ForestEnvironment.FEATURES){
   try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+name+".json")){
    assertNotNull(in,name);String hint=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().get("feature").getAsString();
    assertTrue(hint.contains("tree")||hint.contains("birch")||hint.contains("taiga")||hint.contains("jungle"),name+" "+hint);
   }
  }
 }
}
