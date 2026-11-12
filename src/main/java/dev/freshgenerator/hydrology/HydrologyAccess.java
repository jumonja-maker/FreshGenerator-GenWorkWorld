package dev.freshgenerator.hydrology;

/** The climate resolver and terrain fill are bound to the same native RandomState. */
public interface HydrologyAccess {
    DrainageDensity.Sampler freshgenerator$drainage();
    void freshgenerator$drainage(DrainageDensity.Sampler sampler);
}
