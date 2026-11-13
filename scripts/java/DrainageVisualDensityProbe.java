import dev.freshgenerator.core.DrainageModel;
import static dev.freshgenerator.core.DrainageModel.*;
import java.util.Locale;

/** Identical fixed sampling coordinates for published and experimental river networks. */
public final class DrainageVisualDensityProbe {
    private static final long[] SEEDS = {42, 1337, -20261111, 2037157180504249802L, 801086821803359396L};
    private static final int[][] TILES = {{-2,-2},{-1,-2},{0,-2},{-2,-1},{-1,-1},{0,-1},{-2,0},{-1,0},{0,0},{1,0},{0,1},{1,1}};

    private static Field terrain(String shape) {
        return switch (shape) {
            case "valley" -> (x,z) -> 105 - .055f * z + Math.min(5f, .18f * Math.abs(x - 64));
            case "bowl" -> (x,z) -> 83 + (float) StrictMath.hypot(.22 * (x - 64), .34 * (z - 64));
            case "spill" -> (x,z) -> Math.min(83 + (float) StrictMath.hypot(.18 * (x - 64), .32 * (z - 64)),
                    110 - .25f * x + .4f * Math.abs(z - 64));
            case "rugged" -> (x,z) -> (float)(108 + 18 * StrictMath.sin(x / 77.0) * StrictMath.cos(z / 91.0)
                    + 8 * StrictMath.sin(z / 39.0) + .007 * x);
            case "rolling" -> (x,z) -> (float)(99 + 5 * StrictMath.sin(x / 151.0) + 4 * StrictMath.cos(z / 179.0)
                    + 3 * StrictMath.sin((x + z) / 89.0) - .018 * z);
            case "flat" -> (x,z) -> 100;
            default -> throw new IllegalArgumentException(shape);
        };
    }

    public static void main(String[] args) {
        for (String shape : new String[]{"valley", "bowl", "spill", "rugged", "rolling", "flat"}) {
            for (long seed : SEEDS) {
                Field h = terrain(shape);
                DrainageModel model = new DrainageModel(seed, 63, () -> new Fields(h, (x,z) -> .2f,
                        (x,z) -> (float)(.4 * StrictMath.sin(x / 501.0 + z / 709.0))));
                int streams = 0, lakes = 0, cut = 0, raised = 0, samples = 0;
                for (int[] t : TILES) {
                    Tile tile = model.tile(t[0], t[1]);
                    for (int z = t[1] * TILE_SIZE; z < (t[1] + 1) * TILE_SIZE; z += 4) {
                        for (int x = t[0] * TILE_SIZE; x < (t[0] + 1) * TILE_SIZE; x += 4) {
                            int profile = tile.waterAt(x, z);
                            if (profile != DRY) {
                                if (isLake(profile)) lakes++; else streams++;
                            }
                            float change = tile.cutAt(x, z);
                            if (change > 0.05f) cut++;
                            if (change < -0.05f) raised++;
                            samples++;
                        }
                    }
                }
                System.out.printf(Locale.ROOT,
                        "{\"shape\":\"%s\",\"seed\":\"%d\",\"samples\":%d,\"river\":%d,\"lake\":%d,\"cut\":%d,\"raised\":%d}%n",
                        shape, seed, samples, streams, lakes, cut, raised);
            }
        }
    }
}
