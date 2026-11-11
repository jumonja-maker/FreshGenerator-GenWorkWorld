"""Publish only after single-world selection tests, native server and compatibility gates passed."""
import hashlib,json,os,shutil,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
version='0.2.1-alpha.1'
jar=Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar')
sources=Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}-sources.jar')
with zipfile.ZipFile(jar) as z:
    names=z.namelist(); assert len(names)==len(set(names))
    for cls in ['TerrainProgram','NaturalTerrainProgram']:
        assert f'dev/freshgenerator/core/{cls}.class' in names
    assert not any(n.startswith(('net/minecraft/','net/fabricmc/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/')) for n in names)
    assert all(b'sun/misc/Unsafe' not in z.read(n) for n in names if n.endswith('.class'))
    assert json.loads(z.read('fabric.mod.json'))['version']==version
    manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'))
    assert manifest['minecraft']=='26.3' and manifest['format']==2
    assert manifest['selectable_world_presets']==['freshgenerator:world']
    for preset in ['balanced_v1','highlands_v1','balanced_v2','highlands_v2']:
        for registry in ['world_preset','noise_settings']:
            assert f'data/freshgenerator/worldgen/{registry}/{preset}.json' in names
    public=json.loads(z.read('data/freshgenerator/worldgen/world_preset/world.json'))
    assert public==json.loads(z.read('data/freshgenerator/worldgen/world_preset/highlands_v2.json'))
    native=[n for n in names if n.startswith('data/minecraft/') and not n.endswith('/')]
    assert native==['data/minecraft/tags/worldgen/world_preset/normal.json']
    assert json.loads(z.read(native[0]))=={'replace':False,'values':['freshgenerator:world']}
    for locale in ['en_us','ru_ru']:
        assert json.loads(z.read(f'assets/freshgenerator/lang/{locale}.json'))=={'generator.freshgenerator.world':'FreshGenerator'}
    for n in names:
        for registry in ['biome','structure','structure_set','placed_feature','feature','multi_noise_biome_source_parameter_list']:
            assert f'/worldgen/{registry}/' not in n, 'rarity registry override: '+n
    legacy_counts={}
    for old_version in ['0.1.0-alpha.1','0.2.0-alpha.1']:
        legacy=0
        with zipfile.ZipFile(f'dist/FreshGenerator-Fabric-26.3-{old_version}.jar') as old:
            for n in old.namelist():
                if n.startswith('data/freshgenerator/') and n.endswith('.json'):
                    assert json.loads(old.read(n))==json.loads(z.read(n)), 'Legacy resource changed: '+n
                    legacy+=1
        legacy_counts[old_version]=legacy
    assert legacy_counts=={'0.1.0-alpha.1':144,'0.2.0-alpha.1':322}
with zipfile.ZipFile(sources) as z:
    assert 'dev/freshgenerator/core/NaturalTerrainProgram.java' in z.namelist()
    assert 'dev/freshgenerator/pack/NaturalWorldgenCompiler.java' in z.namelist()
    assert 'dev/freshgenerator/pack/WorldgenCatalog.java' in z.namelist()
suites=[ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['errors','failures','skipped'])
total=sum(int(s.get('tests',0)) for s in suites); assert total>=56
labels=['balanced-new','balanced-reload','other-seed','highlands-new','natural-new','natural-other-seed','world-new','world-reload']
reports={}
p=Path('build/reports/integration')
for label in labels:
    assert 'FRESHGENERATOR_WORLD_PASS' in (p/f'{label}.log').read_text()
    reports[label]=json.loads((p/f'{label}.json').read_text())
old_report=json.loads(Path('dist/validation-0.1.0-alpha.1.json').read_text())
for label in labels[:4]:
    assert reports[label]['climate_sha256']==old_report['world_scenarios'][label]['climate_sha256']
for key in ['block_sha256','climate_sha256']:
    assert reports['world-new'][key]==reports['world-reload'][key]
for label in ['world-new','world-reload']:
    assert 'level-type=freshgenerator:world\n' in (p/f'{label}.properties').read_text()
    assert reports[label]['settings']=='freshgenerator:highlands_v2'
for label in labels[4:]:
    r=reports[label];assert r['core_vs_native_max_error']<=.02 and r['unchanged_climate_axis_samples']==2560
    assert r['full_chunks_checked']>=9 and r['distinct_base_heights']>=3
legacy_audit=json.loads((p/'legacy-comparison.json').read_text())
assert legacy_audit['full_block_differences']==0 and legacy_audit['full_blocks_compared']==491520
assert legacy_audit['base_height_columns_equal']==1280
report={
    'version':version,'source_commit':os.environ['GITHUB_SHA'],
    'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    'unit_tests_passed':total,'actual_dedicated_server_runs':len(reports)+1,
    'selectable_freshgenerator_worlds':['freshgenerator:world'],
    'public_display_name':'FreshGenerator',
    'single_public_world_create_and_reload':'passed',
    'legacy_worlds':'hidden compatibility only; no separate menu modes',
    'historical_resources_unchanged':legacy_counts,
    'historical_full_chunk_hash_comparison':json.loads((p/'historical-v1-comparison.json').read_text()),
    'legacy_historical_source_AB':legacy_audit,
    'single_world_save_reload_full_chunks':'passed','core_vs_native_graph':'passed',
    'climate_axes_vs_v1':'2560 identical sampled values per natural-terrain run; not a global rarity proof',
    'native_heightmaps':'289 columns near a sampled mountain per natural-terrain run',
    'terrain_algorithm_changes_in_this_release':'none; current terrain improvements consolidated under one public world type',
    'client_menu_visual_test':'NOT PERFORMED; generated selection tags and translations checked',
    'client_render_and_visual_aesthetics':'NOT VERIFIED',
    'universal_biome_material_or_grass_color_blending':'NOT IMPLEMENTED',
    'physical_erosion_and_river_drainage':'NOT IMPLEMENTED',
    'global_biome_structure_rarity_statistics':'NOT MEASURED',
    'user_modpack_and_distant_horizons':'NOT TESTED',
    'performance_improvement':'NOT MEASURED',
    'generation_manifest':manifest,'world_scenarios':reports,
    'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()
}
dest=Path('dist');dest.mkdir(exist_ok=True)
for source in [jar,sources]:
    shutil.copyfile(source,dest/source.name)
    (dest/(source.name+'.sha256')).write_text(hashlib.sha256(source.read_bytes()).hexdigest()+'  '+source.name+'\n')
(dest/f'validation-{version}.json').write_text(json.dumps(report,indent=2)+'\n')
shutil.copyfile('build/reports/integration/world-new.csv',dest/f'world-heightmap-{version}.csv')
print(json.dumps({k:v for k,v in report.items() if k not in ['generation_manifest','world_scenarios']},indent=2))
