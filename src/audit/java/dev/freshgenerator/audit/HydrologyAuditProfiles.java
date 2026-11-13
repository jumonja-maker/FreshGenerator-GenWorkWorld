package dev.freshgenerator.audit;

import dev.freshgenerator.core.DrainageModel;
import dev.freshgenerator.hydrology.DrainageDensity;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Used to pick shared inspection coordinates on the candidate only. */
final class HydrologyAuditProfiles {
    private HydrologyAuditProfiles() { }

    static List<HydrologyWorldAudit.Window> select(ServerLevel level, NoiseBasedChunkGenerator generator) {
        var settings = generator.generatorSettings().value();
        var state = RandomState.create(level.registryAccess().lookupOrThrow(Registries.NOISE), level.getSeed(), settings);
        var drainage = DrainageDensity.from(state, settings);
        if (drainage == null) throw new AssertionError("Candidate hydrology sampler is absent");
        int sea = drainage.seaLevel();
        Map<String, HydrologyWorldAudit.Site> picked = new LinkedHashMap<>();
        int radius = 28;
        long seed = level.getSeed();
        int phaseX = (int) Math.floorMod(seed, 17) - 8;
        int phaseZ = (int) Math.floorMod(seed >>> 12, 17) - 8;
        for (int ring = 0; ring <= radius && picked.size() < 6; ring++) {
            for (int tz = -ring; tz <= ring; tz++) {
                for (int tx = -ring; tx <= ring; tx++) {
                    if (Math.max(Math.abs(tx), Math.abs(tz)) != ring) continue;
                    int x0 = (tx + phaseX) * DrainageModel.TILE_SIZE, z0 = (tz + phaseZ) * DrainageModel.TILE_SIZE;
                    for (int dz = 0; dz < DrainageModel.TILE_SIZE; dz += 16) {
                        for (int dx = 0; dx < DrainageModel.TILE_SIZE; dx += 16) {
                            int x = x0 + dx, z = z0 + dz;
                            int profile = drainage.waterAt(x, z);
                            if (profile == DrainageModel.DRY) continue;
                            int top = DrainageModel.waterTop(profile);
                            boolean lake = DrainageModel.isLake(profile);
                            String category = lake ? (top >= sea + 10 ? "high-lake" : "low-lake")
                                    : top >= sea + 43 ? "mountain-river" : top >= sea + 4 && top <= sea + 17 ? "foothill-river"
                                    : top <= sea ? "coastal-river" : null;
                            if (lake && DrainageModel.hasSpillway(profile) && !picked.containsKey("throughflow-lake")
                                    && lowerReachNearby(drainage, x, z, top)) category = "throughflow-lake";
                            if (category != null && !picked.containsKey(category)
                                    && picked.values().stream().noneMatch(s -> Math.abs(x - s.x()) + Math.abs(z - s.z()) < 64)) {
                                picked.put(category, new HydrologyWorldAudit.Site(category, x, z));
                            }
                        }
                    }
                }
            }
        }
        if (picked.isEmpty()) throw new AssertionError("No hydrology profiles located on this seed");
        List<HydrologyWorldAudit.Window> windows = new ArrayList<>();
        for (var site : picked.values()) {
            int[] profiles = new int[HydrologyWorldAudit.SIDE * HydrologyWorldAudit.SIDE];
            for (int z = 0; z < HydrologyWorldAudit.SIDE; z++) {
                for (int x = 0; x < HydrologyWorldAudit.SIDE; x++) {
                    profiles[z * HydrologyWorldAudit.SIDE + x] = drainage.waterAt(
                            site.x() + x - HydrologyWorldAudit.RADIUS, site.z() + z - HydrologyWorldAudit.RADIUS);
                }
            }
            windows.add(new HydrologyWorldAudit.Window(site, profiles));
        }
        return windows;
    }

    private static boolean lowerReachNearby(DrainageDensity.Sampler drainage, int x, int z, int top) {
        for (int dz = -16; dz <= 16; dz += 4) {
            for (int dx = -16; dx <= 16; dx += 4) {
                int profile = drainage.waterAt(x + dx, z + dz);
                if (profile != DrainageModel.DRY && !DrainageModel.isLake(profile)
                        && DrainageModel.waterTop(profile) < top) return true;
            }
        }
        return false;
    }
}
