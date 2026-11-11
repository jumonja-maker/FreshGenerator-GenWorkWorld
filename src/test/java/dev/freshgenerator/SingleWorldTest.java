package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class SingleWorldTest {
    private static Map<String, JsonElement> catalog() throws Exception {
        return WorldgenCatalog.compile(new VanillaAssets());
    }
    @Test void exactlyOneFreshGeneratorWorldIsSelectable() throws Exception {
        var tag = catalog().get(WorldgenCatalog.NORMAL_TAG).getAsJsonObject();
        assertFalse(tag.get("replace").getAsBoolean());
        assertEquals(JsonParser.parseString("[\"freshgenerator:world\"]"), tag.get("values"));
    }
    @Test void currentWorldIncludesEveryDimensionAndTheLatestTerrainImprovements() throws Exception {
        var files = catalog();
        var world = files.get(VanillaAssets.path("world_preset", WorldgenCatalog.WORLD_ID));
        assertEquals(files.get(VanillaAssets.path("world_preset", WorldgenCatalog.CURRENT_SETTINGS)), world);
        var dims = world.getAsJsonObject().getAsJsonObject("dimensions");
        assertEquals(Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), dims.keySet());
        assertEquals(WorldgenCatalog.CURRENT_SETTINGS, dims.getAsJsonObject("minecraft:overworld")
                .getAsJsonObject("generator").get("settings").getAsString());
        assertTrue(files.containsKey(VanillaAssets.path("density_function", WorldgenCatalog.CURRENT_SETTINGS + "/height_delta")));
    }
    @Test void historicalWorldResourcesRemainSemanticallyIdentical() throws Exception {
        var files = catalog();
        try (var zip = new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.0-alpha.1.jar")) {
            int checked = 0;
            for (var entry : Collections.list(zip.entries())) {
                if (!entry.getName().startsWith("data/freshgenerator/") || !entry.getName().endsWith(".json")) continue;
                assertNotNull(files.get(entry.getName()), entry.getName());
                try (var input = zip.getInputStream(entry)) {
                    var before = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    var after = JsonParser.parseString(files.get(entry.getName()).toString());
                    assertEquals(before, after, entry.getName());
                }
                checked++;
            }
            assertEquals(322, checked);
        }
    }
    @Test void noOtherSelectionTagReintroducesLegacyModes() throws Exception {
        var files = catalog();
        var tags = files.keySet().stream().filter(p -> p.contains("/tags/worldgen/world_preset/")).toList();
        assertEquals(List.of(WorldgenCatalog.NORMAL_TAG), tags);
        String tag = files.get(WorldgenCatalog.NORMAL_TAG).toString();
        for (String old : List.of("balanced", "highlands", "_v1", "_v2")) assertFalse(tag.contains(old));
    }
    @Test void onlyPublicWorldNameIsTranslatedAndHasNoVariantSuffix() throws Exception {
        for (String locale : List.of("en_us", "ru_ru")) {
            try (var input = getClass().getResourceAsStream("/assets/freshgenerator/lang/" + locale + ".json")) {
                assertNotNull(input);
                var language = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(Set.of("generator.freshgenerator.world"), language.keySet());
                assertEquals("FreshGenerator", language.get("generator.freshgenerator.world").getAsString());
            }
        }
    }
    @Test void rarityAndOtherModsRegistriesAreNotReplaced() throws Exception {
        for (String path : catalog().keySet()) {
            assertTrue(path.startsWith("data/freshgenerator/") || path.equals(WorldgenCatalog.NORMAL_TAG), path);
            for (String registry : List.of("biome", "structure", "structure_set", "placed_feature", "feature"))
                assertFalse(path.contains("/worldgen/" + registry + "/"), path);
        }
    }
    @Test void catalogIsDeterministicAndPublishedMapCannotBeMutated() throws Exception {
        var files = catalog();
        assertEquals(files, catalog());
        assertThrows(UnsupportedOperationException.class, () -> files.clear());
    }
}
