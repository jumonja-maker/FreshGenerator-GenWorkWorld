package dev.freshgenerator.pack;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Builds one public world; deterministic resource bytes and fingerprints improve regression auditing. */
public final class GeneratePack {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GeneratePack <output directory>");
        Path destination = Path.of(args[0]);
        Gson gson = new Gson();
        VanillaAssets vanilla = new VanillaAssets();
        var files = WorldgenCatalog.compile(vanilla);
        Map<String,String> generatedHashes = new TreeMap<>();
        for (var file : files.entrySet()) {
            Path path = destination.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            byte[] bytes = ResourceJson.encode(file.getValue()).getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            generatedHashes.put(file.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("minecraft", "26.3");
        manifest.addProperty("format", 2);
        manifest.addProperty("generated_entries", files.size());
        manifest.add("selectable_world_presets", gson.toJsonTree(List.of(WorldgenCatalog.WORLD_ID)));
        manifest.addProperty("current_internal_settings", WorldgenCatalog.CURRENT_SETTINGS);
        manifest.addProperty("legacy_presets", "hidden; existing-save compatibility only");
        manifest.add("source_sha256", gson.toJsonTree(vanilla.fingerprints()));
        manifest.add("generated_sha256", gson.toJsonTree(generatedHashes));
        Files.writeString(destination.resolve("freshgenerator-worldgen-manifest.json"), ResourceJson.encode(manifest), StandardCharsets.UTF_8);
        System.out.println("FRESHGENERATOR_PACK_PASS entries=" + files.size()
                + " nativeAssets=" + vanilla.fingerprints().size() + " selectableWorld=FreshGenerator");
    }
}
