package dev.freshgenerator.core;

/** Immutable geography only. No biome/structure weights, rarity or feature-density knobs. */
public record TerrainProfile(int formatVersion, double continentScale, double biomeScale,
        double terrainScale, double mountainUplift, double oceanDeepening) {
    public static final int CURRENT_FORMAT = 1;
    public static final TerrainProfile BALANCED = new TerrainProfile(1, 1.75, 1.25, 1.35, 40, 16);
    public static final TerrainProfile HIGHLANDS = new TerrainProfile(1, 2.5, 1.5, 1.75, 64, 24);
    public static final TerrainProfile IDENTITY = new TerrainProfile(1, 1, 1, 1, 0, 0);
    public TerrainProfile {
        if (formatVersion != CURRENT_FORMAT) throw new IllegalArgumentException("Unsupported terrain format: " + formatVersion);
        range("continentScale", continentScale, 0.5, 8);
        range("biomeScale", biomeScale, 0.5, 4);
        range("terrainScale", terrainScale, 0.5, 4);
        range("mountainUplift", mountainUplift, 0, 64);
        range("oceanDeepening", oceanDeepening, 0, 24);
    }
    private static void range(String key, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(key + " must be finite in [" + min + ", " + max + "]");
    }
}
