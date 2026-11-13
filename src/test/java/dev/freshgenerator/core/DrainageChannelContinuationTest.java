package dev.freshgenerator.core;

import dev.freshgenerator.pack.CoherentSurfaceCompiler;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static dev.freshgenerator.core.DrainageModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduce the observed candidate's dry cutoff, without excusing sheet-flow canals. */
class DrainageChannelContinuationTest {
    private static final long[] SEEDS = {42, 1337, -20261111, 2037157180504249802L, 801086821803359396L};
    private static final Field FLOODPLAIN = (x, z) -> {
        float t = Math.clamp(z / 256f, 0, 1);
        return 130 - 0.02f * z + 0.22f * Math.abs(x - 64) * (1 - t * t * (3 - 2 * t));
    };
    private static DrainageModel model(long seed, Field height) {
        return new DrainageModel(seed, 63, () -> new Fields(height, (x,z) -> .2f, (x,z) -> .3f));
    }

    @Test
    void establishedRiverDoesNotDisappearWhenTheValleyBecomesAFlatFloodplain() {
        for (long seed : SEEDS) {
            var model = model(seed, FLOODPLAIN);
            for (int z = -128; z < 640; z++) {
                int wet = 0;
                for (int x = -128; x < 256; x++)
                    if (model.tile(Math.floorDiv(x, 128), Math.floorDiv(z, 128)).waterAt(x,z) != DRY) wet++;
                assertTrue(wet > 0, "River disappears above sea level: seed=" + seed + ", z=" + z);
                assertTrue(wet < 64, "Continuation must not flood the whole plain");
            }
        }
    }

    @Test
    void establishedRiverReachesNativeSeaAfterHundredsOfEdgesWithoutDistanceCutoff() {
        for (long seed : SEEDS) {
            var model = model(seed, FLOODPLAIN);
            Cell node = model.cell(4,-4);
            int edges = 0;
            while (!node.ocean()) {
                assertTrue(edges++ < 1000, "Test route did not reach sea");
                int w = model.tile(Math.floorDiv(node.x(),128), Math.floorDiv(node.z(),128)).waterAt(node.x(),node.z());
                assertNotEquals(DRY, w, "Lost main stem at edge " + edges + ", seed=" + seed);
                if (node.receiver() == NO_CELL) {
                    // Jittered DEM samples may introduce a shallow minimum even
                    // in this analytic valley. Follow the certified spill edge,
                    // not a fictitious assertion that the raw mesh has no sink.
                    var basin = DrainageBasin.find(model, node, model.discharge(x(node.key()), z(node.key())), FLOODPLAIN);
                    assertNotNull(basin, "Shallow minimum lost its certified route");
                    assertNotEquals(NO_CELL, basin.outlet(), "Open valley cannot terminate in a sampled sink");
                    for (long key : basin.path()) {
                        Cell intermediate = model.cell(key);
                        if (!intermediate.ocean()) assertNotEquals(DRY,
                            model.tile(Math.floorDiv(intermediate.x(),128),Math.floorDiv(intermediate.z(),128))
                                 .waterAt(intermediate.x(),intermediate.z()), "Spill path lost water");
                    }
                    node = model.cell(basin.outlet());
                } else node = model.cell(node.receiver());
            }
            assertTrue(edges > 100, "Test did not exercise a long open floodplain");
        }
    }

    @Test
    void verifiedSpillFollowsNaturalDescentInsteadOfAnEqualCostUphillDetour() {
        Field bowl=(x,z)->83+(float)Math.hypot(.18*(x-64),.32*(z-64));
        Field landscape=(x,z)->Math.min(bowl.at(x,z),110-.25f*x+.4f*Math.abs(z-64));
        var model = new DrainageModel(42,63,()->new Fields(landscape,(x,z)->.2f,(x,z)->.5f));
        var center = model.cell(4,4);
        var basin = DrainageBasin.find(model,center,model.discharge(4,4),landscape);
        assertNotNull(basin);
        assertNotEquals(NO_CELL,basin.outlet());
        float previous=model.cell(basin.path()[basin.saddleIndex()]).height();
        for(int i=basin.saddleIndex()+1;i<basin.path().length;i++) {
            float next=model.cell(basin.path()[i]).height();
            assertTrue(next<previous,"Avoidable uphill detour below verified saddle");
            previous=next;
        }
        assertTrue(previous<center.height()-.55f,"Replacement needs a verified lower receiver");
    }

    @Test
    void completedChannelCertificatesAreIndependentOfOrderAndParallelRequests() throws Exception {
        var forward = model(1337,FLOODPLAIN);
        var reverse = model(1337,FLOODPLAIN);
        var parallel = model(1337,FLOODPLAIN);
        int[][] coordinates = {{-1,2},{0,2},{1,2},{0,3}};
        var expected = new Tile[coordinates.length];
        for (int i=0;i<coordinates.length;i++) expected[i]=forward.tile(coordinates[i][0],coordinates[i][1]);
        for (int i=coordinates.length-1;i>=0;i--) reverse.tile(coordinates[i][0],coordinates[i][1]);
        try (var pool=Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Tile>>();
            for (var coordinate:coordinates) futures.add(pool.submit(() -> parallel.tile(coordinate[0],coordinate[1])));
            for (int i=0;i<coordinates.length;i++) {
                var actual=futures.get(i).get(20,TimeUnit.SECONDS);
                var other=reverse.tile(coordinates[i][0],coordinates[i][1]);
                for (int z=coordinates[i][1]*128;z<coordinates[i][1]*128+128;z++)
                    for (int x=coordinates[i][0]*128;x<coordinates[i][0]*128+128;x++) {
                        assertEquals(expected[i].waterAt(x,z),actual.waterAt(x,z));
                        assertEquals(expected[i].cutAt(x,z),actual.cutAt(x,z),0);
                        assertEquals(expected[i].waterAt(x,z),other.waterAt(x,z));
                        assertEquals(expected[i].cutAt(x,z),other.cutAt(x,z),0);
                    }
            }
        }
    }

    @Test
    void longFeaturelessSlopeHasNoChannelsAndDoesNotRepeatedlyRewalkEvictedTerrain() {
        var calls = new AtomicLong();
        var model = model(1337,(x,z) -> { calls.incrementAndGet(); return 100 - .006f*z; });
        for (int tz=-1;tz<=1;tz++) for(int tx=-1;tx<=1;tx++) {
            var tile=model.tile(tx,tz);
            for(int z=tz*128;z<tz*128+128;z++) for(int x=tx*128;x<tx*128+128;x++)
                assertEquals(DRY,tile.waterAt(x,z), "No ancestor may invent a valley on a planar slope");
        }
        assertTrue(calls.get()<5_000_000, "Unbounded repeat work on a flat slope: " + calls.get());
    }

    @Test
    void channelSearchElevationBoundMatchesThePublishedExteriorInsteadOfALengthBudget() {
        assertEquals(304f,MAX_CHANNEL_ELEVATION);
        for (float raw:new float[]{-1000,-48,63,256,512,1e4f,1e8f,Float.MAX_VALUE})
            assertTrue(CoherentSurfaceCompiler.softHeight(raw)<=MAX_CHANNEL_ELEVATION);
        assertEquals(CoherentSurfaceCompiler.softHeight(Float.MAX_VALUE),MAX_CHANNEL_ELEVATION);
    }
}
