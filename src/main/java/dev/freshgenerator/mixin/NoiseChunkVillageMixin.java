package dev.freshgenerator.mixin;

import dev.freshgenerator.village.*;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.Objects;

@Mixin(NoiseChunk.class)
abstract class NoiseChunkVillageMixin implements VillageNoiseAccess {
    @Unique private VillageTerrainPlan freshgenerator$plan=VillageTerrainPlan.EMPTY;
    @Override public VillageTerrainPlan freshgenerator$villagePlan(){return freshgenerator$plan;}
    @Override public void freshgenerator$villagePlan(VillageTerrainPlan plan){freshgenerator$plan=Objects.requireNonNull(plan);}
}
