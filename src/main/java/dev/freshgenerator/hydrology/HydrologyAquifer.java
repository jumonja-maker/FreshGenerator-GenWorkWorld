package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

/** Surface sources enter the native noise fill before materials, carvers and features. */
public final class HydrologyAquifer implements Aquifer {
    private final Aquifer nativeAquifer;
    private final DrainageDensity.Sampler drainage;
    private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    private int column;
    private boolean surfaceWater;

    public HydrologyAquifer(Aquifer nativeAquifer, DrainageDensity.Sampler drainage) {
        this.nativeAquifer = nativeAquifer;
        this.drainage = drainage;
    }

    @Override
    public BlockState computeSubstance(int x, int y, int z, double density) {
        surfaceWater = false;
        if (density <= 0 && y >= drainage.seaLevel() - 18) {
            if (x != lastX || z != lastZ) {
                column = drainage.waterAt(x, z);
                lastX = x;
                lastZ = z;
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
        if (y < drainage.seaLevel() - 23) return false;
        return withinSeal(drainage.waterAt(x, z), y)
                || withinSeal(drainage.waterAt(x - 1, z), y)
                || withinSeal(drainage.waterAt(x + 1, z), y)
                || withinSeal(drainage.waterAt(x, z - 1), y)
                || withinSeal(drainage.waterAt(x, z + 1), y);
    }

    private static boolean withinSeal(int profile, int y) {
        return profile != DrainageModel.DRY && y >= DrainageModel.floor(profile) - 4 && y <= DrainageModel.waterTop(profile) + 1;
    }
}
