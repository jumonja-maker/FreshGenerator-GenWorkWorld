"""Only publish a candidate after its own unit, native, FULL topology and structure checks."""
import hashlib,json,os,shutil,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
version='0.2.3-alpha.1'
jar=Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar')
sources=Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}-sources.jar')
root='data/freshgenerator/worldgen/density_function/highlands_v2/'
allowed={root+'final_density.json',root+'preliminary_surface_level.json'}
changed=[];unchanged=0
with zipfile.ZipFile(jar) as z,zipfile.ZipFile('dist/FreshGenerator-Fabric-26.3-0.2.2-alpha.1.jar') as old:
    names=z.namelist();assert len(names)==len(set(names))
    assert json.loads(z.read('fabric.mod.json'))['version']==version
    assert not any(n.startswith(('net/minecraft/','net/fabricmc/','dev/freshgenerator/audit/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/')) for n in names)
    assert all(b'sun/misc/Unsafe' not in z.read(n) for n in names if n.endswith('.class'))
    tag='data/minecraft/tags/worldgen/world_preset/normal.json'
    assert json.loads(z.read(tag))=={'replace':False,'values':['freshgenerator:world']}
    for locale in ['en_us','ru_ru']:
        assert json.loads(z.read(f'assets/freshgenerator/lang/{locale}.json'))=={'generator.freshgenerator.world':'FreshGenerator'}
    manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'))
    assert manifest['selectable_world_presets']==['freshgenerator:world']
    for n in old.namelist():
        if n.startswith('data/freshgenerator/') and n.endswith('.json'):
            assert n in names,n
            if json.loads(old.read(n))!=json.loads(z.read(n)):changed.append(n)
            else:unchanged+=1
    assert set(changed)==allowed,changed
    for n in names:
        for registry in ['biome','structure','structure_set','placed_feature','feature','multi_noise_biome_source_parameter_list']:
            assert f'/worldgen/{registry}/' not in n,'Placement override: '+n
    for path,digest in manifest['generated_sha256'].items():
        assert hashlib.sha256(z.read(path)).hexdigest()==digest,'Resource fingerprint mismatch: '+path
with zipfile.ZipFile(sources) as z:
    assert 'dev/freshgenerator/pack/CoherentSurfaceCompiler.java' in z.namelist()
suites=[ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['errors','failures','skipped'])
count=sum(int(s.get('tests',0)) for s in suites);assert count>=83,count
p=Path('build/reports/integration');reports={}
labels=['balanced-new','balanced-reload','other-seed','highlands-new','natural-new','natural-other-seed','world-new','world-reload']
for label in labels:
    assert 'FRESHGENERATOR_WORLD_PASS' in (p/f'{label}.log').read_text()
    reports[label]=json.loads((p/f'{label}.json').read_text())
for key in ['block_sha256','climate_sha256']:
    assert reports['world-new'][key]==reports['world-reload'][key],'Save/reload regression: '+key
for label in ['world-new','world-reload']:
    assert 'level-type=freshgenerator:world\n' in (p/f'{label}.properties').read_text().replace(chr(92)+':',':')
    assert reports[label]['settings']=='freshgenerator:highlands_v2'
    assert reports[label]['full_chunks_checked']>=9
    assert reports[label]['core_vs_native_max_error']<=.02
legacy=json.loads((p/'legacy-comparison.json').read_text())
assert legacy['full_block_differences']==0 and legacy['full_blocks_compared']==491520
q=Path('build/reports/technical');audit=json.loads((q/'technical-audit.json').read_text())
assert audit['source_commit']==os.environ['GITHUB_SHA'],'Outdated audit'
assert audit['server_runs']==6 and audit['placement_rules_equal_to_vanilla_controls'] is True
# Preserve raw findings, including unknown border components and door warnings.
audit['generation_or_block_cleanup_changes']=True
audit['automatic_block_cleanup']=False
audit['terrain_change']='bounded coherent exterior, highland support shell, native deep caves'
envelopes={};summary={};before=json.loads(Path('dist/audits/technical-audit.json').read_text())
for seed in [42,1337,-20261111]:
    label=f'fg-{seed}';r=audit['summary'][label]
    envelopes[label]=json.loads((q/f'{label}-envelope.json').read_text())
    assert envelopes[label]['monotone_exterior_density_samples']>10000
    assert envelopes[label]['protected_roof_samples']>0 and envelopes[label]['native_deep_cavity_samples']>0
    assert r['villages_examined']>=1 and r['structures_examined']>=3
    assert r['village_suspended_piece_candidates']==0,'Suspended village candidate: '+label
    assert r['detached_sky_terrain_components']==0,'Detached exterior terrain remains in sampled regions: '+label
    summary[label]={'before':before['summary'][label],'after':r}
report={
    'version':version,'source_commit':os.environ['GITHUB_SHA'],
    'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    'unit_tests_passed':count,'actual_server_runs':len(reports)+1+audit['server_runs'],
    'one_public_world':'freshgenerator:world','current_graph_replaced_resources':changed,
    'unchanged_prior_generation_resources':unchanged,
    'native_envelope_checks':envelopes,'terrain_and_structure_comparison':summary,
    'world_scenarios':reports,'legacy_historical_source_AB':legacy,
    'save_reload':'passed after FULL neighbour halo',
    'no_floating_terrain':'zero detected in bounded FULL sampled regions, not a universal guarantee',
    'scope_change':'High exposed terrain uses a continuous roof; near-surface highland cave mouths/overhangs are reduced. Native caves are retained deeper below the surface and lowland openings remain.',
    'existing_generated_chunks':'not rewritten; old/new chunk transitions can differ',
    'screenshots_exact_seed_and_coordinates':'not provided; exact locations not reproduced',
    'client_visual_test':'NOT PERFORMED','modpack_and_DH':'NOT TESTED',
    'all_village_doors_or_pathfinding':'NOT PROVEN; warnings retained in audit',
    'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()
}
dest=Path('dist');dest.mkdir(exist_ok=True)
for source in [jar,sources]:
    shutil.copyfile(source,dest/source.name)
    (dest/(source.name+'.sha256')).write_text(hashlib.sha256(source.read_bytes()).hexdigest()+'  '+source.name+'\n')
(dest/f'validation-{version}.json').write_text(json.dumps(report,indent=2)+'\n')
(dest/'audits'/f'terrain-correction-{version}.json').write_text(json.dumps(audit,indent=2)+'\n')
shutil.copyfile(p/'world-new.csv',dest/f'world-heightmap-{version}.csv')
print('FRESHGENERATOR_CORRECTION_RELEASE_PASS '+json.dumps({k:v for k,v in report.items() if k not in ['world_scenarios','legacy_historical_source_AB']}))
