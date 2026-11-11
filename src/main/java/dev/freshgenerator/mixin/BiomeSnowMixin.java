package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.surface.SnowTransitions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The current 26.3 shouldSnow method first requests precipitation, then checks placement safety. */
@Mixin(Biome.class)
abstract class BiomeSnowMixin {
    @WrapOperation(method="shouldSnow",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"),require=1)
    private Biome.Precipitation freshgenerator$coherentSnow(Biome biome,BlockPos sample,int seaLevel,
            Operation<Biome.Precipitation> original,LevelReader reader,BlockPos position){
        return SnowTransitions.precipitation(reader,position,original.call(biome,sample,seaLevel));
    }
}
