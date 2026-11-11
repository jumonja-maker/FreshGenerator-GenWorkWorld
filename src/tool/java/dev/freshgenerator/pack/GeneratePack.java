package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Build-time tool. Output is ordinary native worldgen data that Minecraft evaluates with its world seed. */
public final class GeneratePack {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GeneratePack <output directory>");
        Path destination = Path.of(args[0]);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        VanillaAssets vanilla = new VanillaAssets();
        Map<String, TerrainProfile> profiles = new TreeMap<>();
        profiles.put("balanced_v1", TerrainProfile.BALANCED); profiles.put("highlands_v1", TerrainProfile.HIGHLANDS);
        int count = 0;
        for (var profile : profiles.entrySet()) {
            var files = new WorldgenCompiler(vanilla, profile.getKey(), profile.getValue()).compile();
            for (var file : files.entrySet()) {
                Path path = destination.resolve(file.getKey()); Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(file.getValue()) + "\n", StandardCharsets.UTF_8); count++;
            }
        }
        Path tag = destination.resolve("data/minecraft/tags/worldgen/world_preset/normal.json");
        Files.createDirectories(tag.getParent());
        Files.writeString(tag, gson.toJson(Map.of("replace", false, "values", List.of("freshgenerator:balanced_v1", "freshgenerator:highlands_v1"))) + "\n");
        JsonObject manifest = new JsonObject(); manifest.addProperty("minecraft", "26.3"); manifest.addProperty("format", 1);
        manifest.addProperty("generated_entries", count); manifest.add("source_sha256", gson.toJsonTree(vanilla.fingerprints()));
        Files.writeString(destination.resolve("freshgenerator-worldgen-manifest.json"), gson.toJson(manifest) + "\n");
        System.out.println("FRESHGENERATOR_PACK_PASS entries=" + count + " nativeAssets=" + vanilla.fingerprints().size());
    }
}
