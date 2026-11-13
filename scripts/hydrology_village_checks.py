"""Hydrology-specific paired village gate; historical village gates remain unchanged.

Compare fixed original-elevation coordinates, keep deep blocks byte-identical, and
permit outside TERRAIN changes only in the measured local excavation/water envelope.
Never waive door, road, farmland, foundation, layout or saved-world safety checks.
"""
import hashlib
import json
import math
import struct
import sys
from pathlib import Path
from village_checks import BASE, PUBLISHED, COUNTS, FULL_COUNTS, counts, fingerprint, identity, require


def number(bits):
    require(type(bits) is int and -(1 << 31) <= bits < (1 << 31), 'Invalid float bits')
    value = struct.unpack('!f', struct.pack('!i', bits))[0]
    require(math.isfinite(value), 'Nonfinite height')
    return value


def decode_runs(runs, palette, size):
    require(len(runs) % 2 == 0, 'Malformed block runs')
    states, previous = [], -1
    for length, index in zip(runs[::2], runs[1::2]):
        require(type(length) is int and 0 < length <= size - len(states), 'Invalid block run length')
        require(type(index) is int and 0 <= index < len(palette) and index != previous,
                'Invalid block run palette index')
        states.extend([palette[index]] * length)
        previous = index
    require(len(states) == size, 'Incomplete block column')
    return states


def outside_rows(report):
    palette, rows = report['outside_palette'], report['outside_columns']
    require(isinstance(palette, list) and len(set(palette)) == len(palette), 'Invalid outside palette')
    require(all(isinstance(s, str) and s for s in palette), 'Missing outside block state')
    require(isinstance(rows, list) and rows, 'Missing outside columns')
    seen, result, digest = set(), {}, hashlib.sha256()
    for row in rows:
        require(7 <= len(row) <= 389 and len(row) % 2 == 1 and all(type(v) is int for v in row),
                'Malformed outside column')
        x, z, original, current, profile = row[:5]
        require((x, z) not in seen, 'Duplicate outside coordinate')
        seen.add((x, z))
        number(original); number(current)
        states = decode_runs(row[5:], palette, 192)
        for i, state in enumerate(states):
            digest.update(f'{x},{-64+2*i},{z}={state}\n'.encode())
        result[x, z] = (original, current, profile, states)
    require(digest.hexdigest() == report['outside_sha256'], 'Outside snapshot/hash mismatch')
    return result


def full_rows(report):
    palette, rows = report['full_palette'], report['full_columns']
    require(palette and all(isinstance(s, str) and s for s in palette) and len(set(palette)) == len(palette),
            'Invalid FULL block palette')
    require(isinstance(rows, list) and rows, 'Missing FULL columns')
    result, digest = {}, hashlib.sha256()
    for x, z, *runs in rows:
        require(type(x) is int and type(z) is int and (x, z) not in result, 'Invalid FULL coordinates')
        states = decode_runs(runs, palette, 384)
        digest.update(('\n'.join(states) + '\n').encode())
        result[x, z] = states
    require(digest.hexdigest() == report['full_sha256'], 'FULL snapshot/hash mismatch')
    return result


def check_reload(before, after):
    old, new = full_rows(before), full_rows(after)
    require(old.keys() == new.keys(), 'FULL sampling coordinates changed on reload')
    for (x, z), states in old.items():
        for y, (a, b) in enumerate(zip(states, new[x, z]), -64):
            require(a == b, f'FULL village block changed on reload at {x},{y},{z}: {a} -> {b}')
    metadata = {'full_columns', 'full_palette'}
    require({k: v for k, v in before.items() if k not in metadata} ==
            {k: v for k, v in after.items() if k not in metadata}, 'FULL village measurements changed on reload')


def street_columns(report):
    rows = report['street_path_columns']
    require(isinstance(rows, list) and all(isinstance(row, list) and len(row) == 2
            and all(type(v) is int for v in row) for row in rows), 'Invalid native street stencil')
    coordinates = [tuple(row) for row in rows]
    require(coordinates == sorted(set(coordinates)), 'Noncanonical native street stencil')
    digest = hashlib.sha256(''.join(f'{x},{z}\n' for x, z in coordinates).encode()).hexdigest()
    require(digest == report['street_path_sha256'], 'Native street stencil/hash mismatch')
    return set(coordinates)


