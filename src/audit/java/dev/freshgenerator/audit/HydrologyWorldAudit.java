package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Read-only inspection of persisted block states in native FULL chunks. */
public final class HydrologyWorldAudit implements ModInitializer {
    static final int RADIUS = 16;
    static final int SIDE = 2 * RADIUS + 1;
    private static final int MISSING = -999;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    record Site(String kind, int x, int z) { }
    record Window(Site site, int[] profiles) { }
    record Plan(long seed, List<Window> windows) { }
    record Observation(Site site, int[][] columns, Map<String, Integer> surfaceBiomes) { }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var result = inspect(server);
                Files.writeString(Path.of("hydrology-observation.json"), JSON.toJson(result) + "\n");
                System.out.println("FRESHGENERATOR_HYDROLOGY_WORLD_PASS " + result.get("mode") + " " + result.get("seed"));
            } catch (Exception | AssertionError error) {
                error.printStackTrace();
                System.out.println("FRESHGENERATOR_HYDROLOGY_WORLD_FAIL");
            } finally {
                server.halt(false);
            }
        });
    }

    private static Map<String, Object> inspect(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        String mode = System.getenv().getOrDefault("FG_HYDROLOGY_MODE", "candidate");
        String expected = mode.startsWith("normal") ? "minecraft:overworld" : "freshgenerator:highlands_v2";
        String settings = generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
        if (!settings.equals(expected)) throw new AssertionError("Unexpected generator: " + settings);
        long seed = level.getSeed();
        Path planFile = Path.of(System.getenv("FG_HYDROLOGY_PLAN"));
        long planStart = System.nanoTime();
        if (mode.equals("candidate") && !Files.exists(planFile)) {
            var plan = new Plan(seed, HydrologyAuditProfiles.select(level, generator));
            Files.writeString(planFile, JSON.toJson(plan) + "\n");
        }
        Plan plan = JSON.fromJson(Files.readString(planFile), Plan.class);
        if (seed != plan.seed || plan.windows.isEmpty()) throw new AssertionError("Invalid comparison plan");
        long planNanos = System.nanoTime() - planStart;

        var ordered = new TreeMap<Long, ChunkPos>();
        for (Window window : plan.windows) {
            Site site = window.site;
            for (int z = Math.floorDiv(site.z - RADIUS, 16); z <= Math.floorDiv(site.z + RADIUS, 16); z++) {
                for (int x = Math.floorDiv(site.x - RADIUS, 16); x <= Math.floorDiv(site.x + RADIUS, 16); x++) {
                    var pos = new ChunkPos(x, z);
                    ordered.put(pos.pack(), pos);
                }
            }
        }
        List<ChunkPos> order = new ArrayList<>(ordered.values());
        boolean concurrent = mode.equals("parallel");
        if (concurrent || mode.equals("reverse")) Collections.reverse(order);
        long generationStart = System.nanoTime();
        var chunks = load(server, level, order, concurrent);
        long generationNanos = System.nanoTime() - generationStart;
        List<Observation> observations = new ArrayList<>();
        boolean candidateHydrology = !mode.startsWith("normal-") && !mode.endsWith("baseline");
        for (Window window : plan.windows) observations.add(read(window, chunks, candidateHydrology));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("jar_sha256", System.getenv("FG_HYDROLOGY_JAR_SHA256"));
        result.put("seed", seed);
        result.put("settings", settings);
        result.put("full_chunks", chunks.size());
        result.put("requested_parallel", concurrent);
        result.put("plan_ms", planNanos / 1_000_000);
        result.put("chunk_generation_ms", generationNanos / 1_000_000);
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("java", Runtime.version().toString());
        result.put("distant_horizons", FabricLoader.getInstance().getModContainer("distanthorizons")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(""));
        result.put("windows", observations);
        return result;
    }

    private static Map<Long, ChunkAccess> load(MinecraftServer server, ServerLevel level, List<ChunkPos> order, boolean concurrent) {
        Map<Long, ChunkAccess> chunks = new TreeMap<>();
        var source = level.getChunkSource();
        if (concurrent) {
            // Concurrent native FULL requests, resolved by the game's chunk scheduler.
            var requests = order.stream().map(p -> source.getChunkFuture(p.x(), p.z(), ChunkStatus.FULL, true)).toList();
            var all = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new));
            server.managedBlock(all::isDone);
            for (int i = 0; i < order.size(); i++) {
                chunks.put(order.get(i).pack(), requests.get(i).join().orElseThrow(() -> new AssertionError("Missing async FULL chunk")));
            }
        } else {
            for (ChunkPos p : order) chunks.put(p.pack(), source.getChunk(p.x(), p.z(), ChunkStatus.FULL, true));
        }
        for (var chunk : chunks.values()) {
            if (!(chunk instanceof LevelChunk) || chunk.getPersistedStatus() != ChunkStatus.FULL) {
                throw new AssertionError("Not a fully generated chunk: " + chunk.getPos());
            }
        }
        return chunks;
    }

    private static Observation read(Window window, Map<Long, ChunkAccess> chunks, boolean candidateHydrology) {
        var pos = new BlockPos.MutableBlockPos();
        int[][] columns = new int[SIDE * SIDE][];
        Map<String, Integer> biomes = new TreeMap<>();
        for (int dz = 0; dz < SIDE; dz++) {
            for (int dx = 0; dx < SIDE; dx++) {
                int i = dz * SIDE + dx;
                int x = window.site.x + dx - RADIUS, z = window.site.z + dz - RADIUS;
                int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16);
                ChunkAccess chunk = chunks.get(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)).pack());
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                int water = MISSING, bed = MISSING, flags = 0;
                int profile = window.profiles[i];
                // A tree canopy or a giant mushroom cap can cover a stream.
                // Inspect the actual block at the modeled stage beneath decorations.
                if (candidateHydrology && profile != 0) {
                    int expected = profile >> 16;
                    var state = chunk.getBlockState(pos.set(x, expected, z));
                    if (isWater(state)) {
                        water = expected;
                        while (water < surface && isWater(chunk.getBlockState(pos.set(x, water + 1, z)))) water++;
                    }
                }
                if (water == MISSING) {
                    for (int y = surface; y >= Math.max(chunk.getMinY(), surface - 36); y--) {
                        var state = chunk.getBlockState(pos.set(x, y, z));
                        if (isWater(state)) {
                            water = y;
                            break;
                        }
                        if (Heightmap.Types.OCEAN_FLOOR.isOpaque().test(state)
                                && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)
                                && !state.is(Blocks.BROWN_MUSHROOM_BLOCK) && !state.is(Blocks.RED_MUSHROOM_BLOCK)
                                && !state.is(Blocks.MUSHROOM_STEM)) {
                            ground = y;
                            break;
                        }
                    }
                }
                if (water != MISSING) {
                    var top = chunk.getBlockState(pos.set(x, water, z));
                    flags |= top.is(Blocks.ICE) ? 2 : top.getFluidState().isSource() ? 1 : 0;
                    for (int y = water - 1; y >= Math.max(chunk.getMinY(), water - 24); y--) {
                        var state = chunk.getBlockState(pos.set(x, y, z));
                        if (!state.getFluidState().is(FluidTags.WATER) && !state.is(Blocks.ICE)) {
                            bed = y;
                            ground = y;
                            if (!Heightmap.Types.OCEAN_FLOOR.isOpaque().test(state)) flags |= 4;
                            for (int d = 0; d <= 4 && y - d >= chunk.getMinY(); d++) {
                                if (chunk.getBlockState(pos.set(x, y - d, z)).getFluidState().is(FluidTags.LAVA)) flags |= 8;
                            }
                            break;
                        }
                    }
                }
                int biomeY = water == MISSING ? surface : water;
                var biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(biomeY), QuartPos.fromBlock(z));
                if (biome.is(Biomes.RIVER) || biome.is(Biomes.FROZEN_RIVER)) flags |= 16;
                if (water != MISSING || window.profiles[i] != 0) {
                    biomes.merge(biome.unwrapKey().orElseThrow().identifier().toString(), 1, Integer::sum);
                }
                columns[i] = new int[]{surface, ground, water, bed, flags, window.profiles[i]};
            }
        }
        return new Observation(window.site, columns, biomes);
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.ICE);
    }
}
