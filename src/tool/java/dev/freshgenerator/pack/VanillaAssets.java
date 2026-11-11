package dev.freshgenerator.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Reads bundled data from the ACTUAL resolved game JAR. No downloaded templates or API stubs. */
public final class VanillaAssets {
    private final Map<String, String> fingerprints = new TreeMap<>();
    public boolean exists(String path) { return VanillaAssets.class.getResource("/" + path) != null; }
    public JsonElement read(String path) throws IOException {
        try (InputStream in = VanillaAssets.class.getResourceAsStream("/" + path)) {
            if (in == null) throw new FileNotFoundException("Missing Minecraft 26.3 data: " + path);
            byte[] bytes = in.readAllBytes();
            try { fingerprints.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        }
    }
    public Map<String, String> fingerprints() { return Collections.unmodifiableMap(fingerprints); }
    public static String path(String registry, String id) {
        String[] parts = id.split(":", 2);
        if (parts.length != 2 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException(id);
        return "data/" + parts[0] + "/worldgen/" + registry + "/" + parts[1] + ".json";
    }
}
