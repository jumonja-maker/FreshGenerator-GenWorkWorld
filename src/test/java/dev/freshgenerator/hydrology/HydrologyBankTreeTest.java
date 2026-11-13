package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.DrainageModel;
import net.minecraft.world.level.levelgen.densityfunction.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyBankTreeTest {
    private static DensitySampler field(DrainageModel.Field field) {
        return new DensitySampler() {
            @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
                return field.at(x, z);
            }
            @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
                DensitySampler.sampleVolumeNaive(context, output, volume, this);
            }
        };
    }

    @Test
    void wideTrunkSoilCannotRaiseAnAdjacentDryBank() {
        DrainageModel.Field terrain = (x,z) -> 105 - 0.055f*z + Math.min(5f, 0.18f*Math.abs(x-64));
        var sampler = new DrainageDensity.Sampler(field(terrain),field((x,z)->0.2f),null,63,42,0);
        var context = SamplerContext.builder().enableCaches().build();
        int banks=0, far=0;
        for(int z=16;z<112;z+=4)for(int x=32;x<96;x++){
            int profile=sampler.waterAt(x,z);
            boolean near=sampler.waterAt(x-1,z)!=0||sampler.waterAt(x+1,z)!=0
                    ||sampler.waterAt(x,z-1)!=0||sampler.waterAt(x,z+1)!=0;
            float height=terrain.at(x,z)-sampler.sampleValue(context,x,0,z);
            int floor=(int)Math.ceil(height)-1;
            if(profile==0&&near){
                banks++;
                assertFalse(sampler.wouldRaiseDryBank(x,floor,z));
                assertTrue(sampler.wouldRaiseDryBank(x,floor+1,z));
                assertTrue(sampler.wouldRaiseDryBank(x,floor+3,z));
            }else if(profile==0){
                far++;
                assertFalse(sampler.wouldRaiseDryBank(x,floor+3,z));
            }
        }
        assertTrue(banks>=24,"No real modeled dry-bank coverage");
        assertTrue(far>=24,"No unmodified forest coverage");
    }
}
