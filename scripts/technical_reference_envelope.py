"""Validate every sampled block against independently observed reference states.
No block type, column, or changed candidate value is silently excluded. Only values
actually seen at that exact coordinate in three clean 0.3.2 worlds are admissible.
"""
import hashlib


def require(ok, message):
    if not ok:
        raise ValueError(message)


def states(window):
    palette, indices = window['block_palette'], window['sampled_states']
    require(isinstance(palette, list) and palette and len(palette) == len(set(palette)), 'Invalid block palette')
    require(all(isinstance(x, str) and x for x in palette), 'Missing block description')
    require(len(indices) == window['sample_columns'] * 384, 'Incomplete FULL block array')
    require(all(type(x) is int and 0 <= x < len(palette) for x in indices), 'Invalid block index')
    decoded = [palette[x] for x in indices]
    h = hashlib.sha256()
    for value in decoded:
        h.update((value + '\n').encode())
    require(h.hexdigest() == window['sampled_full_blocks_sha256'], 'Block array does not match its snapshot')
    return decoded


def validate_full(reference_windows, candidate_windows):
    require(len(reference_windows) == 3, 'Exactly three independently generated references are required')
    require(candidate_windows and all(len(rows) == len(candidate_windows) for rows in reference_windows), 'Window counts differ')
    total = varied = changed = 0
    observations = []
    for wi, candidate in enumerate(candidate_windows):
        controls = [rows[wi] for rows in reference_windows]
        for control in controls:
            for field in ('label', 'x', 'z', 'sample_columns', 'base_geometry_water_sha256', 'biomes'):
                require(control[field] == candidate[field], 'Changed geographical/stored biome field: ' + field)
        before = [states(w) for w in controls]
        after = states(candidate)
        local_varied = local_changed = 0
        examples = []
        for i, (a, b, c, actual) in enumerate(zip(*before, after)):
            observed = {a, b, c}
            require(actual in observed, f"Candidate introduced an unobserved block at window {wi}, sample {i}: {actual} not in {sorted(observed)}")
            local_varied += len(observed) > 1
            local_changed += actual != a
            if len(observed) > 1 and len(examples) < 24:
                col, y = divmod(i, 384)
                dx, dz = divmod(col, 16)
                examples.append({'x': candidate['x'] + dx * 4, 'y': y - 64, 'z': candidate['z'] + dz * 4,
                                 'reference_states': [a, b, c], 'candidate': actual})
        require(local_varied <= len(after) // 1000, 'Reference window is too variable for a technical release')
        total += len(after)
        varied += local_varied
        changed += local_changed
        observations.append({'label': candidate['label'], 'x': candidate['x'], 'z': candidate['z'],
                             'sampled_blocks': len(after), 'reference_variable_blocks': local_varied,
                             'candidate_changes_from_first_reference': local_changed,
                             'unobserved_candidate_blocks': 0, 'examples': examples})
    return {'sampled_blocks': total, 'reference_variable_blocks': varied,
            'candidate_changes_from_first_reference': changed, 'unobserved_candidate_blocks': 0,
            'all_stable_reference_blocks_equal': True, 'windows': observations}


FAMILIES = ('TEMPERATE', 'CONIFER', 'TROPICAL')
FOREST_KEYS = {f + suffix for f in FAMILIES for suffix in ('_attempts', '_kept', '_opening_attempts')}
ORACLE_KEYS = {'verified_calls', 'hits', 'misses', 'mismatches'}


def validate_telemetry(references, candidate):
    require(len(references) == 3, 'Exactly three telemetry controls are required')
    for name in ('forest_telemetry', 'cave_telemetry'):
        require(all(set(r[name]) == set(candidate[name]) for r in references), 'Telemetry schema changed')
        require(all(type(v) is int and v >= 0 for r in [*references, candidate] for v in r[name].values()), 'Invalid telemetry')
    require(all(r['cave_telemetry'] == candidate['cave_telemetry'] for r in references), 'Carving decisions changed')
    oracles = []
    for report in [*references, candidate]:
        forest = report['forest_telemetry']
        require(set(forest) == FOREST_KEYS, 'Incomplete forest telemetry')
        attempts = 0
        for family in FAMILIES:
            n = forest[family + '_attempts']
            attempts += n
            require(forest[family + '_kept'] <= n and forest[family + '_opening_attempts'] <= n,
                    'Impossible forest counter relationships')
        oracle = report.get('forest_height_oracle')
        require(isinstance(oracle, dict) and set(oracle) == ORACLE_KEYS, 'Missing native height-cache oracle')
        require(all(type(v) is int and v >= 0 for v in oracle.values()), 'Invalid height-cache evidence')
        require(oracle['mismatches'] == 0, 'Actual native cache protocol/value mismatch')
        require(oracle['verified_calls'] == oracle['hits'] + oracle['misses'], 'Incomplete cache evidence')
        require(oracle['verified_calls'] >= attempts, 'Not every observed forest attempt exercised the oracle')
        require(attempts == 0 or oracle['misses'] > 0, 'Native height sampling was not exercised')
        oracles.append(oracle)
    # Process-wide decoration counters varied even between unchanged control runs.
    # Do not invent an equal snapshot or use per-counter tolerance bands. Record them
    # verbatim; correctness now additionally requires EVERY instrumented native height
    # call to follow the old map's exact hit/miss, coordinate and float-bit protocol.
    # Maps, water, same-coordinate block envelopes and reload equality remain hard gates.
    return {'cave_counts_exact_in_all_controls': True,
            'forest_counts_equal_first_reference': references[0]['forest_telemetry'] == candidate['forest_telemetry'],
            'forest_counts_equal_complete_observed_reference': any(r['forest_telemetry'] == candidate['forest_telemetry'] for r in references),
            'forest_counts_are_observational_not_a_determinism_claim': True,
            'native_height_cache_protocol_verified': True,
            'candidate_height_oracle': oracles[-1],
            'reference_height_oracles': oracles[:-1],
            'forest_reference_snapshots': [r['forest_telemetry'] for r in references]}
