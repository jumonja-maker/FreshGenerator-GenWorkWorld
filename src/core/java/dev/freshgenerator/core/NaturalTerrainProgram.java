package dev.freshgenerator.core;

import java.util.*;
import java.util.function.ToDoubleFunction;
import static dev.freshgenerator.core.DensityExpr.*;

/** Version 2: continuous foothills, shoulders, sharp crests and bounded side gullies.
 * This is procedural terrain shaping, NOT a physical erosion or drainage simulation.
 * Climate inputs and biome/structure selection are deliberately not rewritten.
 */
public final class NaturalTerrainProgram {
    private final String prefix;
    private final Map<String, DensityExpr> nodes;

    private NaturalTerrainProgram(String prefix, Map<String, DensityExpr> nodes) {
        this.prefix = prefix;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public static NaturalTerrainProgram compile(String prefix, TerrainProfile profile) {
        Objects.requireNonNull(profile);
        if (prefix == null || !prefix.matches("freshgenerator:[a-z0-9_]+_v2/"))
            throw new IllegalArgumentException("Natural terrain requires an explicit v2 namespace");
        Map<String, DensityExpr> n = new LinkedHashMap<>();
        DensityExpr c = ref(prefix + "continents"), e = ref(prefix + "erosion");
        DensityExpr p = ref(prefix + "ridges_folded");
        // Wider C2 envelopes: no discrete branch on a biome ID and no step at a chunk edge.
        n.put("land_mask", smootherstep(-0.11, 0.28, c));
        n.put("mountain_province", sub(constant(1), smootherstep(-0.82, 0.08, e)));
        n.put("valley_clearance", smootherstep(-0.92, -0.48, p));
        n.put("foothills", smootherstep(-0.75, 0.45, p));
        n.put("shoulders", smootherstep(-0.10, 0.85, p));
        DensityExpr crest = clamp01(scale(sub(p, constant(0.10)), 1.0 / 0.90));
        // Cubic, rather than a saturated smoothstep, keeps a pointed ridge at the folded maximum.
        n.put("crests", mul(mul(crest, crest), crest));
        n.put("summit_mask", smootherstep(0.35, 0.95, p));
        n.put("massif_variation", add(constant(0.82), scale(smootherstep(-0.8, 0.8,
                ref(prefix + "massif_noise")), 0.18)));
        DensityExpr gully = ref(prefix + "spur_noise");
        DensityExpr absoluteGully = new DensityExpr.Binary(DensityExpr.Op.MAX, gully, scale(gully, -1));
        n.put("gully_mask", sub(constant(1), smootherstep(0.03, 0.30, absoluteGully)));
        n.put("slope_band", mul(smootherstep(-0.35, 0.25, p),
                sub(constant(1), smootherstep(0.65, 0.95, p))));
        n.put("gully_attenuation", sub(constant(1), scale(mul(ref(prefix + "gully_mask"),
                ref(prefix + "slope_band")), 0.18)));
        n.put("ridge_shape", add(add(scale(ref(prefix + "foothills"), 0.22),
                scale(ref(prefix + "shoulders"), 0.38)), scale(ref(prefix + "crests"), 0.40)));
        n.put("mountain_mask", mul(mul(ref(prefix + "land_mask"), ref(prefix + "mountain_province")),
                mul(ref(prefix + "valley_clearance"), mul(ref(prefix + "ridge_shape"),
                mul(ref(prefix + "massif_variation"), ref(prefix + "gully_attenuation"))))));
        // No extra uplift in river corridors; no ocean deepening in the mushroom-island region.
        n.put("ocean_mask", mul(smootherstep(0.18, 0.51, scale(c, -1)), smootherstep(-1.05, -1.02, c)));
        n.put("height_delta", sub(scale(ref(prefix + "mountain_mask"), profile.mountainUplift()),
                scale(ref(prefix + "ocean_mask"), profile.oceanDeepening())));
        n.put("offset_delta", scale(ref(prefix + "height_delta"), 1.0 / 128.0));
        // Keep rock detail on crests; soften the noisy pedestal below them.
        n.put("jaggedness_weight", add(constant(0.35), scale(ref(prefix + "summit_mask"), 0.65)));
        return new NaturalTerrainProgram(prefix, n);
    }

    /** Quintic transition with zero first/second derivatives at both ends. */
    public static DensityExpr smootherstep(double lo, double hi, DensityExpr value) {
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi <= lo)
            throw new IllegalArgumentException("Invalid interval");
        DensityExpr t = clamp01(scale(sub(value, constant(lo)), 1.0 / (hi - lo)));
        DensityExpr polynomial = add(constant(10), mul(t, add(constant(-15), scale(t, 6))));
        return clamp01(mul(mul(mul(t, t), t), polynomial));
    }

    public Map<String, DensityExpr> nodes() { return nodes; }
    public float sample(String node, ToDoubleFunction<String> input) {
        return evaluate(node, input, new HashMap<>(), 0);
    }
    public float heightDelta(float c, float e, float p, float massif, float spurs) {
        return sample("height_delta", id -> {
            if (id.equals(prefix + "continents")) return c;
            if (id.equals(prefix + "erosion")) return e;
            if (id.equals(prefix + "ridges_folded")) return p;
            if (id.equals(prefix + "massif_noise")) return massif;
            if (id.equals(prefix + "spur_noise")) return spurs;
            throw new IllegalArgumentException("Unknown terrain input: " + id);
        });
    }
    private float evaluate(String name, ToDoubleFunction<String> input, Map<String, Float> memo, int depth) {
        if (depth > 24) throw new IllegalStateException("Cyclic terrain graph");
        Float found = memo.get(name);
        if (found != null) return found;
        DensityExpr expression = nodes.get(name);
        if (expression == null) throw new IllegalArgumentException("Unknown node: " + name);
        float result = expression.evaluate(id -> {
            String local = id.startsWith(prefix) ? id.substring(prefix.length()) : "";
            return nodes.containsKey(local) ? evaluate(local, input, memo, depth + 1) : input.applyAsDouble(id);
        });
        memo.put(name, result);
        return result;
    }
}
