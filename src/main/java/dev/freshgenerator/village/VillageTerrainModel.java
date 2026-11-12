package dev.freshgenerator.village;

import java.util.*;

/** Continuous, bounded plot grading. No random source, biome replacement or world access.
 * Native piece groundY is the first open block; the ground block is groundY - 1.
 * Terrain-matching roads protect the existing hillside rather than becoming flat rectangles.
 */
public final class VillageTerrainModel {
    public static final double RADIUS = 24.0, SAFE_MARGIN = 4.0, ROOF = 24.0, DEEP_FADE = 16.0;
    public record Site(int minX, int minZ, int maxX, int maxZ, int groundY, boolean rigid) {
        public Site {
            if (minX > maxX || minZ > maxZ || groundY < -64 || groundY > 319)
                throw new IllegalArgumentException("Invalid village footprint");
        }
        public double distance(double x, double z) {
            double dx = Math.max(Math.max((double)minX - x, x - (double)maxX), 0.0);
            double dz = Math.max(Math.max((double)minZ - z, z - (double)maxZ), 0.0);
            return Math.hypot(dx, dz);
        }
    }
    public record Column(double originalHeight, double height, double influence) {
        public Column {
            if (!Double.isFinite(originalHeight) || !Double.isFinite(height) || !Double.isFinite(influence)
                    || influence < 0 || influence > 1) throw new IllegalArgumentException("Invalid village column");
        }
        public double density(double original, int y) {
            if (!Double.isFinite(original)) throw new IllegalArgumentException("Nonfinite density");
            if (influence == 0) return original;
            double bottom = Math.min(originalHeight, height) - ROOF;
            double top = Math.max(originalHeight, height) + 4;
            double alpha = influence * smooth(bottom - DEEP_FADE, bottom, y) * (1 - smooth(top, top + 8, y));
            if (alpha == 0) return original;
            double grade = (height - y - 0.5) * 0.125;
            return alpha == 1 ? grade : original + alpha * (grade - original);
        }
        public boolean permitsCarving(int y) {
            return influence == 0 || y < height - ROOF * influence || y > Math.max(originalHeight, height) + 4;
        }
    }
    private final List<Site> sites;
    public VillageTerrainModel(Collection<Site> sites) {
        Objects.requireNonNull(sites);
        this.sites = sites.stream().map(Objects::requireNonNull).distinct().sorted(
            Comparator.comparingInt(Site::minX).thenComparingInt(Site::minZ)
                .thenComparingInt(Site::maxX).thenComparingInt(Site::maxZ)
                .thenComparingInt(Site::groundY).thenComparing(Site::rigid)).toList();
    }
    public List<Site> sites() { return sites; }
    public Column column(int x, int z, double baseHeight, int seaLevel) {
        if (!Double.isFinite(baseHeight)) throw new IllegalArgumentException("Nonfinite terrain height");
        double protection = 0, outer = 0, weightSum = 0, targetSum = 0;
        double coreTargetSum = 0; int coreCount = 0;
        for (Site site : sites) {
            double distance = site.distance(x, z);
            if (distance >= RADIUS) continue;
            protection = Math.max(protection, 1 - smooth(SAFE_MARGIN, RADIUS, distance));
            if (!site.rigid) continue;
            double d = Math.max(0, distance - 1);
            if (d == 0) { coreTargetSum += site.groundY; coreCount++; continue; }
            double w = 1 - smooth(0, RADIUS - 1, d);
            outer = Math.max(outer, w);
            double priority = w / (d * d * d * d);
            weightSum += priority; targetSum += priority * site.groundY;
        }
        if (protection == 0) return new Column(baseHeight, baseHeight, 0);
        // Open water beside a path must not turn into an earthen dam.
        if (baseHeight < seaLevel && coreCount == 0) return new Column(baseHeight, baseHeight, 0);
        double target = baseHeight;
        if (coreCount > 0) target = coreTargetSum / coreCount;
        else if (weightSum > 0) target += outer * (targetSum / weightSum - target);
        double land = coreCount > 0 ? 1 : smooth(seaLevel, seaLevel + 3, baseHeight);
        return new Column(baseHeight, baseHeight + land * (target - baseHeight), protection * land);
    }
    public static double smooth(double lo, double hi, double value) {
        if (!(hi > lo) || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid smooth interval/value");
        double t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
        return t*t*t*(t*(t*6 - 15) + 10);
    }
}
