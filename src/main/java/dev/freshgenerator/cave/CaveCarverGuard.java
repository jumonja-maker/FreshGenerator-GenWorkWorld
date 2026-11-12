package dev.freshgenerator.cave;

import dev.freshgenerator.core.CaveAccessModel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** One bounded 16x16 cache per generateCarvers invocation, never a global/world cache.
 * Operates on the proposed carving mask, before native aquifer/material safety and before
 * structures/features. It neither edits loaded chunks nor reads neighbouring chunks.
 */
public final class CaveCarverGuard {
    private final ChunkPos origin;
    private final DensitySampler height,roof;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private final float[] heights=new float[256],roofs=new float[256];
    private final boolean[] sampled=new boolean[256];
    public CaveCarverGuard(NoiseGeneratorSettings settings,RandomState state,ChunkPos origin) {
        this.origin=origin;
        height=state.getSampler(settings.debugFunctions().functions().stream()
            .filter(e->e.label().equals(CaveAccessModel.HEIGHT_LABEL)).findFirst().orElseThrow().function());
        roof=state.getSampler(settings.debugFunctions().functions().stream()
            .filter(e->e.label().equals(CaveAccessModel.ROOF_LABEL)).findFirst().orElseThrow().function());
        CaveTelemetry.guard();
    }
    public CarverOutput wrap(CarverOutput original,boolean canyon) {
        return new CarverOutput() {
            @Override public int minY(){return original.minY();}
            @Override public int maxY(){return original.maxY();}
            @Override public void carve(int x,int y,int z) {
                if((x|z)<0||x>=16||z>=16)throw new IllegalArgumentException("Carver supplied non-local column");
                int i=x*16+z;
                if(!sampled[i]) {
                    int wx=origin.getBlockX(x),wz=origin.getBlockZ(z);
                    heights[i]=height.sampleValue(context,wx,0,wz);
                    // This bound is a column invariant; do not recompute it for every block.
                    roofs[i]=Math.max(0,Math.min(CaveAccessModel.ROOF_DEPTH,roof.sampleValue(context,wx,0,wz)));
                    sampled[i]=true;
                }
                boolean keep=CaveAccessModel.permit(heights[i],y,roofs[i]);
                CaveTelemetry.carve(canyon,keep,heights[i]-y);
                if(keep)original.carve(x,y,z);
            }
        };
    }
}
