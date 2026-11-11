package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Observes the active generator at the ground surface of isolated FULL chunks. */
final class BiomeTransitionAudit {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SETTINGS = "freshgenerator:highlands_v2";
    private static final int SEARCH_RADIUS = 16_384;
    private static final int COARSE_STEP = 256;
    private static final int WINDOW_SPAN = 16;
    private static final int HIGHLAND_Y = 128;
    private static final int MAX_WINDOWS_PER_TARGET = 4;
    private static final Set<String> CAVE_BIOMES = Set.of("minecraft:dripstone_caves", "minecraft:lush_caves",
            "minecraft:sulfur_caves", "minecraft:deep_dark");
    private static final Set<Block> DECORATION = Set.of(Blocks.BAMBOO, Blocks.POINTED_DRIPSTONE, Blocks.CACTUS,
            Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.MELON, Blocks.PUMPKIN);

    private record Probe(String biome, float envelopeY) { }
    private record Window(int x, int z, int biomes, float maxEnvelopeY) { }
    private record Column(int x, int z, int baseY, int groundY, float envelopeY,
                          String biome, String material, boolean waterAbove) { }

    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    private final MultiNoiseBiomeSource biomeSource;
    private final RandomState state;
    private final DensitySampler height;
    private final SamplerContext heightContext = SamplerContext.builder().enableCaches().build();
    private final BiomeManager predictedBiomes;
    private final Map<Long, Probe> probes = new HashMap<>();
    private final Set<Long> fullChunks = new HashSet<>();

