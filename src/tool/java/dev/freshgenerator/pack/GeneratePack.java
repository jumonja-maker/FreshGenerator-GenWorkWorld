package dev.freshgenerator.pack;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Builds one public FreshGenerator world and hidden compatibility resources for existing saves. */
public final class GeneratePack {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GeneratePack <output directory>");
        Path destination = Path.of(args[0]);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        VanillaAssets vanilla = new VanillaAssets();
        var files = WorldgenCatalog.compile(vanilla);
        for (var file : files.entrySet()) {
            Path path = destination.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(file.getValue()) + "\n", StandardCharsets.UTF_8);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("minecraft", "26.3");
        manifest.addProperty("format", 2);
        manifest.addProperty("generated_entries", files.size());
        manifest.add("selectable_world_presets", gson.toJsonTree(List.of(WorldgenCatalog.WORLD_ID)));
        manifest.addProperty("current_internal_settings", WorldgenCatalog.CURRENT_SETTINGS);
        manifest.addProperty("legacy_presets", "hidden; existing-save compatibility only");
        manifest.add("source_sha256", gson.toJsonTree(vanilla.fingerprints()));
        Files.writeString(destination.resolve("freshgenerator-worldgen-manifest.json"), gson.toJson(manifest) + "\n");
        System.out.println("FRESHGENERATOR_PACK_PASS entries=" + files.size()
                + " nativeAssets=" + vanilla.fingerprints().size() + " selectableWorld=FreshGenerator");
    }
}
