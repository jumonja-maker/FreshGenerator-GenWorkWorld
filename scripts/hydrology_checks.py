"""Validate native FULL chunk observations from the hydrology server matrix."""

import hashlib
import json
import sys
from collections import deque
from pathlib import Path

from village_checks import require

SIDE = 33
RADIUS = SIDE // 2
MISSING = -999
WATER_FLAGS = 1 | 2
RIVER_BIOME = 16
MODES = ('candidate', 'baseline', 'reverse', 'parallel', 'reload',
         'normal-baseline', 'normal-repeat-1', 'normal-repeat-2', 'normal-candidate')


def read_json(path):
    return json.loads(path.read_text())


def load(folder, seed, mode, plan):
    result = read_json(folder / f'{mode}.json')
    expected = 'minecraft:overworld' if mode.startswith('normal-') else 'freshgenerator:highlands_v2'
    require(result['seed'] == seed and result['mode'] == mode and result['settings'] == expected,
            f'Incorrect seed, mode, or noise settings for {mode}')
    require(result['full_chunks'] >= 9 and result['chunk_generation_ms'] >= 0,
            f'Missing FULL generation for {mode}')
    require(result['requested_parallel'] == (mode == 'parallel'), f'Wrong generation mode for {mode}')
    require(f'FRESHGENERATOR_HYDROLOGY_WORLD_PASS {mode} {seed}' in (folder / f'{mode}.log').read_text(),
            f'Native server did not complete {mode}')
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
                if min(a[2], b[2]) <= max(a[3], b[3]):
                    continue
                remaining.remove(other)
                queue.append(other)
                size += 1
        largest = max(largest, size)
    return components, largest


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
        'possible_exposed_bank_edges': len(dry_bank_neighbours),
        'planned_chunk_edge_columns': len(chunk_edges),
        'matched_chunk_edge_columns': len(matched_set.intersection(chunk_edges)),
        'planned_tile_edge_columns': len(tile_edges),
        'matched_tile_edge_columns': len(matched_set.intersection(tile_edges)),
        'dry_river_biome_before': sum(c[2] == MISSING and bool(c[4] & RIVER_BIOME) for c in old),
        'dry_river_biome_after': sum(c[2] == MISSING and bool(c[4] & RIVER_BIOME) for c in columns),
    }


def hydrology_signature(result):
    return [[(c[2], c[3], c[4] & (1 | 2 | 4 | 8)) for c in w['columns'] if c[5]] for w in result['windows']]


def normal_signature(result):
    return [(w['site'], w['surfaceBiomes'], [c[1:] for c in w['columns']]) for w in result['windows']]


def compare_normal(references, candidate):
    """No normalized blocks: each admissible value must exist at that exact
    coordinate in three independent runs of the unchanged published JAR."""
    require(len(references) == 3, 'Three ordinary-world references required')
    varied = changed = total = 0
    for wi, actual in enumerate(candidate['windows']):
        controls = [r['windows'][wi] for r in references]
        for old in controls:
            require(old['site'] == actual['site'] and old['surfaceBiomes'] == actual['surfaceBiomes'],
                    'Ordinary Minecraft coordinates or stored biome distribution changed')
            require(old['nativeGeometrySha256'] == actual['nativeGeometrySha256'],
                    'Ordinary native terrain/water geometry changed')
        for ci, col in enumerate(actual['columns']):
            require(tuple(col[1:]) in {tuple(c['columns'][ci][1:]) for c in controls},
                    f'Unobserved ordinary-world column at window {wi}, index {ci}')
        for window in [*controls, actual]:
            palette, states = window['blockPalette'], window['fullBlockStates']
            require(palette and len(set(palette)) == len(palette), 'Invalid normal block palette')
            require(len(states) == SIDE * SIDE * 384, 'Incomplete ordinary FULL block evidence')
            require(all(type(v) is int and 0 <= v < len(palette) for v in states), 'Invalid block state index')
        local_varied = 0
        for index, value in enumerate(actual['fullBlockStates']):
            observed = {c['blockPalette'][c['fullBlockStates'][index]] for c in controls}
            state = actual['blockPalette'][value]
            require(state in observed, f'Unobserved ordinary block at window {wi}, block {index}: {state}')
            local_varied += len(observed) > 1
            changed += state != controls[0]['blockPalette'][controls[0]['fullBlockStates'][index]]
        require(local_varied <= len(actual['fullBlockStates']) // 1000,
                'Ordinary references too variable for a reliable comparison')
        varied += local_varied
        total += len(actual['fullBlockStates'])
    return {'sampled_full_blocks': total, 'native_variable_blocks': varied,
            'candidate_changes_from_first_reference': changed, 'unobserved_candidate_blocks': 0,
            'native_terrain_water_geometry_exact': True, 'all_stable_reference_blocks_exact': True}


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
    normal_control = compare_normal([observed[m] for m in ('normal-baseline', 'normal-repeat-1', 'normal-repeat-2')],
                                    observed['normal-candidate'])
    if require_dh:
        dh = observed['distant-horizons']
        require(dh['distant_horizons'] == '3.3.4', 'Distant Horizons was not loaded')
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
    require(any(r['matched_tile_edge_columns'] > 0 for r in rows), 'No observed water across a tile edge')
    return {
        'source_commit': sha, 'seed': seed, 'actual_server_runs': len(modes),
        'full_chunks_per_run': candidate['full_chunks'],
        'chunk_generation_ms': {mode: result['chunk_generation_ms'] for mode, result in observed.items()},
        'water_matches_reverse_parallel_and_reload': True,
        'normal_world_reference_envelope': normal_control,
        'normal_world_canopy_differences': sum(a[0] != b[0] for old, new in
            zip(observed['normal-baseline']['windows'], observed['normal-candidate']['windows'])
            for a, b in zip(old['columns'], new['columns'])),
        'distant_horizons_installed_equal': require_dh,
        'observations_sha256': hashlib.sha256((folder / 'candidate.json').read_bytes()).hexdigest(),
        'windows': rows,
    }


if __name__ == '__main__':
    folder, sha, seed = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
    report = compare(folder, sha, seed, require_dh=seed == 42)
    (folder / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'FRESHGENERATOR_HYDROLOGY_MATRIX_PASS {seed}')
