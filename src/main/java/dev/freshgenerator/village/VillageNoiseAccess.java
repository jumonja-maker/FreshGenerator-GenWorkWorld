package dev.freshgenerator.village;

/** Owned by one native NoiseChunk, never a global or cross-world cache. */
public interface VillageNoiseAccess {
    VillageTerrainPlan freshgenerator$villagePlan();
    void freshgenerator$villagePlan(VillageTerrainPlan plan);
}
