"""Prepare only technical A/B observers; historical biome-scale audits stay unchanged."""

PHASE_SWITCH = 'candidate=!"reference".equals(System.getenv("FG_REGION_MODE"));'
CURRENT_SWITCH = 'candidate=false; /* technical comparison: current climate on BOTH versions */'


def current_climate_observer(source):
    if not isinstance(source, str) or source.count(PHASE_SWITCH) != 1:
        raise ValueError("Expected exactly one historical observer phase switch")
    # Metadata still describes reference/candidate. Only the obsolete, candidate-only
    # 0.3.1 reconstruction is disabled equally for both sides of the technical A/B.
    # No production source, sample coordinate, array, hash or tolerance changes.
    return source.replace(PHASE_SWITCH, CURRENT_SWITCH)
