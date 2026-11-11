package dev.freshgenerator.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import static dev.freshgenerator.core.DensityExpr.*;

/** Smooth, coast-aware uplift/deepening. Native climate distributions select the biome as before. */
public final class TerrainProgram {
    private final String prefix;
    private final Map<String, DensityExpr> nodes;
    private TerrainProgram(String prefix, Map<String, DensityExpr> nodes) {
        this.prefix = prefix;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
    public static TerrainProgram compile(String prefix, TerrainProfile profile) {
        Objects.requireNonNull(profile);
        if (prefix == null || !prefix.matches("freshgenerator:[a-z0-9_/]+/"))
            throw new IllegalArgumentException("A versioned FreshGenerator prefix is required");
        Map<String, DensityExpr> nodes = new LinkedHashMap<>();
        DensityExpr c = ref(prefix + "continents"), e = ref(prefix + "erosion"), p = ref(prefix + "ridges_folded");
        nodes.put("land_mask", smoothstep(-0.11, 0.20, c));
        nodes.put("mountain_mask", mul(mul(ref(prefix + "land_mask"),
                sub(constant(1), smoothstep(-0.80, -0.20, e))), smoothstep(0.25, 0.85, p)));
        // Exclude the extreme continentalness region used for mushroom islands.
        nodes.put("ocean_mask", mul(smoothstep(0.18, 0.51, scale(c, -1)), smoothstep(-1.05, -1.02, c)));
        nodes.put("height_delta", sub(scale(ref(prefix + "mountain_mask"), profile.mountainUplift()),
                scale(ref(prefix + "ocean_mask"), profile.oceanDeepening())));
        // The pinned native depth gradient changes by 1/128 per vertical block; checked during compilation.
        nodes.put("offset_delta", scale(ref(prefix + "height_delta"), 1.0 / 128.0));
        return new TerrainProgram(prefix, nodes);
    }
    public Map<String, DensityExpr> nodes() { return nodes; }
    public float heightDelta(float continents, float erosion, float foldedRidges) {
        ToDoubleFunction<String> climate = id -> {
            if (id.equals(prefix + "continents")) return continents;
            if (id.equals(prefix + "erosion")) return erosion;
            if (id.equals(prefix + "ridges_folded")) return foldedRidges;
            throw new IllegalArgumentException("Unknown input " + id);
        };
        return evaluate("height_delta", climate, 0);
    }
    private float evaluate(String localName, ToDoubleFunction<String> climate, int depth) {
        if (depth > 16) throw new IllegalStateException("Cyclic terrain program");
        DensityExpr expression = nodes.get(localName);
        if (expression == null) throw new IllegalArgumentException(localName);
        return expression.evaluate(id -> {
            String name = id.startsWith(prefix) ? id.substring(prefix.length()) : "";
            return nodes.containsKey(name) ? evaluate(name, climate, depth + 1) : climate.applyAsDouble(id);
        });
    }
}
