package dev.freshgenerator;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Native test-only reproduction of why two A/B climates must not share lookup history. */
class NativeBiomeSearchIsolationTest {
 private static Climate.ParameterList<String> index(){return new Climate.ParameterList<>(List.of(
     Pair.of(Climate.parameters(-1,0,0,0,0,0,0),"cold"),
     Pair.of(Climate.parameters(1,0,0,0,0,0,0),"warm")));}
 @Test void exactTieCanFollowTheLastNativeLeaf(){
  var source=index();var middle=Climate.target(0,0,0,0,0,0);
  assertEquals("cold",source.findValue(Climate.target(-1,0,0,0,0,0)));
  assertEquals("cold",source.findValue(middle));
  assertEquals("warm",source.findValue(Climate.target(1,0,0,0,0,0)));
  assertEquals("warm",source.findValue(middle));
  assertEquals(source.values().get(0).getFirst().fitness(middle),source.values().get(1).getFirst().fitness(middle));
 }
 @Test void independentIndexesDoNotContaminateReferenceTies(){
  var reference=index();var candidate=new Climate.ParameterList<>(List.copyOf(reference.values()));
  reference.findValue(Climate.target(-1,0,0,0,0,0));candidate.findValue(Climate.target(1,0,0,0,0,0));
  assertEquals("cold",reference.findValue(Climate.target(0,0,0,0,0,0)));
  assertEquals("warm",candidate.findValue(Climate.target(0,0,0,0,0,0)));
  assertEquals(reference.values(),candidate.values());
 }
}
