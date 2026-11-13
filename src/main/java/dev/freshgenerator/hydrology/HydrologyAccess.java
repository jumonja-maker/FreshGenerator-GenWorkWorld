package dev.freshgenerator.hydrology;

/** The climate resolver and terrain fill are bound to the same native RandomState. */
public interface HydrologyAccess {
    ScopedValue<Boolean> STRUCTURE_PLANNING = ScopedValue.newInstance();

    DrainageDensity.Sampler freshgenerator$drainage();
    void freshgenerator$drainage(DrainageDensity.Sampler sampler);
}
