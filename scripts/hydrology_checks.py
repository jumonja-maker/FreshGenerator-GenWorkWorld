"""Validate native FULL chunk observations from the hydrology server matrix."""

import hashlib
import json
import sys
from collections import deque
from pathlib import Path

from village_checks import fingerprint, require

SIDE = 33
RADIUS = SIDE // 2
MISSING = -999
WATER_FLAGS = 1 | 2
RIVER_BIOME = 16
MODES = ('candidate', 'baseline', 'reverse', 'parallel', 'reload',
         'normal-baseline', 'normal-repeat-1', 'normal-repeat-2', 'normal-candidate')
VANILLA_MODES = ('normal-baseline', 'normal-repeat-1', 'normal-repeat-2')


def read_json(path):
    return json.loads(path.read_text())


def load(folder, seed, mode, plan):
    result = read_json(folder / f'{mode}.json')
    expected = 'minecraft:overworld' if mode.startswith('normal-') else 'freshgenerator:highlands_v2'
    require(result['seed'] == seed and result['mode'] == mode and result['settings'] == expected,
            f'Incorrect seed, mode, or noise settings for {mode}')
    require(result['minecraft'] == '26.3' and result['fabric_loader'] == '0.20.1'
            and result['java'].split('.')[0].split('+')[0] == '25', f'Wrong game or Java version for {mode}')
    require(result['freshgenerator_loaded'] is (mode not in VANILLA_MODES), f'Wrong mod set for {mode}')
    require(result['full_chunks'] >= 9 and result['chunk_generation_ms'] >= 0,
            f'Missing FULL generation for {mode}')
    require(result['requested_parallel'] == (mode == 'parallel'), f'Wrong generation mode for {mode}')
    log = (folder / f'{mode}.log').read_text()
    require(f'FRESHGENERATOR_HYDROLOGY_WORLD_PASS {mode} {seed}' in log,
            f'Native server did not complete {mode}')
    require(not any(w in log for w in ('Server Watchdog', 'A single server tick took', 'OutOfMemoryError')),
            f'Watchdog or memory failure in {mode}')
    result['peak_rss_kib'] = int((folder / f'{mode}.rss-kib').read_text())
    require(result['peak_rss_kib'] > 0, f'Missing peak RSS for {mode}')
    require(len(result['windows']) == len(plan['windows']), f'Missing sampled windows for {mode}')
    for actual, expected_window in zip(result['windows'], plan['windows']):
        require(actual['site'] == expected_window['site'] and len(actual['columns']) == SIDE * SIDE,
                f'Wrong coordinates in {mode}')
        require([column[5] for column in actual['columns']] == expected_window['profiles'],
                f'Incorrect profile evidence in {mode}')
    return result


def is_source(column):
    return column[2] != MISSING and column[4] & WATER_FLAGS != 0


def neighbours(index):
    x, z = index % SIDE, index // SIDE
    if x > 0:
        yield index - 1
    if x < SIDE - 1:
        yield index + 1
    if z > 0:
        yield index - SIDE
    if z < SIDE - 1:
        yield index + SIDE


def shares_water(a, b):
    return min(a[2], b[2]) > max(a[3], b[3])


def connected(columns, selected):
    largest, components = 0, 0
    remaining = set(selected)
    while remaining:
        components += 1
        first = remaining.pop()
        queue = deque([first])
        size = 1
        while queue:
            index = queue.popleft()
            for other in neighbours(index):
                if other not in remaining:
                    continue
                a, b = columns[index], columns[other]
                # Two neighboring columns share water at an equal elevation.
                if not shares_water(a, b):
                    continue
                remaining.remove(other)
                queue.append(other)
                size += 1
        largest = max(largest, size)
    return components, largest


def spillway_metrics(columns, matched):
    lake = {i for i in matched if columns[i][5] & 3 == 3}
    stream = {i for i in matched if not columns[i][5] & 1}
    stage = max((columns[i][2] for i in lake), default=None)
    # An outlet may descend immediately from the lake. Require actual shared
    # wet voxels and a non-increasing stage, not an artificial same-level apron.
    # A dry/solid lip, diagonal-only water or uphill contact still fails.
    contacts = {j for i in lake for j in neighbours(i)
                if j in stream and columns[j][2] <= columns[i][2] and shares_water(columns[i], columns[j])}
    downstream = set(contacts)
    queue = deque(contacts)
    while queue:
        i = queue.popleft()
        for j in neighbours(i):
            if j in stream and j not in downstream and columns[j][2] <= columns[i][2] and shares_water(columns[i], columns[j]):
                downstream.add(j)
                queue.append(j)
    return len(contacts), sum(columns[i][2] < stage for i in downstream)


