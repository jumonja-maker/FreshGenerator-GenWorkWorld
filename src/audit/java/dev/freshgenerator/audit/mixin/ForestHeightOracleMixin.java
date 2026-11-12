package dev.freshgenerator.audit.mixin;

import dev.freshgenerator.audit.ForestHeightOracle;
import dev.freshgenerator.forest.ForestEnvironment;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enabled only by the technical-audit scratch mod, never included in the game JAR. */
@Mixin(ForestEnvironment.class)
public abstract class ForestHeightOracleMixin {
    @Unique private ForestHeightOracle freshgenerator$heightOracle;

    @Inject(method = "height", at = @At("HEAD"), require = 1)
    private void freshgenerator$before(int x, int z, CallbackInfoReturnable<Float> ci) {
        if (freshgenerator$heightOracle == null) freshgenerator$heightOracle = new ForestHeightOracle();
        freshgenerator$heightOracle.begin(x, z);
    }

    @Redirect(method = "height", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySampler;sampleValue(Lnet/minecraft/world/level/levelgen/densityfunction/SamplerContext;III)F"), require = 1)
    private float freshgenerator$nativeSample(DensitySampler sampler, SamplerContext context, int x, int y, int z) {
        float result = sampler.sampleValue(context, x, y, z);
        freshgenerator$heightOracle.sampled(x, y, z, result);
        return result;
    }

    @Inject(method = "height", at = @At("RETURN"), require = 1)
    private void freshgenerator$after(int x, int z, CallbackInfoReturnable<Float> ci) {
        freshgenerator$heightOracle.finish(ci.getReturnValueF());
    }
}
