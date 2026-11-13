package dev.freshgenerator.core;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/** Seed-local drainage on the original, unblended elevation model. */
public final class DrainageModel {
    public static final int TILE_SIZE = 128;
    public static final float MAX_CUT = 18;
    public static final float MAX_BANK_RAISE = 2.25f;
    public static final int DRY = 0;
    public static final int SPACING = 16;
    public static final int MAX_FLOW = 1024;
    // Discharge is weighted catchment area, not a river count. A larger source
    // threshold alone cannot suppress the parallel channels of a planar slope;
    // cross-valley confinement below is a separate geomorphic requirement.
    public static final int MIN_FLOW = 64;
    static final long NO_CELL = Long.MIN_VALUE;
    private static final int CELLS = TILE_SIZE / SPACING;
    // Shared edges include a one-block apron for the dry banks.
    private static final int SIDE = TILE_SIZE + 3;
    private static final int REACH_MARGIN = 3;
    // The published coherent exterior approaches 304 from below. This is an
    // elevation-domain boundary, not a traversal-distance or catchment cutoff.
    static final float MAX_CHANNEL_ELEVATION = 304;
    private static final int NO_CHANNEL = 1, CHANNEL = 2;

    private final long seed;
    private final int sea;
    private final Supplier<Fields> fields;
    private final Tiles<Grid> grids = new Tiles<>(512, this::buildGrid);
    // Completed channel certificates are much smaller than sampled terrain.
    // Keep at most 4096 x 64 integer decisions (~1 MiB of primitive payload),
    // separate from the unchanged terrain cache. This avoids rewalking a long
    // featureless slope whenever a terrain tile is evicted.
    private final Tiles<AtomicIntegerArray> channels = new Tiles<>(4096,
            (x, z) -> new AtomicIntegerArray(CELLS * CELLS));
    private final Tiles<List<DrainageBasin>> basins = new Tiles<>(256, this::buildBasins);
    // Invert the certified spill edges once per receiving grid. Previously each
    // visited upstream node rescanned up to nine basin grids in both flow and
    // channel searches. This immutable index changes neither edges nor order.
    private final Tiles<long[][]> spillSources = new Tiles<>(512, this::buildSpillSources);
    private static final long[] NO_SPILLS = new long[0];
    private final Tiles<Tile> rasters = new Tiles<>(128, this::buildTile);

    @FunctionalInterface
    public interface Field {
        float at(int x, int z);
    }

    public record Fields(Field terrain, Field continents, Field moisture) { }

    public DrainageModel(long seed, int sea, Supplier<Fields> fields) {
        this.seed = seed;
        this.sea = sea;
        this.fields = fields;
    }

    public Tile tile(int tileX, int tileZ) {
        return rasters.get(tileX, tileZ);
    }

    public static int waterTop(int profile) { return (short) (profile >>> 16); }
    public static int floor(int profile) { return (profile << 16) >> 18; }
    public static boolean isLake(int profile) { return (profile & 1) != 0; }
    public static boolean hasSpillway(int profile) { return (profile & 2) != 0; }

    public int discharge(int gridX, int gridZ) {
        return flow(key(gridX, gridZ), new long[MAX_FLOW], true);
    }

    public static final class Tile {
        private final int x0;
        private final int z0;
        private final float[] cut;
        private final int[] water;

        private Tile(int x0, int z0, float[] cut, int[] water) {
            this.x0 = x0;
            this.z0 = z0;
            this.cut = cut;
            this.water = water;
        }

        public float cutAt(int x, int z) { return cut[(z - z0) * SIDE + x - x0]; }
        public int waterAt(int x, int z) { return water[(z - z0) * SIDE + x - x0]; }
    }

    record Cell(long key, int x, int z, float height, int runoff, boolean ocean, long receiver) { }

    Cell cell(int gx, int gz) {
        return grid(gx, gz).cell(gx, gz);
    }

    Cell cell(long key) { return cell(x(key), z(key)); }

    int sea() { return sea; }

    float riverLevel(Cell cell) {
        return Math.max(sea - 1, cell.height - 2.0f);
    }

