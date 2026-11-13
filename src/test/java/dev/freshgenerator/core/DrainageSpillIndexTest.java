package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class DrainageSpillIndexTest {
    @SuppressWarnings("unchecked")
    private static List<DrainageBasin> basins(DrainageModel model, int tx, int tz) throws Exception {
        var field = DrainageModel.class.getDeclaredField("basins"); field.setAccessible(true);
        Object cache = field.get(model);
        Method get = cache.getClass().getDeclaredMethod("get", int.class, int.class); get.setAccessible(true);
        return (List<DrainageBasin>) get.invoke(cache, tx, tz);
    }
    private static DrainageModel model(long seed) {
        Field field = (x,z) -> Math.min(83 + (float)Math.hypot(.22 * (x-64), .34 * (z-64)),
                110 - .25f * x + .4f * Math.abs(z-64));
        return new DrainageModel(seed,63,()->new Fields(field,(x,z)->.2f,(x,z)->.5f));
    }
    @Test void immutableSpillIndexEqualsTheOriginalOrderedNeighbourScan() throws Exception {
        Method index = DrainageModel.class.getDeclaredMethod("spillSources", int.class, int.class);
        index.setAccessible(true);
        int edges = 0;
        for(long seed : new long[]{42,1337,-20261111}) {
            var model = model(seed);
            // Includes both sides of grid zero and receiving-grid boundaries.
            for(int z=-16;z<=16;z++) for(int x=-16;x<=16;x++) {
                var reference = new ArrayList<Long>();
                for(int bz=Math.floorDiv(z-DrainageBasin.SEARCH,8);bz<=Math.floorDiv(z+DrainageBasin.SEARCH,8);bz++)
                    for(int bx=Math.floorDiv(x-DrainageBasin.SEARCH,8);bx<=Math.floorDiv(x+DrainageBasin.SEARCH,8);bx++)
                        for(var basin:basins(model,bx,bz)) if(basin.outlet()==key(x,z))reference.add(basin.center().key());
                long[] actual = (long[])index.invoke(model,x,z);
                assertArrayEquals(reference.stream().mapToLong(Long::longValue).toArray(),actual,
                        "Index changed a certified edge or its deterministic order");
                edges += actual.length;
            }
        }
        assertTrue(edges>0,"Fixture must contain real spill edges, not just empty lookups");
    }
    @Test void reverseAndParallelBackwaterStagesUseTheSameCompletedCacheValues() {
        Field bowl = (x,z)->83+(float)Math.hypot(.22*(x-64),.34*(z-64));
        var serial=new DrainageModel(1337,63,()->new Fields(bowl,(x,z)->.2f,(x,z)->.5f));
        var parallel=new DrainageModel(1337,63,()->new Fields(bowl,(x,z)->.2f,(x,z)->.5f));
        float[] expected=new float[1024];
        for(int i=0;i<expected.length;i++)expected[i]=serial.waterLevel(serial.cell(i%32-16,i/32-16));
        java.util.stream.IntStream.range(0,expected.length).parallel().forEach(j->{
            int i=expected.length-1-j;
            assertEquals(expected[i],parallel.waterLevel(parallel.cell(i%32-16,i/32-16)),0);
        });
    }
    @Test void existingOceanIsATerminalStageNotAnInventedReservoir() {
        var model=new DrainageModel(42,63,()->new Fields((x,z)->-20,(x,z)->-.5f,(x,z)->0));
        assertEquals(62,model.waterLevel(model.cell(-12,9)),0);
        assertEquals(DRY,model.tile(-1,1).waterAt(-120,155));
        assertEquals(0,model.tile(-1,1).cutAt(-120,155),0);
    }
}