def road_columns(report):
    for field in ('bridge_blocks', 'natural_road_blocks'):
        require(type(report[field]) is int and report[field] >= 0, 'Invalid road surface count')
    stencil = street_columns(report)
    bridges_allowed = {'minecraft:oak_planks', 'minecraft:acacia_planks', 'minecraft:spruce_planks'}
    patches_allowed = {'minecraft:grass_block', 'minecraft:gravel', 'minecraft:sand'}
    states = {'minecraft:dirt_path'} | bridges_allowed | patches_allowed
    walkability = {}
    for row in report['road_walkability']:
        require(isinstance(row, list) and len(row) == 7 and all(type(v) is int for v in row[:3])
                and all(type(v) is bool and v for v in row[3:]), 'Unsafe or unobserved processed road')
        key = tuple(row[:3])
        require(key not in walkability, 'Duplicate processed road evidence')
        walkability[key] = row[3:]
    result, paths, bridges, patches, alternatives = set(), 0, 0, 0, set()
    for x, y, z, block in report['road_surface']:
        require(all(type(v) is int for v in (x, y, z)) and block in states, 'Invalid village road block')
        require((x, z) not in result, 'Overlapping village road surface')
        result.add((x, z))
        paths += block == 'minecraft:dirt_path'
        bridges += block in bridges_allowed
        patches += block in patches_allowed
        if block != 'minecraft:dirt_path':
            require((x, z) in stencil, 'Processed road is outside the native DIRT_PATH stencil')
            require((x, y, z) in walkability, 'Missing processed road walkability')
            alternatives.add((x, y, z))
    require(alternatives == walkability.keys(), 'Extraneous processed road evidence')
    require(paths <= report['path_blocks'] and bridges == report['bridge_blocks']
            and patches == report['natural_road_blocks'], 'Road inventory/count mismatch')
    return result


def compare_outside(before, after):
    old, new = outside_rows(before), outside_rows(after)
    require(old.keys() == new.keys(), 'Outside sampling coordinates changed')
    changed, examples = 0, []
    for position in old:
        a, b = old[position], new[position]
        require(a[0] == b[0], 'Original elevation reference changed')
        h0, h1 = number(b[0]), number(b[1])
        delta = h0 - h1
        require(-2.251 <= delta <= 18.001, 'Unbounded hydrology height correction')
        profile = b[2]
        top = (profile >> 16) if profile else h1
        # Native vertical density interpolation can span a four-block cell.
        bottom, ceiling = math.floor(min(h0, h1)) - 16, math.ceil(max(h0, h1, top)) + 2
        for index, (first, actual) in enumerate(zip(a[3], b[3])):
            if first == actual:
                continue
            y = -64 + 2 * index
            require((delta != 0 or profile != 0) and bottom <= y <= ceiling,
                    f'Unmodeled outside change at {position[0]},{y},{position[1]}: {first} -> {actual}'
                    f' (original={h0}, current={h1}, water={profile}, band={bottom}..{ceiling})')
            changed += 1
            if len(examples) < 16:
                examples.append({'x': position[0], 'y': y, 'z': position[1], 'before': first,
                                 'after': actual, 'old_height': h0, 'new_height': h1, 'water_profile': profile})
    return {'columns': len(old), 'changed_blocks_inside_hydrology_envelope': changed,
            'unmodeled_changes': 0, 'examples': examples}


