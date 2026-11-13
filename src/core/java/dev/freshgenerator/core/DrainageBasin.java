package dev.freshgenerator.core;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.PriorityQueue;

import static dev.freshgenerator.core.DrainageModel.*;

/** A local, verified depression with an optional route over its spill saddle. */
record DrainageBasin(Cell center, int top, BitSet footprint, long[] path, int saddleIndex) {
    static final int SEARCH = 7;
    private static final int RADIUS = 96;
    private static final int DIAMETER = 2 * RADIUS + 1;
    private static final int MIN_AREA = 128;
    private static final int MAX_AREA = 4800;
    private static final int[] CARDINAL = {-DIAMETER, -1, 1, DIAMETER};

    long outlet() { return path.length == 0 ? NO_CELL : path[path.length - 1]; }

    boolean isNear(int x, int z, int size) {
        int margin = (SEARCH + 1) * SPACING + 24;
        return center.x() + margin >= x && center.x() - margin <= x + size
            && center.z() + margin >= z && center.z() - margin <= z + size;
    }

    void paint(Raster raster) {
        for (int i = footprint.nextSetBit(0); i >= 0; i = footprint.nextSetBit(i + 1)) {
            raster.lakeColumn(center.x() + i % DIAMETER - RADIUS, center.z() + i / DIAMETER - RADIUS, top, path.length != 0);
        }
    }

    static DrainageBasin find(DrainageModel model, Cell center, int flow, Field terrain) {
        Spill spill = spill(model, center);
        if (spill.level < center.height() + 2.0f) {
            // A shallow minimum on the sampled 16-block mesh is not necessarily
            // a real lake. Preserve its certified downhill connection WITHOUT
            // painting a lake; otherwise narrow rivers become disconnected canals.
            if (spill.path.length == 0) return null;
            return new DrainageBasin(center, (int) Math.floor(model.riverLevel(center)),
                    new BitSet(), spill.path, spill.saddleIndex);
        }
        if (flow < MIN_FLOW) return null;
        int sill = (int) Math.floor(spill.level - 0.6f);
        int minimum = Math.max(model.sea() - 1, (int) Math.floor(center.height()) + 1);
        // Runoff constrains the surface area that can balance evaporation.
        // A drier catchment has a smaller area budget for the same inflow.
        int areaBudget = Math.min(MAX_AREA, 60 + flow * (7 + 7 * center.runoff()));
        int highest = Math.min(sill, minimum + 10);
        int[] queue = new int[areaBudget + 1];
        for (int level = highest; level >= minimum; level--) {
            BitSet flooded = flood(center, level, terrain, queue);
            if (flooded == null) continue;
            if (flooded.cardinality() < MIN_AREA || hasLowerOwner(model, center, flooded)) return null;
            boolean throughflow = level == sill && spill.path.length > 0;
            return new DrainageBasin(center, level, flooded, throughflow ? spill.path : new long[0], spill.saddleIndex);
        }
        return null;
    }

    private static BitSet flood(Cell center, int waterTop, Field terrain, int[] queue) {
        BitSet visited = new BitSet(DIAMETER * DIAMETER);
        BitSet flooded = new BitSet(DIAMETER * DIAMETER);
        int origin = RADIUS * DIAMETER + RADIUS;
        int count = 1;
        queue[0] = origin;
        visited.set(origin);
        flooded.set(origin);
        for (int i = 0; i < count; i++) {
            int slot = queue[i];
            int dx = slot % DIAMETER - RADIUS, dz = slot / DIAMETER - RADIUS;
            // A clipped footprint is inconclusive, so this water level is ineligible.
            if (Math.abs(dx) == RADIUS || Math.abs(dz) == RADIUS) return null;
            for (int direction : CARDINAL) {
                int next = slot + direction;
                if (visited.get(next)) continue;
                visited.set(next);
                int x = center.x() + next % DIAMETER - RADIUS;
                int z = center.z() + next / DIAMETER - RADIUS;
                if (terrain.at(x, z) >= waterTop + 0.5f) continue;
                if (count == queue.length - 1) return null;
                flooded.set(next);
                queue[count++] = next;
            }
        }
        return flooded;
    }

