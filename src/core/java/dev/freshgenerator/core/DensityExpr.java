package dev.freshgenerator.core;

import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** Small immutable terrain IR. Outputs the native 26.3 density graph, not a custom chunk filler. */
public sealed interface DensityExpr permits DensityExpr.Constant, DensityExpr.Reference, DensityExpr.Binary {
    float evaluate(ToDoubleFunction<String> inputs);
    Object json();

    record Constant(float value) implements DensityExpr {
        public Constant { if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite constant"); }
        @Override public float evaluate(ToDoubleFunction<String> inputs) { return value; }
        @Override public Object json() { return value; }
    }
    record Reference(String id) implements DensityExpr {
        public Reference {
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid resource id");
        }
        @Override public float evaluate(ToDoubleFunction<String> inputs) {
            float value = (float) inputs.applyAsDouble(id);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite input: " + id);
            return value;
        }
        @Override public Object json() { return id; }
    }
    enum Op { ADD, SUB, MUL, MIN, MAX }
    record Binary(Op op, DensityExpr left, DensityExpr right) implements DensityExpr {
        public Binary { Objects.requireNonNull(op); Objects.requireNonNull(left); Objects.requireNonNull(right); }
        @Override public float evaluate(ToDoubleFunction<String> inputs) {
            float a = left.evaluate(inputs), b = right.evaluate(inputs);
            return switch (op) {
                case ADD -> a + b; case SUB -> a - b; case MUL -> a * b;
                case MIN -> Math.min(a, b); case MAX -> Math.max(a, b);
            };
        }
        @Override public Object json() {
            return Map.of("type", "minecraft:" + op.name().toLowerCase(java.util.Locale.ROOT),
                    "left", left.json(), "right", right.json());
        }
    }
    static DensityExpr constant(double value) { return new Constant((float) value); }
    static DensityExpr ref(String id) { return new Reference(id); }
    static DensityExpr add(DensityExpr a, DensityExpr b) { return new Binary(Op.ADD, a, b); }
    static DensityExpr sub(DensityExpr a, DensityExpr b) { return new Binary(Op.SUB, a, b); }
    static DensityExpr mul(DensityExpr a, DensityExpr b) { return new Binary(Op.MUL, a, b); }
    static DensityExpr scale(DensityExpr a, double value) { return mul(a, constant(value)); }
    static DensityExpr clamp01(DensityExpr a) {
        return new Binary(Op.MIN, constant(1), new Binary(Op.MAX, constant(0), a));
    }
    static DensityExpr smoothstep(double lower, double upper, DensityExpr a) {
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || upper <= lower)
            throw new IllegalArgumentException("Invalid transition interval");
        DensityExpr t = clamp01(scale(sub(a, constant(lower)), 1 / (upper - lower)));
        return mul(mul(t, t), sub(constant(3), scale(t, 2)));
    }
}