    /** The water receiving a tributary is a boundary condition, not h - 2.
     * A lake's stage can exceed the raw river estimate several blocks upstream.
     * Follow the strictly descending receiver graph to that boundary, then carry
     * its stage upstream until the natural profile rises above it. The terrain,
     * lake footprint and spill saddle are not raised or enlarged by this lookup.
     * Completed float bits only are cached; traversal never samples a neighbour
     * raster, so tile order, concurrent requests and cache eviction are harmless.
     */
    float waterLevel(Cell start) {
        Grid origin = grid(x(start.key), z(start.key));
        int originSlot = origin.index(x(start.key), z(start.key));
        int ready = origin.stage.get(originSlot);
        if (ready != 0) return Float.intBitsToFloat(ready);
        float originalStage = riverLevel(start);
        var path = new ArrayList<Cell>();
        Cell current = start;
        float level;
        while (true) {
            // Every following receiver is strictly lower. Even a maximally
            // filled downstream lake cannot affect this start once its upper
            // bound falls below the natural start stage. This is an elevation
            // proof, NOT a fixed-distance truncation. Cache ONLY the start:
            // intermediate nodes can still have a different downstream stage.
            if (DrainageBasin.maximumStage(current.height, sea) <= originalStage) {
                origin.stage.compareAndSet(originSlot, 0, Float.floatToIntBits(originalStage));
                return originalStage;
            }
            Grid owner = grid(x(current.key), z(current.key));
            int known = owner.stage.get(owner.index(x(current.key), z(current.key)));
            if (known != 0) {
                level = Float.intBitsToFloat(known);
                break;
            }
            path.add(current);
            if (current.ocean) {
                level = sea - 1;
                break;
            }
            long receiver = current.receiver;
            if (receiver == NO_CELL) {
                DrainageBasin basin = null;
                for (DrainageBasin candidate : basins.get(Math.floorDiv(x(current.key), CELLS),
                        Math.floorDiv(z(current.key), CELLS))) {
                    if (candidate.center().key == current.key) { basin = candidate; break; }
                }
                if (basin == null || !basin.footprint().isEmpty() || basin.outlet() == NO_CELL) {
                    level = basin != null && !basin.footprint().isEmpty()
                            ? basin.top() : riverLevel(current);
                    break;
                }
                receiver = basin.outlet();
            }
            Cell next = cell(receiver);
            if (!(next.height < current.height))
                throw new IllegalStateException("Water-stage receiver must lose elevation");
            current = next;
        }
        for (int i = path.size() - 1; i >= 0; i--) {
            Cell node = path.get(i);
            level = Math.max(level, riverLevel(node));
            Grid owner = grid(x(node.key), z(node.key));
            owner.stage.compareAndSet(owner.index(x(node.key), z(node.key)), 0, Float.floatToIntBits(level));
        }
        return level;
    }

    private Grid grid(int gx, int gz) {
        return grids.get(Math.floorDiv(gx, CELLS), Math.floorDiv(gz, CELLS));
    }

