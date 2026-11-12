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
        states, previous = [], -1
        for length, index in zip(row[5::2], row[6::2]):
            require(0 < length <= 192 - len(states), 'Invalid outside run length')
            require(0 <= index < len(palette) and index != previous, 'Invalid outside run palette index')
            states.extend([palette[index]] * length)
            previous = index
        require(len(states) == 192, 'Incomplete outside column')
        for i, state in enumerate(states):
            digest.update(f'{x},{-64+2*i},{z}={state}\n'.encode())
        result[x, z] = (original, current, profile, states)
    require(digest.hexdigest() == report['outside_sha256'], 'Outside snapshot/hash mismatch')
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
        require(data['hydrology_observer_schema'] == 2, 'Missing hydrology-aware raw evidence')
        require(data['settings'] == 'freshgenerator:highlands_v2', 'Wrong village world')
        require(type(data['explicit_full_chunks']) is int and data['explicit_full_chunks'] > 0, 'No FULL village chunks')
        require(len(data['villages']) == len(data['terrain']) >= 3, 'Missing village coverage')
        require(len({(v['id'],v['chunkX'],v['chunkZ']) for v in data['villages']}) == len(data['villages']), 'Duplicate village')
    require(len(baseline['villages']) == len(published['villages']) == len(candidate['villages']) == len(reload['villages']),
            'Unpaired village coverage')
    require(candidate['villages'] == reload['villages'], 'FULL village changed on reload')
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
        for field in ('path_blocks', 'farmland_blocks'):
            require(new[field] >= old[field], 'Lost native village content: ' + field)
        for field in ('path_columns_with_void_within_8', 'farmland_columns_with_void_within_8', 'door_exit_warnings'):
            require(new[field] <= old[field], 'Village safety regressed: ' + field)
        for field in ('protected_path_air_or_lava_columns', 'protected_farm_air_or_lava_columns', 'protected_floor_lava_columns'):
            require(new[field] == 0, 'Late feature breached village: ' + field)
    for field in ('path_blocks', 'farmland_blocks'):
        require(sum(v[field] for v in published['villages']) > 0, 'Missing native coverage: ' + field)
    return {'source_commit': sha, 'reference_commit': PUBLISHED, 'seed': seed,
            'actual_server_runs': 4, 'villages': len(candidate['villages']),
            'full_before': published['villages'], 'full_after': candidate['villages'],
            'saved_world_equal': True, 'deep_blocks_identical_at_fixed_coordinates': True,
            'outside_terrain': terrain_checks,
            'limits': 'Paired real villages; changed outside blocks are permitted only in the independently recorded hydrology envelope. FULL decorative blocks outside protected plots may change with the new landscape. No villager AI test.'}


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