def validate(baseline, published, candidate, reload, sha, seed):
    for data, phase, source in ((baseline, 'baseline', BASE), (published, 'published', PUBLISHED),
                                (candidate, 'candidate', sha), (reload, 'reload', sha)):
        require(data['completed'] is True and data['seed'] == seed and data['phase'] == phase
                and data['source_commit'] == source, 'Wrong/incomplete village evidence')
        require(data['hydrology_observer_schema'] == 4, 'Missing hydrology-aware raw evidence')
        require(data['settings'] == 'freshgenerator:highlands_v2', 'Wrong village world')
        require(type(data['explicit_full_chunks']) is int and data['explicit_full_chunks'] > 0, 'No FULL village chunks')
        require(len(data['villages']) == len(data['terrain']) >= 3, 'Missing village coverage')
        require(len({(v['id'],v['chunkX'],v['chunkZ']) for v in data['villages']}) == len(data['villages']), 'Duplicate village')
    require(len(baseline['villages']) == len(published['villages']) == len(candidate['villages']) == len(reload['villages']),
            'Unpaired village coverage')
    for before, after in zip(candidate['villages'], reload['villages']):
        check_reload(before, after)
    require(any(v['id'] == 'minecraft:village_savanna' for v in candidate['villages']), 'No savanna coverage')
    terrain_checks = []
    for base, old, new in zip(baseline['terrain'], published['terrain'], candidate['terrain']):
        require(identity(base) == identity(old) == identity(new), 'Native structure layout changed')
        for row in (base, old, new):
            counts(row, COUNTS)
            require(row['predecorated_columns_not_used'] == 0, 'Terrain snapshot already decorated')
            fingerprint(row['deep_sha256']); fingerprint(row['outside_sha256'])
        require(base['deep_sha256'] == old['deep_sha256'] == new['deep_sha256'], 'Deep blocks changed at fixed coordinates')
        require(old['terrain_columns'] == new['terrain_columns'] > 0, 'Terrain sample count changed')
        require(new['protected_roof_columns'] >= old['protected_roof_columns'] >= 50, 'Protected roof coverage lost')
        require(new['rigid_columns'] == old['rigid_columns'] > 0, 'Rigid plots changed')
        require(new['roof_failure_columns'] == new['open_hole_columns'] == 0, 'Village roof or surface breached')
        require(new['rigid_grade_error_columns'] <= old['rigid_grade_error_columns'], 'Village grades regressed')
        terrain_checks.append(compare_outside(old, new))
    for old, new, terrain in zip(published['villages'], candidate['villages'], candidate['terrain']):
        require(identity(old) == identity(new) == identity(terrain), 'FULL village layout changed')
        counts(old, FULL_COUNTS); counts(new, FULL_COUNTS)
        fingerprint(old['full_sha256']); fingerprint(new['full_sha256'])
        require(new['doors'] == old['doors'] > 0, 'Lost village doors')
        require(street_columns(old) == street_columns(new), 'Native street stencil changed')
        old_roads, new_roads = road_columns(old), road_columns(new)
        missing_roads = old_roads - new_roads
        require(not missing_roads, f'Lost native village road columns: {sorted(missing_roads)[:16]}')
        require(new['farmland_blocks'] >= old['farmland_blocks'], 'Lost native village farmland')
        for field in ('path_columns_with_void_within_8', 'farmland_columns_with_void_within_8', 'door_exit_warnings'):
            require(new[field] <= old[field], 'Village safety regressed: ' + field)
        for field in ('protected_path_air_or_lava_columns', 'protected_farm_air_or_lava_columns', 'protected_floor_lava_columns'):
            require(new[field] == 0, 'Late feature breached village: ' + field)
    for field in ('path_blocks', 'farmland_blocks'):
        require(sum(v[field] for v in published['villages']) > 0, 'Missing native coverage: ' + field)
    return {'source_commit': sha, 'reference_commit': PUBLISHED, 'seed': seed,
            'actual_server_runs': 4, 'villages': len(candidate['villages']),
            'full_before': [summarize_full(v) for v in published['villages']],
            'full_after': [summarize_full(v) for v in candidate['villages']],
            'saved_world_equal': True, 'deep_blocks_identical_at_fixed_coordinates': True,
            'outside_terrain': terrain_checks,
            'limits': 'Paired real villages; changed outside blocks are permitted only in the independently recorded hydrology envelope. FULL decorative blocks outside protected plots may change with the new landscape. No villager AI test.'}


def summarize_full(report):
    return {k: v for k, v in report.items() if k not in {'full_palette', 'full_columns', 'road_surface', 'road_walkability', 'street_path_columns'}}


def compare(folder, sha, seed):
    folder = Path(folder)
    modes = ('baseline', 'published', 'candidate', 'reload')
    for mode in modes:
        require('FRESHGENERATOR_VILLAGE_AUDIT_PASS' in (folder / (mode + '.log')).read_text(), 'Village server failed: ' + mode)
    data = [json.loads((folder / (mode + '.json')).read_text()) for mode in modes]
    result = validate(*data, sha, seed)
    (folder / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    print('FRESHGENERATOR_HYDROLOGY_VILLAGE_PASS', seed)
    return result


if __name__ == '__main__':
    compare(sys.argv[1], sys.argv[2], int(sys.argv[3]))
