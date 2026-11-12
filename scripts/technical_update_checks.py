"""Fail-closed equality checks for a technical (not worldgen-changing) release."""
import hashlib
import json
import re
from pathlib import Path
from biome_region_metrics import decode

BASE_COMMIT = '8bb647170ed20390fc8ae3b9e05017fbd921aa61'
SCENARIOS = (('fresh', 42), ('fresh', 1337), ('fresh', -20261111), ('normal', 42))

def require(value, message):
    if not value:
        raise ValueError(message)

def digest(value):
    require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value), 'Missing/invalid snapshot hash')

def compare(a, b, c, source_commit, scope, seed):
    require((scope, seed) in SCENARIOS, 'Unexpected scenario')
    require(a['source_commit'] == BASE_COMMIT, 'Not the published 0.3.2 reference')
    require(b['source_commit'] == c['source_commit'] == source_commit, 'Stale candidate report')
    expected = 'freshgenerator:highlands_v2' if scope == 'fresh' else 'minecraft:overworld'
    for report, mode, label in ((a, 'reference', 'reference'), (b, 'candidate', 'candidate'), (c, 'candidate', 'candidate-reload')):
        require(report['completed'] is True, 'Incomplete server run')
        require(report['mode'] == mode and report['pass'] == label, 'Wrong server phase')
        require(report['seed'] == seed and report['settings'] == expected, 'Wrong world/seed')
        digest(report['observer_sha256'])
    require(a['observer_sha256'] == b['observer_sha256'] == c['observer_sha256'], 'Observers differ')
    require(b['maps'] == c['maps'], 'Candidate maps changed after reload')
    require(len(a['maps']) == len(b['maps']) == (2 if scope == 'fresh' else 0), 'Missing maps')
    samples = 0
    for old, new in zip(a['maps'], b['maps']):
        for key in ('name', 'x', 'z', 'side', 'step', 'geography_sha256', 'deep_sha256', 'surface_climate_sha256'):
            require(old[key] == new[key], 'Changed map field: ' + key)
        for key in ('geography_sha256', 'deep_sha256', 'surface_climate_sha256'):
            digest(old[key])
        require(decode(old) == decode(new), 'Biome IDs changed')
        samples += len(decode(old))
    require(a['full_windows'] == b['full_windows'] == c['full_windows'], 'FULL blocks, water, biomes or reload differ')
    require(len(b['full_windows']) >= (4 if scope == 'fresh' else 1), 'Insufficient FULL windows')
    columns = 0
    for window in b['full_windows']:
        require(window['sample_columns'] == 256, 'Wrong column sampling')
        for key in ('base_geometry_water_sha256', 'sampled_full_blocks_sha256'):
            digest(window[key])
        require(sum(window['biomes'].values()) == 256, 'Missing stored biome samples')
        columns += window['sample_columns']
    for report in (a, b, c):
        require(report['stored_biome_checks'] == columns, 'Stored biome check count mismatch')
        require(report['explicit_full_chunks'] > 0, 'No FULL chunks')
    # Telemetry is observational, enabled identically in both fresh runs.
    for name in ('forest_telemetry', 'cave_telemetry'):
        require(a[name] == b[name], 'Runtime decisions/counts changed: ' + name)
        require(all(type(v) is int and v >= 0 for v in b[name].values()), 'Bad telemetry')
    forest = sum(v for k, v in b['forest_telemetry'].items() if k.endswith('_attempts') and not k.endswith('_opening_attempts'))
    if scope == 'fresh':
        require(forest > 0 and b['cave_telemetry']['guard_instances'] > 0, 'Updated runtime paths were not exercised')
    else:
        require(not any(b['forest_telemetry'].values()) and not any(b['cave_telemetry'].values()), 'Policy leaked to ordinary Minecraft')
    return {'source_commit': source_commit, 'reference_commit': BASE_COMMIT, 'seed': seed, 'scope': scope,
            'actual_server_runs': 3, 'native_map_samples_per_version': samples,
            'stored_biome_columns_per_version': columns, 'full_block_states_hashed_per_version': columns * 384,
            'explicit_full_chunks_per_pass': b['explicit_full_chunks'],
            'worldgen_maps_equal': True, 'full_sample_equal': True, 'saved_world_equal': True,
            'runtime_decisions_equal': True, 'forest_attempts': forest,
            'forest_telemetry': b['forest_telemetry'], 'cave_telemetry': b['cave_telemetry'],
            'observer_sha256': b['observer_sha256'],
            'limitations': 'Fixed maps and sampled FULL columns, not an exhaustive client/modpack or performance test.'}

def compare_folder(folder, source_commit, scope, seed):
    folder = Path(folder)
    reports = [json.loads((folder / (label + '.json')).read_text()) for label in ('reference', 'candidate', 'candidate-reload')]
    for label in ('reference', 'candidate', 'candidate-reload'):
        require('FRESHGENERATOR_BIOME_REGION_WORLD_PASS' in (folder / (label + '.log')).read_text(), 'Missing actual server completion')
    result = compare(*reports, source_commit, scope, seed)
    result['input_sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.glob('*.json') if p.name != 'comparison.json'}
    (folder / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    return result

if __name__ == '__main__':
    import sys
    print('FRESHGENERATOR_TECHNICAL_PAIR_PASS ' + json.dumps(compare_folder(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]))))
