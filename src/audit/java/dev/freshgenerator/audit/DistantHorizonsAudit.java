package dev.freshgenerator.audit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Request FULL chunks through DH's internal-server generator, with no mod dependency at build time. */
final class DistantHorizonsAudit {
    private static final String PREFIX = "com.seibel.distanthorizons.";

    private DistantHorizonsAudit() { }

    static Map<Long, ChunkAccess> load(MinecraftServer server, ServerLevel level,
                                      List<HydrologyWorldAudit.Window> windows) throws Exception {
        var shared = Class.forName(PREFIX + "core.api.internal.SharedApi");
        var world = shared.getMethod("getAbstractDhWorld").invoke(null);
        var wrapper = Class.forName(PREFIX + "common.wrappers.world.ServerLevelWrapper")
                .getMethod("getWrapper", ServerLevel.class).invoke(null, level);
        var dhLevel = Class.forName(PREFIX + "core.world.IDhWorld")
                .getMethod("getOrLoadLevel", Class.forName(PREFIX + "core.wrapperInterfaces.world.ILevelWrapper"))
                .invoke(world, wrapper);
        var generator = Class.forName(PREFIX + "common.wrappers.worldGeneration.DhChunkGenerator");
        var constructor = generator.getConstructor(Class.forName(PREFIX + "core.level.IDhServerLevel"));
        var modeType = Class.forName(PREFIX + "api.enums.worldGeneration.EDhApiDistantGeneratorMode");
        var stepType = Class.forName(PREFIX + "api.enums.worldGeneration.EDhApiWorldGenerationStep");
        var queue = generator.getMethod("queueGenEvent", int.class, int.class, int.class, modeType, stepType,
                ExecutorService.class, Consumer.class);
        var getChunk = Class.forName(PREFIX + "common.wrappers.chunk.ChunkWrapper").getMethod("getChunk");
        Object mode = modeType.getField("INTERNAL_SERVER").get(null);
        Object step = stepType.getField("FEATURES").get(null);
        Map<Long, ChunkAccess> chunks = new ConcurrentHashMap<>();
        try (var instance = (AutoCloseable) constructor.newInstance(dhLevel);
             var executor = Executors.newFixedThreadPool(2)) {
            for (var window : windows) {
                var site = window.site();
                int x = Math.floorDiv(site.x() - HydrologyWorldAudit.RADIUS, 16);
                int z = Math.floorDiv(site.z() - HydrologyWorldAudit.RADIUS, 16);
                Consumer<Object> collect = wrapped -> {
                    try {
                        var chunk = (ChunkAccess) getChunk.invoke(wrapped);
                        chunks.put(chunk.getPos().pack(), chunk);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("Cannot read the chunk returned by Distant Horizons", error);
                    }
                };
                var finished = (CompletableFuture<?>) queue.invoke(instance, x, z, 3, mode, step, executor, collect);
                server.managedBlock(finished.orTimeout(90, TimeUnit.SECONDS)::isDone);
                finished.join();
                for (int dz = 0; dz < 3; dz++) for (int dx = 0; dx < 3; dx++) {
                    var pos = new ChunkPos(x + dx, z + dz);
                    var chunk = chunks.get(pos.pack());
                    if (chunk == null || chunk.getPersistedStatus() != ChunkStatus.FULL) {
                        throw new AssertionError("Distant Horizons did not return a FULL chunk at " + pos);
                    }
                }
            }
        }
        return new TreeMap<>(chunks);
    }
}
