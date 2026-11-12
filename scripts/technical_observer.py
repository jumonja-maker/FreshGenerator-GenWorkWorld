"""Prepare only technical A/B observers; historical biome-scale audits stay unchanged."""

PHASE_SWITCH = 'candidate=!"reference".equals(System.getenv("FG_REGION_MODE"));'
CURRENT_SWITCH = 'candidate=false; /* technical comparison: current climate on BOTH versions */'


def current_climate_observer(source, *, ordered=True):
    if not isinstance(source, str) or source.count(PHASE_SWITCH) != 1:
        raise ValueError("Expected exactly one historical observer phase switch")
    # Metadata retains reference/candidate. Both sides observe the current climate.
    result = source.replace(PHASE_SWITCH, CURRENT_SWITCH)
    if ordered:
        from technical_generation_order import ordered_generation_observer
        result = ordered_generation_observer(result)
    return result