    private static boolean hasLowerOwner(DrainageModel model, Cell center, BitSet flooded) {
        int gx = x(center.key()), gz = z(center.key());
        for (int dz = -SEARCH; dz <= SEARCH; dz++) {
            for (int dx = -SEARCH; dx <= SEARCH; dx++) {
                Cell other = model.cell(gx + dx, gz + dz);
                if (other.ocean() || other.receiver() != NO_CELL || other.key() == center.key()) continue;
                int rx = other.x() - center.x() + RADIUS, rz = other.z() - center.z() + RADIUS;
                if (rx < 0 || rx >= DIAMETER || rz < 0 || rz >= DIAMETER || !flooded.get(rz * DIAMETER + rx)) continue;
                if (other.height() < center.height()
                    || (other.height() == center.height() && other.key() < center.key())) return true;
            }
        }
        return false;
    }

    private record Visit(int cell, float level) { }
    private record Spill(float level, long[] path, int saddleIndex) { }

    /**
     * Minimax Priority-Flood from the sink. A path to lower ground is certified
     * only when it is found before an equally low escape from the search window.
     */
    private static Spill spill(DrainageModel model, Cell center) {
        int side = 2 * SEARCH + 1;
        int origin = SEARCH * side + SEARCH;
        int gx = x(center.key()) - SEARCH, gz = z(center.key()) - SEARCH;
        int[] parent = new int[side * side];
        Arrays.fill(parent, -1);
        var pending = new PriorityQueue<>(Comparator.comparingDouble(Visit::level).thenComparingInt(Visit::cell));
        pending.add(new Visit(origin, center.height()));
        parent[origin] = origin;
        Visit outlet = null;
        while (!pending.isEmpty()) {
            Visit visit = pending.remove();
            if (outlet != null && visit.level > outlet.level) return path(model, parent, outlet, gx, gz, side);
            int ix = visit.cell % side, iz = visit.cell / side;
            if (ix == 0 || iz == 0 || ix == side - 1 || iz == side - 1) return new Spill(visit.level, new long[0], 0);
            Cell cell = model.cell(gx + ix, gz + iz);
            if (cell.height() < center.height() - 0.55f) {
                if (outlet == null) outlet = visit;
                // The verified lower exterior is an outlet, not part of the search
                // for a competing route out of the depression.
                continue;
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int next = visit.cell + dz * side + dx;
                    if (parent[next] >= 0) continue;
                    parent[next] = visit.cell;
                    float height = model.cell(gx + ix + dx, gz + iz + dz).height();
                    pending.add(new Visit(next, Math.max(visit.level, height)));
                }
            }
        }
        if (outlet != null) return path(model, parent, outlet, gx, gz, side);
        throw new IllegalStateException("Drainage search has no boundary");
    }

    private static Spill path(DrainageModel model, int[] parent, Visit outlet, int gx, int gz, int side) {
        int length = 1;
        for (int i = outlet.cell; parent[i] != i; i = parent[i]) length++;
        long[] path = new long[length];
        int saddle = 0;
        float highest = Float.NEGATIVE_INFINITY;
        for (int i = outlet.cell, index = length - 1; index >= 0; i = parent[i], index--) {
            long key = key(gx + i % side, gz + i / side);
            float height = model.cell(key).height();
            if (height > highest) {
                highest = height;
                saddle = index;
            }
            path[index] = key;
        }
        return new Spill(outlet.level, downhillTail(model, path, saddle), saddle);
    }

    /** Minimax search certifies the crest but its equal-cost index tie-break can
     * wander back uphill after it. Prefer the already defined natural receiver
     * chain as soon as it certifies a lower outlet inside that same search window.
     * Keep the certified path when natural drainage leads back into the basin.
     * This changes no saddle level, adds no outlet and performs no unbounded walk.
     */
    private static long[] downhillTail(DrainageModel model, long[] path, int saddle) {
        Cell center = model.cell(path[0]);
        int gx = x(center.key()), gz = z(center.key());
        for (int start = saddle; start < path.length - 1; start++) {
            var tail = new ArrayList<Long>();
            Cell current = model.cell(path[start]);
            while (true) {
                // Search boundaries cannot certify an exit: use the original path.
                if (Math.abs(x(current.key()) - gx) >= SEARCH || Math.abs(z(current.key()) - gz) >= SEARCH)
                    break;
                tail.add(current.key());
                if (current.height() < center.height() - 0.55f) {
                    long[] result = Arrays.copyOf(path, start + tail.size());
                    for (int i = 0; i < tail.size(); i++) result[start + i] = tail.get(i);
                    return result;
                }
                if (current.receiver() == NO_CELL) break;
                Cell next = model.cell(current.receiver());
                if (!(next.height() < current.height()))
                    throw new IllegalStateException("Natural spill descent must lose elevation");
                current = next;
            }
        }
        return path;
    }
}
