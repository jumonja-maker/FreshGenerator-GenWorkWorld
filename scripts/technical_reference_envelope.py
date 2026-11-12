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
        # Limit control instability, not the number of permissible unknown candidate states.
        # Unknown candidate values always fail, including at a variable coordinate.
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


def validate_telemetry(references, candidate):
    require(len(references) == 3, 'Exactly three telemetry controls are required')
    for name in ('forest_telemetry', 'cave_telemetry'):
        require(all(set(r[name]) == set(candidate[name]) for r in references), 'Telemetry schema changed')
        require(all(type(v) is int and v >= 0 for r in [*references, candidate] for v in r[name].values()), 'Invalid telemetry')
    require(all(r['cave_telemetry'] == candidate['cave_telemetry'] for r in references), 'Carving decisions changed')
    # Require one complete observed forest snapshot, not independent min/max ranges.
    require(any(r['forest_telemetry'] == candidate['forest_telemetry'] for r in references), 'Forest telemetry was not reproduced by an unchanged reference')
    return {'cave_counts_exact_in_all_controls': True,
            'forest_counts_equal_first_reference': references[0]['forest_telemetry'] == candidate['forest_telemetry'],
            'forest_counts_equal_complete_observed_reference': True,
            'forest_reference_snapshots': [r['forest_telemetry'] for r in references]}