    private BiomeTransitionAudit(MinecraftServer server) {
        level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            throw new AssertionError("Expected native noise generator");
        }
        generator = noiseGenerator;
        if (!(generator.getBiomeSource() instanceof MultiNoiseBiomeSource nativeBiomes)) {
            throw new AssertionError("Expected native multi-noise biomes");
        }
        biomeSource = nativeBiomes;
        String actual = generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
        if (!SETTINGS.equals(actual)) throw new AssertionError("Wrong generator settings: " + actual);
        state = level.getChunkSource().randomState();
        var heightFunction = server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(
                ResourceKey.create(Registries.DENSITY_FUNCTION,
                        Identifier.parse(SETTINGS + "/coherent/height"))).value();
        height = state.getSampler(heightFunction);
        predictedBiomes = level.getBiomeManager().withDifferentSource(biomeSource.createCachingResolver(state));
    }

    static void run(MinecraftServer server) {
        try {
            JsonObject report = new BiomeTransitionAudit(server).inspect();
            Files.writeString(Path.of("freshgenerator-biome-transitions.json"), GSON.toJson(report) + "\n");
            System.out.println("FRESHGENERATOR_BIOME_AUDIT_PASS seed=" + report.get("seed")
                    + " junctions=" + report.getAsJsonArray("junctions").size());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write biome transition audit", e);
        } finally {
            server.halt(false);
        }
    }

    private JsonObject inspect() {
        List<Window> candidates = findWindows();
        System.out.println("BIOME_AUDIT_STAGE seed=" + level.getSeed() + " candidates=" + candidates.size());
        JsonObject report = new JsonObject();
        report.addProperty("seed", level.getSeed());
        report.addProperty("settings", SETTINGS);
        report.addProperty("search_radius", SEARCH_RADIUS);
        report.addProperty("coarse_step", COARSE_STEP);
        report.addProperty("window_side", WINDOW_SPAN + 1);
        report.addProperty("highland_min_y", HIGHLAND_Y);
        report.addProperty("noise_biome_probes", probes.size());
        report.addProperty("candidate_windows", candidates.size());
        JsonArray junctions = new JsonArray();
        List<Window> sampled = new ArrayList<>();
        for (boolean highland : List.of(true, false)) {
            for (int target : List.of(4, 3)) {
                int inspected = 0;
                for (Window window : candidates) {
                    if (window.biomes() != target || (window.maxEnvelopeY() >= HIGHLAND_Y) != highland) continue;
                    if (sampled.stream().anyMatch(w -> Math.abs(w.x() - window.x()) <= WINDOW_SPAN
                            && Math.abs(w.z() - window.z()) <= WINDOW_SPAN)) continue;
                    JsonObject observation = observe(window);
                    sampled.add(window);
                    int count = observation.getAsJsonObject("biome_columns").size();
                    if (count >= 3) junctions.add(observation);
                    String vertex = target == 4 ? "first_quadruple_vertex" : "first_triple_vertex";
                    if (++inspected == MAX_WINDOWS_PER_TARGET || (count == target && observation.has(vertex))) break;
                }
            }
        }
        report.addProperty("full_windows_scanned", sampled.size());
        report.addProperty("full_chunks_requested", fullChunks.size());
        report.add("junctions", junctions);
        return report;
    }

    private List<Window> findWindows() {
        List<Window> windows = new ArrayList<>();
        for (int x = -SEARCH_RADIUS; x < SEARCH_RADIUS; x += COARSE_STEP) {
            for (int z = -SEARCH_RADIUS; z < SEARCH_RADIUS; z += COARSE_STEP) {
                collectWindows(x, z, COARSE_STEP, windows);
            }
        }
        windows.sort(Comparator.comparingInt(Window::biomes).reversed()
                .thenComparing(Comparator.comparingDouble(Window::maxEnvelopeY).reversed()));
        return windows;
    }

    private void collectWindows(int x, int z, int step, List<Window> windows) {
        Window window = probeWindow(x, z, step);
        if (step == WINDOW_SPAN) {
            if (window.biomes() >= 3) windows.add(window);
            return;
        }
        // Inside a coarse junction tile, follow two-biome edges as well as junctions.
        if (window.biomes() < (step == COARSE_STEP ? 3 : 2)) return;
        int half = step / 2;
        collectWindows(x, z, half, windows);
        collectWindows(x + half, z, half, windows);
        collectWindows(x, z + half, half, windows);
        collectWindows(x + half, z + half, half, windows);
    }

    private Window probeWindow(int x, int z, int step) {
        List<Probe> corners = List.of(probe(x, z), probe(x + step, z),
                probe(x, z + step), probe(x + step, z + step));
        int distinct = (int) corners.stream().map(Probe::biome).distinct().count();
        float max = (float) corners.stream().mapToDouble(Probe::envelopeY).max().orElseThrow();
        return new Window(x, z, distinct, max);
    }

    private Probe probe(int x, int z) {
        return probes.computeIfAbsent(pack(x, z), key -> {
            float y = envelope(x, z);
            int surfaceY = (int) Math.ceil(y) - 1;
            var biome = predictedBiomes.getBiome(x, surfaceY, z);
            return new Probe(biome.unwrapKey().orElseThrow().identifier().toString(), y);
        });
    }

    private float envelope(int x, int z) {
        float y = height.sampleValue(heightContext, x, 0, z);
        if (!Float.isFinite(y)) throw new AssertionError("Non-finite terrain envelope at " + x + ", " + z);
        return y;
    }

    private JsonObject observe(Window window) {
        int minCx = Math.floorDiv(window.x(), 16);
        int minCz = Math.floorDiv(window.z(), 16);
        int maxCx = Math.floorDiv(window.x() + WINDOW_SPAN, 16);
        int maxCz = Math.floorDiv(window.z() + WINDOW_SPAN, 16);
        // Finish the neighbouring chunks before reading the decorated surface.
        for (int cx = minCx - 1; cx <= maxCx + 1; cx++) {
            for (int cz = minCz - 1; cz <= maxCz + 1; cz++) {
                level.getChunk(cx, cz);
                fullChunks.add(pack(cx, cz));
            }
        }

        Column[][] grid = new Column[WINDOW_SPAN + 1][WINDOW_SPAN + 1];
        Map<String, Integer> biomes = new TreeMap<>();
        EdgeStats same = new EdgeStats();
        EdgeStats across = new EdgeStats();
        Map<String, Integer> contacts = new TreeMap<>();
        JsonObject result = new JsonObject();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, wet = 0, dryRiver = 0, exposedCave = 0;
        Column highestDryRiver = null;
        Column highestExposedCave = null;
        int tripleVertices = 0, quadrupleVertices = 0;
        for (int dx = 0; dx <= WINDOW_SPAN; dx++) {
            for (int dz = 0; dz <= WINDOW_SPAN; dz++) {
                Column c = column(window.x() + dx, window.z() + dz);
                grid[dx][dz] = c;
                biomes.merge(c.biome(), 1, Integer::sum);
                minY = Math.min(minY, c.groundY());
                maxY = Math.max(maxY, c.groundY());
                if (c.waterAbove()) wet++;
                if (!c.waterAbove() && c.groundY() >= level.getSeaLevel()
                        && (c.biome().equals("minecraft:river") || c.biome().equals("minecraft:frozen_river"))) {
                    dryRiver++;
                    if (highestDryRiver == null || c.groundY() > highestDryRiver.groundY()) highestDryRiver = c;
                }
                if (!c.waterAbove() && c.groundY() == c.baseY() && CAVE_BIOMES.contains(c.biome())
                        && level.canSeeSky(new BlockPos(c.x(), c.groundY() + 1, c.z()))) {
                    exposedCave++;
                    if (highestExposedCave == null || c.groundY() > highestExposedCave.groundY()) highestExposedCave = c;
                }
                if (dx > 0) recordEdge(grid[dx - 1][dz], c, same, across, contacts);
                if (dz > 0) recordEdge(grid[dx][dz - 1], c, same, across, contacts);
                if (dx > 0 && dz > 0) {
                    var square = List.of(grid[dx - 1][dz - 1], grid[dx - 1][dz], grid[dx][dz - 1], c);
                    int distinct = (int) square.stream().map(Column::biome).distinct().count();
                    if (distinct == 3 && tripleVertices++ == 0) result.add("first_triple_vertex", GSON.toJsonTree(square));
                    if (distinct == 4 && quadrupleVertices++ == 0) result.add("first_quadruple_vertex", GSON.toJsonTree(square));
                }
            }
        }
        result.addProperty("min_x", window.x());
        result.addProperty("min_z", window.z());
        result.addProperty("min_ground_y", minY);
        result.addProperty("max_ground_y", maxY);
        result.addProperty("max_corner_envelope_y", window.maxEnvelopeY());
        result.addProperty("highland", window.maxEnvelopeY() >= HIGHLAND_Y);
        result.add("biome_columns", GSON.toJsonTree(biomes));
        result.addProperty("vertices_with_three_biomes", tripleVertices);
        result.addProperty("vertices_with_four_biomes", quadrupleVertices);
        result.addProperty("wet_columns", wet);
        result.addProperty("dry_river_columns_above_sea", dryRiver);
        if (highestDryRiver != null) result.add("highest_dry_river", GSON.toJsonTree(highestDryRiver));
        result.addProperty("exposed_cave_biome_columns_at_base_height", exposedCave);
        if (highestExposedCave != null) {
            result.add("highest_exposed_cave_biome", GSON.toJsonTree(highestExposedCave));
            result.add("cave_biome_depth_check", depthCheck(highestExposedCave));
        }
        result.add("biome_contacts", GSON.toJsonTree(contacts));
        result.add("within_biome", same.toJson());
        result.add("across_biomes", across.toJson());
        return result;
    }

    private Column column(int x, int z) {
        int baseY = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, state) - 1;
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        var block = level.getBlockState(pos);
        var ground = Heightmap.Types.OCEAN_FLOOR.isOpaque();
        while (y > level.getMinY() && (!ground.test(block) || block.is(BlockTags.LOGS) || block.is(BlockTags.LEAVES)
                || DECORATION.contains(block.getBlock()))) {
            pos.setY(--y);
            block = level.getBlockState(pos);
        }
        String biome = level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
        String material = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        boolean water = level.getFluidState(pos.above()).is(FluidTags.WATER);
        return new Column(x, z, baseY, y, envelope(x, z), biome, material, water);
    }

    private JsonObject depthCheck(Column c) {
        var climate = state.createClimateSampler(SamplerContext.builder().enableCaches().build());
        JsonObject result = new JsonObject();
        // Use the same biome zoom to capture the actual climate cell for the ground block.
        var probeManager = level.getBiomeManager().withDifferentSource((qx, qy, qz) -> {
            var point = climate.sample(qx, qy, qz);
            float envelopeDepth = (envelope(qx * 4, qz * 4) - qy * 4) / 128f;
            var adjusted = new Climate.TargetPoint(point.temperature(), point.humidity(), point.continentalness(),
                    point.erosion(), Climate.quantizeCoord(envelopeDepth), point.weirdness());
            result.add("climate_cell_block_origin", GSON.toJsonTree(List.of(qx * 4, qy * 4, qz * 4)));
            result.addProperty("native_depth", Climate.unquantizeCoord(point.depth()));
            result.addProperty("envelope_depth", envelopeDepth);
            result.addProperty("biome_if_only_depth_followed_envelope",
                    biomeSource.getNoiseBiome(adjusted).unwrapKey().orElseThrow().identifier().toString());
            return biomeSource.getNoiseBiome(point);
        });
        var biome = probeManager.getBiome(c.x(), c.groundY(), c.z());
        result.addProperty("native_sampler_matches_full_biome",
                c.biome().equals(biome.unwrapKey().orElseThrow().identifier().toString()));
        return result;
    }

    private static void recordEdge(Column a, Column b, EdgeStats same, EdgeStats across, Map<String, Integer> contacts) {
        if (a.biome().equals(b.biome())) {
            same.add(a, b);
        } else {
            across.add(a, b);
            String key = a.biome().compareTo(b.biome()) < 0
                    ? a.biome() + " / " + b.biome() : b.biome() + " / " + a.biome();
            contacts.merge(key, 1, Integer::sum);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static final class EdgeStats {
        private int pairs, changedMaterial, chunkBorderPairs, maxBaseStep, maxGroundStep;
        private float maxEnvelopeStep;
        private List<Column> steepestGroundEdge = List.of();

        void add(Column a, Column b) {
            pairs++;
            if (!a.material().equals(b.material())) changedMaterial++;
            if (Math.floorDiv(a.x(), 16) != Math.floorDiv(b.x(), 16)
                    || Math.floorDiv(a.z(), 16) != Math.floorDiv(b.z(), 16)) chunkBorderPairs++;
            maxEnvelopeStep = Math.max(maxEnvelopeStep, Math.abs(a.envelopeY() - b.envelopeY()));
            maxBaseStep = Math.max(maxBaseStep, Math.abs(a.baseY() - b.baseY()));
            int groundStep = Math.abs(a.groundY() - b.groundY());
            if (groundStep > maxGroundStep || steepestGroundEdge.isEmpty()) {
                maxGroundStep = groundStep;
                steepestGroundEdge = List.of(a, b);
            }
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("pairs", pairs);
            result.addProperty("different_material_pairs", changedMaterial);
            result.addProperty("chunk_border_pairs", chunkBorderPairs);
            result.addProperty("max_envelope_step", maxEnvelopeStep);
            result.addProperty("max_base_step", maxBaseStep);
            result.addProperty("max_ground_step", maxGroundStep);
            result.add("steepest_ground_edge", GSON.toJsonTree(steepestGroundEdge));
            return result;
        }
    }
}
