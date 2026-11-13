package dev.freshgenerator.audit;

import com.google.gson.Gson;
import dev.freshgenerator.core.DrainageModel;
import dev.freshgenerator.hydrology.DrainageDensity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Test observer only. Native fluid ticks stay enabled; this never writes a block.
 * Generate the entire ticking halo BEFORE the initial snapshot, so delayed
 * neighbouring decoration cannot be mislabelled as a fluid-update regression. */
public final class HydrologySettlingAudit implements ModInitializer {
    private static final Gson JSON = new Gson();
    private static final int[] CHECKPOINTS = {200, 1000};
    private HydrologyWorldAudit.Plan plan;
    private final List<Map<String, Object>> checkpoints = new ArrayList<>();
    private int ticks = -1;
    private int forcedChunks;

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var level = server.overworld();
                var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
                var sampler = DrainageDensity.from(level.getChunkSource().randomState(), generator.generatorSettings().value());
                if (sampler == null) throw new AssertionError("Missing hydrology sampler");
                Path path = Path.of(System.getenv("FG_HYDROLOGY_PLAN"));
                String fixed = System.getenv("FG_HYDROLOGY_SETTLE_SITES");
                var windows = new ArrayList<HydrologyWorldAudit.Window>();
                if (fixed != null) {
                    var sites = JSON.fromJson(Files.readString(Path.of(fixed)), HydrologyWorldAudit.Plan.class);
                    if (sites.seed() != level.getSeed() || sites.windows().isEmpty())
                        throw new AssertionError("Wrong fixed-site seed or empty plan");
                    for (var window : sites.windows()) {
                        var s = window.site();
                        int[] profiles = new int[33 * 33];
                        for (int z = -16; z <= 16; z++) for (int x = -16; x <= 16; x++)
                            profiles[(z + 16) * 33 + x + 16] = sampler.waterAt(s.x() + x, s.z() + z);
                        windows.add(new HydrologyWorldAudit.Window(s, profiles));
                    }
                    plan = new HydrologyWorldAudit.Plan(level.getSeed(), windows);
                    Files.writeString(path, JSON.toJson(plan));
                } else {
                    if (!Files.exists(path)) Files.writeString(path, JSON.toJson(new HydrologyWorldAudit.Plan(
                            level.getSeed(), HydrologyAuditProfiles.select(level, generator))));
                    plan = JSON.fromJson(Files.readString(path), HydrologyWorldAudit.Plan.class);
                }
                if (plan.seed() != level.getSeed() || plan.windows().isEmpty()) throw new AssertionError("Invalid plan");
                var halo = new TreeSet<Long>();
                for (var window : plan.windows()) {
                    var s = window.site();
                    for (int z = Math.floorDiv(s.z() - 32, 16); z <= Math.floorDiv(s.z() + 32, 16); z++)
                        for (int x = Math.floorDiv(s.x() - 32, 16); x <= Math.floorDiv(s.x() + 32, 16); x++)
                            halo.add(new ChunkPos(x, z).pack());
                }
                for (long packed : halo) {
                    var c = ChunkPos.unpack(packed);
                    var chunk = level.getChunkSource().getChunk(c.x(), c.z(), ChunkStatus.FULL, true);
                    if (chunk == null || chunk.getPersistedStatus() != ChunkStatus.FULL) throw new AssertionError("Incomplete halo");
                    level.setChunkForced(c.x(), c.z(), true);
                }
                forcedChunks = halo.size();
                ticks = 0;
                snapshot(server, 0);
                System.out.println("FRESHGENERATOR_SETTLING_STARTED " + plan.seed() + " " + forcedChunks);
            } catch (Exception | AssertionError error) { fail(server, error); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ticks < 0) return;
            ticks++;
            if (ticks != CHECKPOINTS[0] && ticks != CHECKPOINTS[1]) return;
            try {
                snapshot(server, ticks);
                if (ticks == CHECKPOINTS[1]) {
                    var meta = new LinkedHashMap<String, Object>();
                    meta.put("seed", plan.seed());
                    meta.put("jar_sha256", System.getenv("FG_HYDROLOGY_JAR_SHA256"));
                    meta.put("completed_server_ticks", ticks);
                    meta.put("native_fluid_updates", true);
                    meta.put("halo_generated_before_snapshot", true);
                    meta.put("forced_chunks", forcedChunks);
                    meta.put("checkpoints", checkpoints);
                    meta.put("scope", "Finite forced FULL-chunk windows, normal native ticks, no players, no block edits");
                    Files.writeString(Path.of("settling-meta.json"), JSON.toJson(meta) + "\n");
                    System.out.println("FRESHGENERATOR_SETTLING_COMPLETE " + plan.seed() + " " + ticks);
                    ticks = -1;
                    server.halt(false);
                }
            } catch (Exception | AssertionError error) { fail(server, error); }
        });
    }

    private void snapshot(MinecraftServer server, int tick) throws Exception {
        var snapshot = HydrologyWorldAudit.inspect(server);
        Files.writeString(Path.of("settling-" + tick + ".json"), JSON.toJson(snapshot) + "\n");
        int planned = 0, sourcesOrIce = 0;
        var missing = new ArrayList<Map<String, Object>>();
        var pos = new BlockPos.MutableBlockPos();
        for (var window : plan.windows()) for (int i = 0; i < window.profiles().length; i++) {
            int profile = window.profiles()[i];
            if (profile == DrainageModel.DRY) continue;
            planned++;
            int x = window.site().x() + i % 33 - 16, z = window.site().z() + i / 33 - 16;
            int y = DrainageModel.waterTop(profile);
            var state = server.overworld().getBlockState(pos.set(x, y, z));
            if (state.is(Blocks.ICE) || state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource()) sourcesOrIce++;
            else missing.add(Map.of("x", x, "y", y, "z", z, "state", state.toString()));
        }
        checkpoints.add(Map.of("tick", tick, "planned_columns", planned,
                "sources_or_ice_at_planned_stage", sourcesOrIce, "missing", missing));
        System.out.println("FRESHGENERATOR_SETTLING_SNAPSHOT " + tick + " " + planned + " " + sourcesOrIce);
    }

    private void fail(MinecraftServer server, Throwable error) {
        ticks = -1;
        error.printStackTrace();
        System.out.println("FRESHGENERATOR_SETTLING_FAIL");
        server.halt(false);
    }
}