    private Grid buildGrid(int tx, int tz) {
        Fields samples = fields.get();
        int gx0 = tx * CELLS, gz0 = tz * CELLS;
        int side = CELLS + 2;
        int[] x = new int[side * side];
        int[] z = new int[x.length];
        float[] h = new float[x.length];
        for (int iz = 0; iz < side; iz++) {
            for (int ix = 0; ix < side; ix++) {
                int gx = gx0 + ix - 1, gz = gz0 + iz - 1;
                int i = iz * side + ix;
                long hash = mix(seed + gx * 0x9E3779B97F4A7C15L + gz * 0xC2B2AE3D27D4EB4FL);
                x[i] = gx * SPACING + (int) (hash & 7) - 3;
                z[i] = gz * SPACING + (int) ((hash >>> 8) & 7) - 3;
                h[i] = samples.terrain.at(x[i], z[i]);
            }
        }
        Cell[] cells = new Cell[CELLS * CELLS];
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                int i = (iz + 1) * side + ix + 1;
                float continentalness = samples.continents.at(x[i], z[i]);
                // Existing sea-level water is a terminal receiver, including inland
                // reaches of the native sea-level network. Never incise/embank it
                // again just because continentalness calls this column "land".
                boolean ocean = h[i] <= sea - 1 || continentalness <= -0.24f
                        || (continentalness < -0.16f && h[i] <= sea);
                int runoff = 1 + Math.round(3 * Math.clamp(0.5f + samples.moisture.at(x[i], z[i]), 0, 1));
                long receiver = NO_CELL;
                float steepest = 0;
                if (!ocean) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = i + dz * side + dx;
                            float drop = h[i] - h[j];
                            if (drop <= 0.015f) continue;
                            float slope = drop * drop / distance2(x[j] - x[i], z[j] - z[i]);
                            if (slope > steepest) {
                                receiver = key(gx0 + ix + dx, gz0 + iz + dz);
                                steepest = slope;
                            }
                        }
                    }
                }
                long key = key(gx0 + ix, gz0 + iz);
                cells[iz * CELLS + ix] = new Cell(key, x[i], z[i], h[i], runoff, ocean, receiver);
            }
        }
        return new Grid(gx0, gz0, cells);
    }

    private List<DrainageBasin> buildBasins(int tx, int tz) {
        List<DrainageBasin> result = new ArrayList<>();
        long[] pending = new long[MAX_FLOW];
        Fields samples = fields.get();
        for (Cell center : grids.get(tx, tz).cells) {
            if (center.ocean || center.receiver != NO_CELL || center.height < sea - 6) continue;
            int raw = flow(center.key, pending, false);
            // A short, sampled valley can have a sub-grid minimum before it
            // accumulates enough water to become a channel. Route that minimum
            // first; filtering it at MIN_FLOW would lose all distant tributaries.
            if (raw <= center.runoff) continue;
            DrainageBasin basin = DrainageBasin.find(this, center, raw, samples.terrain);
            if (basin != null) result.add(basin);
        }
        return List.copyOf(result);
    }

    private long[][] buildSpillSources(int tx, int tz) {
        int gx0 = tx * CELLS, gz0 = tz * CELLS;
        long[][] incoming = new long[CELLS * CELLS][];
        int radius = DrainageBasin.SEARCH;
        for (int bz = Math.floorDiv(gz0 - radius, CELLS); bz <= Math.floorDiv(gz0 + CELLS - 1 + radius, CELLS); bz++) {
            for (int bx = Math.floorDiv(gx0 - radius, CELLS); bx <= Math.floorDiv(gx0 + CELLS - 1 + radius, CELLS); bx++) {
                for (DrainageBasin basin : basins.get(bx, bz)) {
                    long outlet = basin.outlet();
                    if (outlet == NO_CELL) continue;
                    int x = x(outlet) - gx0, z = z(outlet) - gz0;
                    if (x < 0 || x >= CELLS || z < 0 || z >= CELLS) continue;
                    int index = z * CELLS + x;
                    long[] old = incoming[index];
                    long[] values = old == null ? new long[1] : Arrays.copyOf(old, old.length + 1);
                    values[values.length - 1] = basin.center().key;
                    incoming[index] = values;
                }
            }
        }
        return incoming;
    }

    private long[] spillSources(int gx, int gz) {
        long[] result = spillSources.get(Math.floorDiv(gx, CELLS), Math.floorDiv(gz, CELLS))
                [Math.floorMod(gz, CELLS) * CELLS + Math.floorMod(gx, CELLS)];
        return result == null ? NO_SPILLS : result;
    }

    /**
     * Reverse traversal of a single-receiver DAG. Every visited cell contributes at
     * least one unit, so discovering MAX_FLOW distinct ancestors is sufficient even
     * for an infinite upslope area. Below that cap the entire contributing area is visited.
     * A spill edge skips its lake's saddle and ends strictly below its original sink.
     */
    private int flow(long start, long[] pending, boolean includeSpills) {
        int gx = x(start), gz = z(start);
        Grid origin = grid(gx, gz);
        int slot = origin.index(gx, gz);
        AtomicIntegerArray memo = includeSpills ? origin.flow : origin.rawFlow;
        int saved = memo.get(slot);
        if (saved != 0) return saved;

        // Natural upstream flow is a lower bound on flow including lake spillways.
        // Once that bound reaches MAX_FLOW no basin search can change the result.
        // The same scratch queue is safe here: the raw traversal does not recurse
        // into basins, and the queue is reset before the full traversal below.
        if (includeSpills && flow(start, pending, false) == MAX_FLOW) {
            memo.compareAndSet(slot, 0, MAX_FLOW);
            return MAX_FLOW;
        }

        pending[0] = start;
        int count = 1;
        int total = 0;
        for (int read = 0; read < count && total < MAX_FLOW; read++) {
            long current = pending[read];
            int cx = x(current), cz = z(current);
            Grid local = grid(cx, cz);
            int index = local.index(cx, cz);
            AtomicIntegerArray partial = includeSpills ? local.flow : local.rawFlow;
            int known = current == start ? 0 : partial.get(index);
            if (known != 0) {
                total += known;
                continue;
            }
            Cell cell = local.cells[index];
            total += cell.runoff;
            for (int dz = -1; dz <= 1 && count < MAX_FLOW; dz++) {
                for (int dx = -1; dx <= 1 && count < MAX_FLOW; dx++) {
                    int nx = cx + dx, nz = cz + dz;
                    Cell other = local.contains(nx, nz) ? local.cell(nx, nz) : cell(nx, nz);
                    if (other.receiver == current) pending[count++] = other.key;
                }
            }
            if (includeSpills && count < MAX_FLOW && total < MAX_FLOW && !cell.ocean) {
                for (long source : spillSources(cx, cz)) {
                    if (count == MAX_FLOW) break;
                    pending[count++] = source;
                }
            }
            if (count == MAX_FLOW) total = MAX_FLOW;
        }
        total = Math.min(MAX_FLOW, total);
        memo.compareAndSet(slot, 0, total);
        return total;
    }

    private Tile buildTile(int tx, int tz) {
        Fields samples = fields.get();
        Raster raster = new Raster(tx * TILE_SIZE - 1, tz * TILE_SIZE - 1, samples.terrain, sea);
        Map<Long, Direction> tangents = new HashMap<>();
        int gx0 = tx * CELLS, gz0 = tz * CELLS;
        long[] pending = new long[MAX_FLOW];
        for (int gz = gz0 - REACH_MARGIN; gz <= gz0 + CELLS + REACH_MARGIN; gz++) {
            for (int gx = gx0 - REACH_MARGIN; gx <= gx0 + CELLS + REACH_MARGIN; gx++) {
                Cell start = cell(gx, gz);
                if (start.receiver == NO_CELL || !channel(start, samples.terrain, pending)) continue;
                int flow = flow(start.key, pending, true);
                Cell end = cell(start.receiver);
                int nextFlow = end.ocean ? flow : flow(end.key, pending, true);
                Direction ta = tangents.computeIfAbsent(start.key, k -> tangent(start, samples.terrain, pending));
                Direction tb = end.receiver == NO_CELL ? Direction.between(start, end)
                        : tangents.computeIfAbsent(end.key, k -> tangent(end, samples.terrain, pending));
                raster.reach(start, end, waterLevel(start), waterLevel(end), flow, nextFlow,
                        Curve.between(start, end, ta, tb), 1, 1);
            }
        }
        int extent = DrainageBasin.SEARCH + REACH_MARGIN + 1;
        for (int bz = Math.floorDiv(gz0 - extent, CELLS); bz <= Math.floorDiv(gz0 + CELLS + extent, CELLS); bz++) {
            for (int bx = Math.floorDiv(gx0 - extent, CELLS); bx <= Math.floorDiv(gx0 + CELLS + extent, CELLS); bx++) {
                for (DrainageBasin basin : basins.get(bx, bz)) {
                    if (!basin.isNear(raster.x0, raster.z0, TILE_SIZE + SPACING)) continue;
                    if (basin.outlet() != NO_CELL) {
                        int flow = flow(basin.center().key, pending, true);
                        long[] path = basin.path();
                        float level = basin.top();
                        float endLevel = waterLevel(cell(basin.outlet()));
                        for (int i = 1; i < path.length; i++) {
                            Cell a = cell(path[i - 1]), b = cell(path[i]);
                            float next = i <= basin.saddleIndex() ? level
                                    : Math.min(level, Math.max(endLevel, riverLevel(b)));
                            raster.reach(a, b, level, next, flow, flow);
                            level = next;
                        }
                    }
                    basin.paint(raster);
                }
            }
        }
        return raster.finish();
    }

    /** Confinement initiates a river; it cannot erase an existing upstream river.
     * Compute the monotone OR of eligible headwaters over the same single-receiver
     * DAG as discharge. No nine-step (or other distance) cutoff is used. Positive
     * discharge and the strictly increasing upstream elevation make the search
     * finite in the coherent exterior's bounded elevation domain. A stack, not
     * Java recursion, handles long paths. Only completed decisions enter the
     * bounded grid cache, so eviction/order/concurrency cannot change the answer.
     */
    private boolean channel(Cell start, Field terrain, long[] flowQueue) {
        ChannelFrame root = new ChannelFrame(start);
        int initial = channelDecision(root, terrain, flowQueue);
        if (initial != 0) return initial == CHANNEL;
        var stack = new ArrayDeque<ChannelFrame>();
        stack.push(root);
        while (!stack.isEmpty()) {
            ChannelFrame frame = stack.peek();
            Cell upstream = frame.nextUpstream();
            if (upstream == null) {
                frame.complete(NO_CHANNEL);
                stack.pop();
                continue;
            }
            if (upstream.height <= frame.node.height) {
                throw new IllegalStateException("A channel's upstream edge must gain elevation");
            }
            ChannelFrame next = new ChannelFrame(upstream);
            int decision = channelDecision(next, terrain, flowQueue);
            if (decision == CHANNEL) {
                for (ChannelFrame ancestor : stack) ancestor.complete(CHANNEL);
                return true;
            }
            if (decision == 0) stack.push(next);
        }
        return false;
    }

    private int channelDecision(ChannelFrame frame, Field terrain, long[] pending) {
        int known = frame.owner.get(frame.slot);
        if (known != 0) return known;
        Cell node = frame.node;
        if (node.ocean || node.height >= MAX_CHANNEL_ELEVATION
                || flow(node.key, pending, true) < MIN_FLOW) return frame.complete(NO_CHANNEL);
        if (node.receiver != NO_CELL && confinement(node, terrain) > 0.08f)
            return frame.complete(CHANNEL);
        if (node.receiver == NO_CELL) {
            // A real throughflow lake is also a source. A dry sub-grid bypass
            // is not: it must inherit an actual upstream channel instead.
            for (DrainageBasin basin : basins.get(Math.floorDiv(x(node.key), CELLS),
                    Math.floorDiv(z(node.key), CELLS))) {
                if (basin.center().key == node.key && basin.outlet() != NO_CELL && !basin.footprint().isEmpty())
                    return frame.complete(CHANNEL);
            }
        }
        return 0;
    }

    private final class ChannelFrame {
        final Cell node;
        final AtomicIntegerArray owner;
        final int slot;
        private int neighbour;
        private long[] spills;
        private int spill;
        ChannelFrame(Cell node) {
            this.node = node;
            int gx = x(node.key), gz = z(node.key);
            owner = channels.get(Math.floorDiv(gx, CELLS), Math.floorDiv(gz, CELLS));
            slot = Math.floorMod(gz, CELLS) * CELLS + Math.floorMod(gx, CELLS);
        }
        int complete(int decision) {
            owner.compareAndSet(slot, 0, decision);
            return decision;
        }
        Cell nextUpstream() {
            int gx = x(node.key), gz = z(node.key);
            while (neighbour < 9) {
                int i = neighbour++;
                if (i == 4) continue;
                Cell other = cell(gx + i % 3 - 1, gz + i / 3 - 1);
                if (other.receiver == node.key) return other;
            }
            if (spills == null) spills = spillSources(gx, gz);
            return spill < spills.length ? cell(spills[spill++]) : null;
        }
    }

    /** Both sides must rise; a tilted plane is not a valley. The two scales
     * distinguish a confined drainage corridor from arbitrary D8 sheet-flow lines.
     * No randomness or tile-relative coordinates enter this decision. */
    private float confinement(Cell node, Field terrain) {
        if (node.receiver == NO_CELL || node.ocean) return 0;
        float relief = 0;
        // Receiver directions can zigzag across a valley floor. Measuring only
        // normal to that one edge incorrectly calls alternate floor nodes dry.
        for (int radius : new int[]{2 * SPACING, 4 * SPACING}) {
            int diagonal = Math.round(radius * 0.70710677f);
            for (int[] d : new int[][]{{radius, 0}, {0, radius}, {diagonal, diagonal}, {diagonal, -diagonal}}) {
                float left = terrain.at(node.x + d[0], node.z + d[1]);
                float right = terrain.at(node.x - d[0], node.z - d[1]);
                relief = Math.max(relief, Math.min(left, right) - node.height);
            }
        }
        return smooth(0.35f, 1.75f, relief);
    }

    /** A shared main-stem tangent at a graph node, rather than independently
     * bending every 16-block link. Tributaries join the same downstream point. */
    private Direction tangent(Cell node, Field terrain, long[] pending) {
        if (node.receiver == NO_CELL) return new Direction(1, 0);
        Cell next = cell(node.receiver);
        Cell upstream = null;
        int largest = 0;
        int gx = x(node.key), gz = z(node.key);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx | dz) == 0) continue;
                Cell other = cell(gx + dx, gz + dz);
                if (other.receiver != node.key || !channel(other, terrain, pending)) continue;
                int q = flow(other.key, pending, false);
                if (q > largest || (q == largest && upstream != null && other.key < upstream.key)) {
                    largest = q;
                    upstream = other;
                }
            }
        }
        Direction out = Direction.between(node, next);
        if (upstream == null) return out;
        Direction in = Direction.between(upstream, node);
        return Direction.unit(in.x + out.x, in.z + out.z, out);
    }

    record Direction(float x, float z) {
        static Direction between(Cell a, Cell b) {
            return unit(b.x - a.x, b.z - a.z, new Direction(1, 0));
        }
        static Direction unit(float x, float z, Direction fallback) {
            float length = (float) Math.sqrt(distance2(x, z));
            return length < 0.0001f ? fallback : new Direction(x / length, z / length);
        }
    }

    /** Short cubic links with graph-shared tangents. Arc-length coordinates
     * control width/stage, so curvature cannot speed up a local water drop. */
    static final class Curve {
        static final int SEGMENTS = 12;
        final float[] x = new float[SEGMENTS + 1];
        final float[] z = new float[SEGMENTS + 1];
        final float[] distance = new float[SEGMENTS + 1];
        final float[] inverseLength2 = new float[SEGMENTS];
        final float length;

        private Curve(Cell a, Cell b, Direction start, Direction end) {
            float dx = b.x - a.x, dz = b.z - a.z;
            float chord = (float) Math.sqrt(distance2(dx, dz));
            Direction line = Direction.between(a, b);
            // A tributary arriving at an obtuse angle must not loop upstream.
            if (start.x * line.x + start.z * line.z < 0.1f) start = line;
            if (end.x * line.x + end.z * line.z < 0.1f) end = line;
            float handle = chord / 3;
            float ax = handle * start.x, az = handle * start.z;
            float bx = dx - handle * end.x, bz = dz - handle * end.z;
            for (int i = 0; i <= SEGMENTS; i++) {
                float t = (float) i / SEGMENTS, u = 1 - t;
                x[i] = 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * dx;
                z[i] = 3 * u * u * t * az + 3 * u * t * t * bz + t * t * t * dz;
                if (i > 0) {
                    float d2 = distance2(x[i] - x[i - 1], z[i] - z[i - 1]);
                    inverseLength2[i - 1] = 1 / d2;
                    distance[i] = distance[i - 1] + (float) Math.sqrt(d2);
                }
            }
            length = distance[SEGMENTS];
        }
        static Curve between(Cell a, Cell b, Direction start, Direction end) {
            return new Curve(a, b, start, end);
        }
    }

    static final class Raster {
        private final int x0, z0;
        private final Field terrain;
        private final int sea;
        private final float[] height = new float[SIDE * SIDE];
        private final float[] cut = new float[height.length];
        private final int[] water = new int[height.length];

        Raster(int x0, int z0, Field terrain, int sea) {
            this.x0 = x0;
            this.z0 = z0;
            this.terrain = terrain;
            this.sea = sea;
            Arrays.fill(height, Float.NaN);
        }

        Tile finish() {
            // Quantized stages must share at least one wet voxel across an edge.
            // A one-block-deep lake lip otherwise becomes a solid dam when the
            // next river column drops one block. Only floors change in this pass:
            // all decisions read immutable stages, so traversal order cannot leak.
            for (int z = 1; z < SIDE - 1; z++) {
                for (int x = 1; x < SIDE - 1; x++) {
                    int i = z * SIDE + x;
                    if (water[i] == DRY) continue;
                    int floor = floor(water[i]);
                    int target = floor;
                    for (int neighbour : new int[]{i - 1, i + 1, i - SIDE, i + SIDE}) {
                        if (water[neighbour] != DRY)
                            target = Math.min(target, waterTop(water[neighbour]) - 1);
                    }
                    if (target == floor) continue;
                    float h = original(i, x0 + x, z0 + z);
                    float proposed = Math.max(cut[i], h - (target + 0.5f));
                    if (proposed > MAX_CUT) continue;
                    cut[i] = proposed;
                    water[i] = (water[i] & 0xffff0003) | ((target & 0x3fff) << 2);
                }
            }
            for (int z = 1; z < SIDE - 1; z++) {
                for (int x = 1; x < SIDE - 1; x++) {
                    int i = z * SIDE + x;
                    if (water[i] != DRY) continue;
                    int top = Math.max(Math.max(bankStage(water[i - 1]), bankStage(water[i + 1])),
                            Math.max(bankStage(water[i - SIDE]), bankStage(water[i + SIDE])));
                    if (top == Integer.MIN_VALUE) continue;
                    float h = original(i, x0 + x, z0 + z);
                    // The native sea already contains water. A dry MODEL column
                    // does not imply dry land: raising it creates artificial dikes.
                    if (h < sea) continue;
                    // Keep the uncut rim and seal shallow natural cave holes beside it.
                    // Low neighboring banks may rise by at most two and a quarter blocks.
                    if (h >= top + 0.55f) {
                        cut[i] = Math.clamp(cut[i], 0.25f, Math.max(0.25f, h - top - 0.30f));
                    } else if (h >= top + 0.55f - MAX_BANK_RAISE) {
                        cut[i] = Math.clamp(h - (top + 0.55f), -MAX_BANK_RAISE, -0.25f);
                    }
                }
            }
            return new Tile(x0, z0, cut, water);
        }

        private static int bankStage(int profile) {
            return profile == DRY ? Integer.MIN_VALUE : waterTop(profile);
        }

        void lakeColumn(int x, int z, int top, boolean spilling) {
            if (x < x0 || x >= x0 + SIDE || z < z0 || z >= z0 + SIDE) return;
            int i = (z - z0) * SIDE + x - x0;
            wet(i, original(i, x, z), top, top - 0.2f, spilling ? 3 : 1);
        }

        private float original(int i, int x, int z) {
            float h = height[i];
            if (Float.isNaN(h)) height[i] = h = terrain.at(x, z);
            return h;
        }

        private void wet(int i, float h, int top, float target, int flags) {
            boolean lake = (flags & 1) != 0;
            if (h <= sea - 1 && (!lake || top <= sea - 1)) return;
            float proposed = Math.max(0.25f, h - target);
            if (proposed > MAX_CUT) return;
            int floor = (int) Math.ceil(h - proposed) - 1;
            if (floor >= top) return;
            int old = water[i];
            if (old != DRY && (isLake(old) && !lake
                    || isLake(old) == lake && (waterTop(old) < top || (waterTop(old) == top && cut[i] >= proposed)))) return;
            water[i] = (top << 16) | ((floor & 0x3fff) << 2) | flags;
            cut[i] = proposed;
        }

        void reach(Cell a, Cell b, float upstreamY, float downstreamY, int upstreamQ, int downstreamQ) {
            Direction line = Direction.between(a, b);
            reach(a, b, upstreamY, downstreamY, upstreamQ, downstreamQ,
                    Curve.between(a, b, line, line), 1, 1);
        }

        void reach(Cell a, Cell b, float upstreamY, float downstreamY, int upstreamQ, int downstreamQ,
                   Curve curve, float upstreamConfinement, float downstreamConfinement) {
            if (upstreamQ < MIN_FLOW) return;
            float grade = Math.max(0, upstreamY - downstreamY) / curve.length;
            // An incoming reach carries its own discharge up to the confluence.
            // Interpolating to the receiver's TOTAL flow widens a tiny tributary
            // into the trunk before the other tributaries have actually joined.
            float maxWidth = width(upstreamQ, grade);
            // Match the receiver stage at the expanded bank, not only at the
            // abstract graph-node centre. Equal-width reaches keep their original
            // longitudinal profile; only the additional receiving width advances
            // the confluence. This does not create or remove any drainage edge.
            float confluence = Math.max(0, width(downstreamQ, grade) - maxWidth);
            float stageLength = Math.max(1, curve.length - confluence);
            // Every evaluated line segment lies inside its sampled-vertex AABB.
            // Outside the AABB expanded by the maximum 1.8-radius bank footprint,
            // the old nearest-segment test could not write anything. Keep local
            // coordinates until integer addition (also exact near world borders).
            float lowX = curve.x[0], highX = lowX, lowZ = curve.z[0], highZ = lowZ;
            for (int s = 1; s <= Curve.SEGMENTS; s++) {
                lowX = Math.min(lowX, curve.x[s]); highX = Math.max(highX, curve.x[s]);
                lowZ = Math.min(lowZ, curve.z[s]); highZ = Math.max(highZ, curve.z[s]);
            }
            int margin = (int) Math.ceil(Math.sqrt(3.24f) * maxWidth) + 1;
            int minX = Math.max(x0, a.x + (int) Math.floor(lowX) - margin);
            int maxX = Math.min(x0 + SIDE - 1, a.x + (int) Math.ceil(highX) + margin);
            int minZ = Math.max(z0, a.z + (int) Math.floor(lowZ) - margin);
            int maxZ = Math.min(z0 + SIDE - 1, a.z + (int) Math.ceil(highZ) + margin);
            if (minX > maxX || minZ > maxZ) return;
            float flow = upstreamQ;
            float depth = Math.min(4.5f, 0.8f + 0.075f * (float) Math.sqrt(flow) + 0.6f * grade);
            boolean uniformConfinement = upstreamConfinement == downstreamConfinement;
            float fixedConfinement = smooth(0.08f, 0.65f, upstreamConfinement);
            float fixedWidth = Math.max(0.72f, maxWidth * fixedConfinement);

            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    float best = Float.POSITIVE_INFINITY, t = 0;
                    for (int s = 0; s < Curve.SEGMENTS; s++) {
                        float sx = curve.x[s + 1] - curve.x[s], sz = curve.z[s + 1] - curve.z[s];
                        float px = x - a.x - curve.x[s], pz = z - a.z - curve.z[s];
                        float projection = Math.clamp((px * sx + pz * sz) * curve.inverseLength2[s], 0, 1);
                        float d2 = distance2(px - projection * sx, pz - projection * sz);
                        if (d2 < best) {
                            best = d2;
                            t = lerp(projection, curve.distance[s], curve.distance[s + 1]) / curve.length;
                        }
                    }
                    float confined = uniformConfinement ? fixedConfinement
                            : smooth(0.08f, 0.65f, lerp(t, upstreamConfinement, downstreamConfinement));
                    if (confined == 0) continue;
                    // Keep the voxelized centerline four-connected even for
                    // narrow sources and diagonal links (sqrt(1/2) < 0.72).
                    float width = uniformConfinement ? fixedWidth : Math.max(0.72f, maxWidth * confined);
                    if (best > 3.24f * width * width) continue;
                    float r = (float) Math.sqrt(best) / width;
                    float level = lerp(Math.min(1, t * curve.length / stageLength), upstreamY, downstreamY);
                    int top = (int) Math.floor(level + 0.0001f);
                    int i = (z - z0) * SIDE + x - x0;
                    float h = original(i, x, z);
                    if (a.ocean || b.ocean && h < downstreamY - 3) continue;
                    if (r <= 1 || (r <= 1.18f && h < top + 0.35f)) {
                        wet(i, h, top, top - Math.max(0.25f, depth * (1 - r * r * 0.88f)), 0);
                    } else if (water[i] == DRY) {
                        float bank = top + 1 + (r - 1) * Math.max(3, 0.8f * width);
                        float amount = Math.max(0, h - bank) * (1 - smooth(1, 1.8f, r));
                        cut[i] = Math.max(cut[i], Math.min(MAX_CUT, amount));
                    }
                }
            }
        }

        static float width(float flow, float grade) {
            // A just-initiated stream must not begin as an eight-block canal.
            // The monotone discharge taper also avoids a blunt full-width end.
            float onset = smooth(MIN_FLOW, MIN_FLOW + 48, flow);
            float radius = 0.9f + 0.145f * (float) Math.sqrt(Math.max(0, flow - MIN_FLOW));
            return Math.max(0.72f, (0.72f + (radius - 0.72f) * onset) / (1 + 2.6f * grade));
        }
    }

    private static final class Grid {
        private final int x0, z0;
        private final Cell[] cells;
        private final AtomicIntegerArray stage = new AtomicIntegerArray(CELLS * CELLS);
        private final AtomicIntegerArray rawFlow = new AtomicIntegerArray(CELLS * CELLS);
        private final AtomicIntegerArray flow = new AtomicIntegerArray(CELLS * CELLS);

        Grid(int x0, int z0, Cell[] cells) { this.x0 = x0; this.z0 = z0; this.cells = cells; }

        int index(int x, int z) { return (z - z0) * CELLS + x - x0; }
        Cell cell(int x, int z) { return cells[index(x, z)]; }
        boolean contains(int x, int z) { return x >= x0 && x < x0 + CELLS && z >= z0 && z < z0 + CELLS; }
    }

    @FunctionalInterface
    private interface TileFactory<T> { T build(int x, int z); }

    private static final class Tiles<T> {
        private record Entry<T>(long key, T value) { }

        private static final int WAYS = 4;
        private final AtomicReferenceArray<Entry<T>> slots;
        private final int[] nextVictim;
        private final Object[] locks = new Object[32];
        private final TileFactory<T> factory;

        Tiles(int size, TileFactory<T> factory) {
            if (size < WAYS || (size & (size - 1)) != 0) {
                throw new IllegalArgumentException("Cache capacity must be a power of two >= " + WAYS);
            }
            slots = new AtomicReferenceArray<>(size);
            nextVictim = new int[size / WAYS];
            Arrays.setAll(locks, i -> new Object());
            this.factory = factory;
        }

        private T find(long key, int first) {
            for (int way = 0; way < WAYS; way++) {
                Entry<T> entry = slots.get(first + way);
                if (entry != null && entry.key == key) return entry.value;
            }
            return null;
        }

        T get(int x, int z) {
            long key = key(x, z);
            int set = (int) mix(key) & (nextVictim.length - 1);
            int first = set * WAYS;
            T current = find(key, first);
            if (current != null) return current;
            // Four ways prevent nearby colliding tiles repeatedly evicting one
            // another. Total entry capacity and raster -> basin -> grid lock order
            // are unchanged. Eviction affects cost, never the calculated geometry.
            synchronized (locks[set & (locks.length - 1)]) {
                current = find(key, first);
                if (current != null) return current;
                T value = factory.build(x, z);
                int victim = nextVictim[set];
                slots.set(first + victim, new Entry<>(key, value));
                nextVictim[set] = (victim + 1) & (WAYS - 1);
                return value;
            }
        }
    }

    static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    static int x(long key) { return (int) (key >> 32); }
    static int z(long key) { return (int) key; }

    private static float distance2(float x, float z) { return x * x + z * z; }

    private static float smooth(float a, float b, float value) {
        float t = Math.clamp((value - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float t, float a, float b) {
        return t == 1 ? b : a + t * (b - a);
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
