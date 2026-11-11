"""Stage-aware regression contract, not a whitelist of decorative block names.

Raw TERRAIN states must be identical. FULL states can change after native features
run on a changed surface: retain every difference, bound occupancy/fluid drift,
and require matching pre-feature evidence for every changed sampled coordinate.
Budgets are conservative engineering release limits, not an ecological constant.
"""
import math
from cave_audit_metrics import compare_deep, compare_terrain_stage, decode_deep


def compare_full_stage(before, after, terrain_before, terrain_after):
    stage = compare_terrain_stage([terrain_before], [terrain_after])
    stats = compare_deep(before, after)
    raw_a, states_a, geom_a, positions = decode_deep(before)
    raw_b, states_b, geom_b, _ = decode_deep(after)
    verified = {(c['chunk_x'], c['chunk_z']) for c in terrain_before['chunks']
                if c['before_features'] and c['samples'] > 0}
    # Both snapshots and FULL sampling use the same stride/minimum/maximum depth.
    # Even a single observed change cannot be excused without its TERRAIN evidence.
    for p, a, b in zip(positions, states_a, states_b):
        if a != b and (p[0] // 16, p[2] // 16) not in verified:
            raise ValueError(f'FULL change lacks exact pre-feature evidence: {p}: {a} -> {b}')
    n = len(raw_a)
    occupancy_limit = min(128, max(8, math.floor(n * .005)))
    fluid_limit = min(32, max(4, math.floor(n * .002)))
    fluid_changes = sum((a == 2) != (b == 2) for a, b in zip(raw_a, raw_b))
    count = stats['deep_terrain_occupancy_changes']
    net_air = geom_b.count(0) - geom_a.count(0)
    net_fluid = raw_b.count(2) - raw_a.count(2)
    result = {'verified_deep_terrain': stage,
              'post_feature_occupancy_changes': count,
              'post_feature_fluid_occupancy_changes': fluid_changes,
              'post_feature_net_air_change': net_air,
              'post_feature_net_fluid_change': net_fluid,
              'occupancy_limit_cells': occupancy_limit, 'fluid_limit_cells': fluid_limit,
              'all_changed_samples_have_exact_terrain_evidence': True,
              'budget_passed': count <= occupancy_limit and fluid_changes <= fluid_limit,
              'full_blocks_identical': stats['deep_block_state_changes'] == 0,
              'interpretation': 'Differences observed after TERRAIN. Exact causation or deterministic FULL decoration is not inferred from two worlds.'}
    if not result['budget_passed']:
        raise ValueError(f'Excessive FULL-stage deep drift: {result}; examples={stats["first_deep_state_changes"]}')
    return result