def window_metrics(window, baseline_window):
    columns, old = window['columns'], baseline_window['columns']
    planned = [i for i, c in enumerate(columns) if c[5]]
    matched = [i for i in planned if is_source(columns[i]) and columns[i][2] == columns[i][5] >> 16]
    levels = [columns[i][2] for i in matched]
    depths = [columns[i][2] - columns[i][3] for i in matched if columns[i][3] != MISSING]
    lakes = [i for i in matched if columns[i][5] & 1]
    site = window['site']
    chunk_edges = [i for i in planned if any(coord % 16 in (0, 15) for coord in (
        site['x'] + i % SIDE - RADIUS, site['z'] + i // SIDE - RADIUS))]
    tile_edges = [i for i in planned if any(coord % 128 in (0, 127) for coord in (
        site['x'] + i % SIDE - RADIUS, site['z'] + i // SIDE - RADIUS))]
    matched_set = set(matched)
    components, largest = connected(columns, matched)
    spillway_contacts, downstream = spillway_metrics(columns, matched)
    dry_bank_neighbours = {(i, j) for i in matched for j in neighbours(i)
                           if columns[j][2] == MISSING and columns[j][1] < columns[i][2]}
    return {
        **site,
        'planned_columns': len(planned),
        'matching_source_or_ice_columns': len(matched),
        'above_sea_columns': sum(y > 62 for y in levels),
        'water_columns_before': sum(c[2] != MISSING for c in old),
        'water_columns_after': sum(c[2] != MISSING for c in columns),
        'matched_lake_columns': len(lakes),
        'matched_lake_levels': sorted({columns[i][2] for i in lakes}),
        'minimum_level': min(levels, default=None),
        'maximum_level': max(levels, default=None),
        'minimum_depth': min(depths, default=None),
        'maximum_depth': max(depths, default=None),
        'unsupported_bed_columns': sum(bool(columns[i][4] & 4) for i in matched),
        'near_bed_lava_columns': sum(bool(columns[i][4] & 8) for i in matched),
        'water_connected_components': components,
        'largest_water_component_columns': largest,
        'wet_spillway_contacts': spillway_contacts,
        'connected_lower_spillway_columns': downstream,
        'possible_exposed_bank_edges': len(dry_bank_neighbours),
        'planned_chunk_edge_columns': len(chunk_edges),
        'matched_chunk_edge_columns': len(matched_set.intersection(chunk_edges)),
        'planned_tile_edge_columns': len(tile_edges),
        'matched_tile_edge_columns': len(matched_set.intersection(tile_edges)),
        'dry_river_biome_before': sum(c[2] == MISSING and bool(c[4] & RIVER_BIOME) for c in old),
        'dry_river_biome_after': sum(c[2] == MISSING and bool(c[4] & RIVER_BIOME) for c in columns),
    }


def hydrology_signature(result):
    return [[(i, c[2], c[3], c[4] & 15, w['modelGround'][i]) if c[5] else (i, w['modelGround'][i])
             for i, c in enumerate(w['columns']) if c[5] or any(w['columns'][j][5] for j in neighbours(i))]
            for w in result['windows']]


def compare_normal(references, candidate):
    """Compare two fresh vanilla controls and the candidate with the same
    feature order. Independently measure vanilla's sensitivity to reversing
    only the order of feature writes in a third fresh vanilla world."""
    require(len(references) == 3, 'Three ordinary-world references required')
    runs = [*references, candidate]
    require([r['feature_order'] for r in runs] == ['forward', 'forward', 'reverse', 'forward'],
            'Missing paired feature schedules')
    terrain = candidate['terrain_before_features']
    require([w['site'] for w in candidate['windows']] == [w['site'] for w in terrain],
            'Missing ordinary pre-feature sample')
    for window in terrain:
        fingerprint(window['blocksSha256'])
        fingerprint(window['biomesSha256'])
    require(all(r['terrain_before_features'] == terrain for r in references),
            'Ordinary terrain blocks or stored biomes changed before features')
    varied = total = 0
    order_examples = []
    for wi, actual in enumerate(candidate['windows']):
        reference, repeat, reverse = (r['windows'][wi] for r in references)
        for other in (reference, repeat, reverse):
            require(other['site'] == actual['site'], 'Ordinary Minecraft coordinates changed')
            require(other['nativeGeometrySha256'] == actual['nativeGeometrySha256'],
                    'Ordinary native terrain/water geometry changed')
        expected = full_states(reference)
        repeated = full_states(repeat)
        backwards = full_states(reverse)
        observed = full_states(actual)
        for index, (first, second, alternative, state) in enumerate(zip(expected, repeated, backwards, observed)):
            require(first == second, f'Vanilla repeat changed at window {wi}, block {index}: {first} -> {second}')
            require(first == state, f'Ordinary FULL regression at window {wi}, block {index}: {first} -> {state}')
            if first != alternative:
                varied += 1
                if len(order_examples) < 24:
                    col, y = divmod(index, 384)
                    site = actual['site']
                    order_examples.append({
                        'x': site['x'] + col % SIDE - RADIUS, 'y': y - 64,
                        'z': site['z'] + col // SIDE - RADIUS, 'forward': first, 'reverse': alternative})
        for field in ('columns', 'surfaceBiomes'):
            require(reference[field] == repeat[field] == actual[field], f'Ordinary FULL {field} changed')
        total += len(actual['fullBlockStates'])
    return {'sampled_full_blocks': total, 'vanilla_feature_order_variable_blocks': varied,
            'vanilla_order_difference_examples': order_examples, 'candidate_changed_full_blocks': 0,
            'pre_feature_blocks_and_biomes_exact': True, 'native_terrain_water_geometry_exact': True}


def full_states(window):
    palette, states = window['blockPalette'], window['fullBlockStates']
    require(palette and all(isinstance(s, str) and s for s in palette)
            and len(set(palette)) == len(palette), 'Invalid normal block palette')
    require(len(states) == SIDE * SIDE * 384, 'Incomplete ordinary FULL block evidence')
    require(all(type(v) is int and 0 <= v < len(palette) for v in states), 'Invalid block state index')
    return (palette[index] for index in states)


def compare(folder, sha, seed, *, require_dh=False):
    folder = Path(folder)
    plan = read_json(folder / 'plan.json')
    require(plan['seed'] == seed and len(plan['windows']) >= 4, 'Insufficient hydrology coverage')
    modes = (*MODES, 'distant-horizons') if require_dh else MODES
    observed = {mode: load(folder, seed, mode, plan) for mode in modes}
    candidate = observed['candidate']
    reference = observed['baseline']
    for mode in ('reverse', 'parallel', 'reload'):
        require(hydrology_signature(candidate) == hydrology_signature(observed[mode]),
                f'Water, bed, or seal changed after {mode}')
    normal_control = compare_normal([observed[m] for m in VANILLA_MODES], observed['normal-candidate'])
    if require_dh:
        dh = observed['distant-horizons']
        require(dh['distant_horizons'] == '3.3.4', 'Distant Horizons was not loaded')
        require(dh['distant_horizons_full_chunks'] >= candidate['full_chunks'],
                'Distant Horizons did not generate all sampled FULL chunks')
        require(hydrology_signature(candidate) == hydrology_signature(dh),
                'Hydrology changed with Distant Horizons installed')
    rows = [window_metrics(a, b) for a, b in zip(candidate['windows'], reference['windows'])]
    require(any(r['kind'] == 'mountain-river' and r['above_sea_columns'] >= 24 for r in rows),
            'No observed mountain river above sea level')
    require(any(r['kind'] == 'coastal-river' and r['matching_source_or_ice_columns'] >= 24 for r in rows),
            'No observed coastal river')
    require(any(r['matched_lake_columns'] >= 24 and r['maximum_depth'] >= 2 for r in rows),
            'No observed lake with depth')
    for row in rows:
        planned = row['planned_columns']
        require(planned >= 24 and row['matching_source_or_ice_columns'] >= 0.97 * planned,
                f"Missing water in {row['kind']}")
        require(row['unsupported_bed_columns'] == row['near_bed_lava_columns'] == 0,
                f"Unsafe bed in {row['kind']}")
        require(row['possible_exposed_bank_edges'] == 0, f"Unsealed bank in {row['kind']}")
    require(any(r['matched_tile_edge_columns'] > 0 for r in rows), 'No observed water across a tile edge')
    return {
        'source_commit': sha, 'seed': seed, 'actual_server_runs': len(modes),
        'full_chunks_per_run': candidate['full_chunks'],
        'chunk_generation_ms': {mode: result['chunk_generation_ms'] for mode, result in observed.items()},
        'peak_rss_kib': {mode: result['peak_rss_kib'] for mode, result in observed.items()},
        'water_matches_reverse_parallel_and_reload': True,
        'normal_world_control': normal_control,
        'distant_horizons_installed_equal': require_dh,
        'distant_horizons_generated_full_chunks': observed['distant-horizons']['distant_horizons_full_chunks'] if require_dh else 0,
        'observations_sha256': hashlib.sha256((folder / 'candidate.json').read_bytes()).hexdigest(),
        'windows': rows,
    }


if __name__ == '__main__':
    folder, sha, seed = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
    report = compare(folder, sha, seed, require_dh=True)
    (folder / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'FRESHGENERATOR_HYDROLOGY_MATRIX_PASS {seed}')
