"""Publish only after unit tests and every isolated native-server scenario passed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

version = '0.1.0-alpha.1'
jar = Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar')
sources = Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}-sources.jar')
with zipfile.ZipFile(jar) as z:
    names = z.namelist()
    assert len(names) == len(set(names)), 'duplicate jar entries'
    assert 'dev/freshgenerator/core/TerrainProgram.class' in names
    assert 'dev/freshgenerator/FreshGenerator.class' in names
    assert not any(n.startswith(('net/minecraft/', 'net/fabricmc/', 'dev/freshgenerator/smoke/', 'dev/freshgenerator/pack/')) for n in names)
    assert all(b'sun/misc/Unsafe' not in z.read(n) for n in names if n.endswith('.class'))
    assert json.loads(z.read('fabric.mod.json'))['version'] == version
    manifest = json.loads(z.read('freshgenerator-worldgen-manifest.json'))
    assert manifest['minecraft'] == '26.3' and len(manifest['source_sha256']) > 10
    for preset in ['balanced_v1', 'highlands_v1']:
        assert f'data/freshgenerator/worldgen/world_preset/{preset}.json' in names
        assert f'data/freshgenerator/worldgen/noise_settings/{preset}.json' in names
    native = [n for n in names if n.startswith('data/minecraft/') and not n.endswith('/')]
    assert native == ['data/minecraft/tags/worldgen/world_preset/normal.json'], native
    tag = json.loads(z.read(native[0]))
    assert tag['replace'] is False
    for n in names:
        for registry in ['biome', 'structure', 'structure_set', 'placed_feature', 'feature']:
            assert f'/worldgen/{registry}/' not in n, 'rarity registry override: ' + n
with zipfile.ZipFile(sources) as z:
    assert 'dev/freshgenerator/core/TerrainProgram.java' in z.namelist()
    assert 'dev/freshgenerator/pack/WorldgenCompiler.java' in z.namelist()
suites = [ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
assert suites
assert all(int(s.get(k, 0)) == 0 for s in suites for k in ['errors', 'failures', 'skipped'])
total = sum(int(s.get('tests', 0)) for s in suites)
assert total >= 27
labels = ['balanced-new', 'balanced-reload', 'other-seed', 'highlands-new']
reports = {}
for label in labels:
    directory = Path('build/reports/integration')
    assert 'FRESHGENERATOR_WORLD_PASS' in (directory / f'{label}.log').read_text()
    reports[label] = json.loads((directory / f'{label}.json').read_text())
a, b, c, d = [reports[label] for label in labels]
assert a['climate_sha256'] == b['climate_sha256']
assert a['block_sha256'] == b['block_sha256']
assert a['climate_sha256'] != c['climate_sha256']
assert a['climate_sha256'] != d['climate_sha256']
assert all(r['full_chunks_checked'] == 5 and r['core_vs_native_max_error'] <= .01 for r in reports.values())
report = {
    'version': version,
    'source_commit': os.environ['GITHUB_SHA'],
    'run_url': f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    'unit_tests_passed': total,
    'actual_dedicated_server': 'passed',
    'native_registry_loading': 'passed',
    'save_reload_terrain_hash': 'passed',
    'world_seed_affects_generation': 'passed',
    'core_vs_native_graph': 'passed',
    'full_chunks_examined_per_run': 5,
    'client_render_test': 'NOT PERFORMED',
    'global_biome_and_structure_rarity_statistics': 'NOT MEASURED',
    'user_modpack_and_distant_horizons': 'NOT TESTED',
    'performance_improvement': 'NOT MEASURED',
    'giant_trees_custom_hydrology_editor_ui': 'NOT IMPLEMENTED',
    'generation_manifest': manifest,
    'world_scenarios': reports,
    'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
}
dest = Path('dist'); dest.mkdir(exist_ok=True)
for source in [jar, sources]:
    shutil.copyfile(source, dest / source.name)
    (dest / (source.name + '.sha256')).write_text(hashlib.sha256(source.read_bytes()).hexdigest() + '  ' + source.name + '\n')
(dest / f'validation-{version}.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['generation_manifest', 'world_scenarios']}, indent=2))
