package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

import java.util.Objects;
import java.util.function.IntBinaryOperator;

/** Surface sources enter the native noise fill before materials, carvers and features. */
public final class HydrologyAquifer implements Aquifer {
    private final Aquifer nativeAquifer;
    private final IntBinaryOperator water;
    private final int seaLevel;
    private int lastX, lastZ;
    private boolean hasColumn;
    private int column;
    private boolean surfaceWater;
    // Like the native aquifer, this instance is confined to its NoiseChunk task.
    // Keep protection lookups separate: carving and fluid fill may be interleaved.
    private int sealX, sealZ;
    private boolean hasSeal;
    private final int[] sealProfiles = new int[5];

    public HydrologyAquifer(Aquifer nativeAquifer, DrainageDensity.Sampler drainage) {
        this(nativeAquifer, drainage::waterAt, drainage.seaLevel());
    }

    HydrologyAquifer(Aquifer nativeAquifer, IntBinaryOperator water, int seaLevel) {
        this.nativeAquifer = Objects.requireNonNull(nativeAquifer);
        this.water = Objects.requireNonNull(water);
        this.seaLevel = seaLevel;
    }

    @Override
    public BlockState computeSubstance(int x, int y, int z, double density) {
        surfaceWater = false;
        if (density <= 0 && y >= seaLevel - 18) {
            if (!hasColumn || x != lastX || z != lastZ) {
                column = water.applyAsInt(x, z);
                lastX = x;
                lastZ = z;
                hasColumn = true;
            }
            if (column != DrainageModel.DRY && y <= DrainageModel.waterTop(column) && y > DrainageModel.floor(column)) {
                surfaceWater = true;
                return Blocks.WATER.defaultBlockState();
            }
        }
        return nativeAquifer.computeSubstance(x, y, z, density);
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return !surfaceWater && nativeAquifer.shouldScheduleFluidUpdate();
    }

    public boolean protects(int x, int y, int z) {
        if (y < seaLevel - 23) return false;
        if (!hasSeal || x != sealX || z != sealZ) {
            // A vertical carving scan reuses these five immutable X/Z profiles.
            // Do not coalesce their intervals: separated levels must retain gaps.
            hasSeal = false;
            sealProfiles[0] = water.applyAsInt(x, z);
            sealProfiles[1] = water.applyAsInt(x - 1, z);
            sealProfiles[2] = water.applyAsInt(x + 1, z);
            sealProfiles[3] = water.applyAsInt(x, z - 1);
            sealProfiles[4] = water.applyAsInt(x, z + 1);
            sealX = x;
            sealZ = z;
            hasSeal = true;
        }
        if (withinSeal(sealProfiles[0], y)) return true;
        for (int i = 1; i < sealProfiles.length; i++) {
            int profile = sealProfiles[i];
            // Below the adjoining channel bed, its own solid seal already
            // separates the water from any cave under the dry bank.
            // The native aquifer already fills passages at/below sea level.
            // Sealing an unmodified neighboring column there would leave a
            // floating soil plug where the original carver produced water.
            if (profile != DrainageModel.DRY && DrainageModel.waterTop(profile) >= seaLevel
                    && y > DrainageModel.floor(profile) && y <= DrainageModel.waterTop(profile)) return true;
        }
        return false;
    }

    private static boolean withinSeal(int profile, int y) {
        return profile != DrainageModel.DRY && y >= DrainageModel.floor(profile) - 4 && y <= DrainageModel.waterTop(profile) + 1;
    }
}
