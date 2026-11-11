package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Stable resource encoding. Only object-key order changes; arrays, numbers and rules are preserved. */
public final class ResourceJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private ResourceJson() { }
    public static String encode(JsonElement value) { return GSON.toJson(ordered(Objects.requireNonNull(value))) + "\n"; }
    private static JsonElement ordered(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(k -> out.add(k, ordered(value.getAsJsonObject().get(k))));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            value.getAsJsonArray().forEach(v -> out.add(ordered(v)));
            return out;
        }
        return value.deepCopy();
    }
}
