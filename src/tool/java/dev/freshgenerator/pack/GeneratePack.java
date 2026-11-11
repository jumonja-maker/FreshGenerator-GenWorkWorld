package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Build-time compiler; existing v1 worlds keep their exact registry resources. */
public final class GeneratePack {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GeneratePack <output directory>");
        Path destination = Path.of(args[0]);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        VanillaAssets vanilla = new VanillaAssets();
        Map<String, TerrainProfile> profiles = new LinkedHashMap<>();
        profiles.put("balanced_v1", TerrainProfile.BALANCED);
        profiles.put("highlands_v1", TerrainProfile.HIGHLANDS);
        profiles.put("balanced_v2", TerrainProfile.BALANCED);
        profiles.put("highlands_v2", TerrainProfile.HIGHLANDS);
        int count = 0;
        for (var profile : profiles.entrySet()) {
            var files = profile.getKey().endsWith("_v2")
                    ? new NaturalWorldgenCompiler(vanilla, profile.getKey(), profile.getValue()).compile()
                    : new WorldgenCompiler(vanilla, profile.getKey(), profile.getValue()).compile();
            for (var file : files.entrySet()) {
                Path path = destination.resolve(file.getKey()); Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(file.getValue()) + "\n", StandardCharsets.UTF_8); count++;
            }
        }
        Path tag = destination.resolve("data/minecraft/tags/worldgen/world_preset/normal.json");
        Files.createDirectories(tag.getParent());
        Files.writeString(tag, gson.toJson(Map.of("replace", false, "values", List.of(
                "freshgenerator:balanced_v2", "freshgenerator:highlands_v2",
                "freshgenerator:balanced_v1", "freshgenerator:highlands_v1"))) + "\n");
        JsonObject manifest = new JsonObject(); manifest.addProperty("minecraft", "26.3"); manifest.addProperty("format", 2);
        manifest.addProperty("generated_entries", count); manifest.add("source_sha256", gson.toJsonTree(vanilla.fingerprints()));
        Files.writeString(destination.resolve("freshgenerator-worldgen-manifest.json"), gson.toJson(manifest) + "\n");
        System.out.println("FRESHGENERATOR_PACK_PASS entries=" + count + " nativeAssets=" + vanilla.fingerprints().size());
    }
}
