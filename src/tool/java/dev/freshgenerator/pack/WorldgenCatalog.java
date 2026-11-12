package dev.freshgenerator.pack;
import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.io.IOException;
import java.util.*;
/** One public world. Old keys are hidden save compatibility, not extra modes. */
public final class WorldgenCatalog {
 public static final String WORLD_ID="freshgenerator:world",CURRENT_SETTINGS="freshgenerator:highlands_v2";
 public static final String NORMAL_TAG="data/minecraft/tags/worldgen/world_preset/normal.json";
 private WorldgenCatalog() { }
 public static Map<String,JsonElement> compile(VanillaAssets vanilla)throws IOException {
  Map<String,JsonElement> out=new TreeMap<>(compileBeforeBiomeRegions(vanilla));
  BiomeRegionCompiler.apply(out);
  return Collections.unmodifiableMap(out);
 }
 /** Frozen 0.3.1 compiler stage for historical layer regression tests. Not a world preset. */
 public static Map<String,JsonElement> compileBeforeBiomeRegions(VanillaAssets vanilla)throws IOException {
  Objects.requireNonNull(vanilla);Map<String,JsonElement> out=new TreeMap<>();
  out.putAll(new WorldgenCompiler(vanilla,"balanced_v1",TerrainProfile.BALANCED).compile());
  out.putAll(new WorldgenCompiler(vanilla,"highlands_v1",TerrainProfile.HIGHLANDS).compile());
  out.putAll(new NaturalWorldgenCompiler(vanilla,"balanced_v2",TerrainProfile.BALANCED).compile());
  out.putAll(new NaturalWorldgenCompiler(vanilla,"highlands_v2",TerrainProfile.HIGHLANDS).compile());
  CoherentSurfaceCompiler.apply(out);EcotoneCompiler.apply(out);CaveAccessCompiler.apply(out);
  out.put(VanillaAssets.path("world_preset",WORLD_ID),Objects.requireNonNull(out.get(VanillaAssets.path("world_preset",CURRENT_SETTINGS))).deepCopy());
  JsonArray values=new JsonArray();values.add(WORLD_ID);JsonObject tag=new JsonObject();tag.addProperty("replace",false);tag.add("values",values);out.put(NORMAL_TAG,tag);
  return Collections.unmodifiableMap(out);
 }
}
