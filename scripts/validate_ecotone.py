"""Release gate: new runtime must be checked by its own native, paired and FULL-world tests."""
import gzip,hashlib,json,os,shutil,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
version='0.2.4-alpha.1'
sha=os.environ['GITHUB_SHA']
jar=Path(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar');sources=jar.with_name(jar.stem+'-sources.jar')
root='data/freshgenerator/worldgen/'
allowed={root+'noise_settings/highlands_v2.json',root+'material_rule/highlands_v2/biome_surface.json',root+'material_rule/highlands_v2/under_biome_surface.json'}
changed=[];unchanged=0
with zipfile.ZipFile(jar) as z,zipfile.ZipFile('dist/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar') as old:
 names=z.namelist();assert len(names)==len(set(names))
 meta=json.loads(z.read('fabric.mod.json'));assert meta['version']==version
 assert meta['mixins']==['freshgenerator.mixins.json']
 assert json.loads(z.read('freshgenerator.mixins.json'))['mixins']==['BiomeSnowMixin']
 assert not any(n.startswith(('net/minecraft/','net/fabricmc/','dev/freshgenerator/audit/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/')) for n in names)
 assert all(b'sun/misc/Unsafe' not in z.read(n) for n in names if n.endswith('.class'))
 assert json.loads(z.read('data/minecraft/tags/worldgen/world_preset/normal.json'))=={'replace':False,'values':['freshgenerator:world']}
 for locale in ['en_us','ru_ru']:assert json.loads(z.read(f'assets/freshgenerator/lang/{locale}.json'))=={'generator.freshgenerator.world':'FreshGenerator'}
 manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'))
 assert manifest['selectable_world_presets']==['freshgenerator:world']
 for n in old.namelist():
  if n.startswith('data/') and n.endswith('.json'):
   assert n in names,n
   if json.loads(old.read(n))!=json.loads(z.read(n)):changed.append(n)
   else:unchanged+=1
 assert set(changed)==allowed,changed
 assert unchanged==330,unchanged
 assert len(manifest['generated_sha256'])==334
 for path,digest in manifest['generated_sha256'].items():assert hashlib.sha256(z.read(path)).hexdigest()==digest,path
 for n in names:
  for registry in ['biome','structure','structure_set','placed_feature','feature','multi_noise_biome_source_parameter_list']:assert f'/worldgen/{registry}/' not in n,n
 # Whole terrain-density graph remains semantically identical to the prior fix.
 for n in old.namelist():
  if '/density_function/highlands_v2/' in n and n.endswith('.json'):assert json.loads(z.read(n))==json.loads(old.read(n)),n
with zipfile.ZipFile(sources) as z:assert 'dev/freshgenerator/pack/EcotoneCompiler.java' in z.namelist()
suites=[ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['errors','failures','skipped'])
count=sum(int(s.get('tests',0)) for s in suites);assert count>=101,count
p=Path('build/reports/integration');reports={}
for label in ['balanced-new','balanced-reload','other-seed','highlands-new','natural-new','natural-other-seed','world-new','world-reload']:
 assert 'FRESHGENERATOR_WORLD_PASS' in (p/f'{label}.log').read_text()
 reports[label]=json.loads((p/f'{label}.json').read_text())
for k in ['block_sha256','climate_sha256']:assert reports['world-new'][k]==reports['world-reload'][k],k
legacy=json.loads((p/'legacy-comparison.json').read_text());assert legacy['full_block_differences']==0 and legacy['full_blocks_compared']==491520
q=Path('build/reports/technical');audit=json.loads((q/'technical-audit.json').read_text());assert audit['source_commit']==sha
assert audit['server_runs']==6 and audit['placement_rules_equal_to_vanilla_controls']
for seed in [42,1337,-20261111]:
 r=audit['summary'][f'fg-{seed}'];assert r['villages_examined']>=1 and r['structures_examined']>=3
 assert r['village_suspended_piece_candidates']==0,('Suspended structure',seed)
 assert r['detached_sky_terrain_components']==0,('Detached surface',seed)
 env=json.loads((q/f'fg-{seed}-envelope.json').read_text());assert env['monotone_exterior_density_samples']>10000 and env['protected_roof_samples']>0
fixed=Path('build/reports/ecotone-fixed');paired=json.loads((fixed/'comparison.json').read_text());assert paired['source_commit']==sha
broad={}
for seed in [42,1337,-20261111]:
 files=list(Path('build/reports/biome-transitions').glob(f'run.*/seed-{seed}.json'));assert len(files)==1,files
 r=json.loads(files[0].read_text());assert r['seed']==seed and r['full_chunks_requested']>0 and len(r['junctions'])>0
 broad[str(seed)]={'full_windows_scanned':r['full_windows_scanned'],'full_chunks_requested':r['full_chunks_requested'],'junctions_reported':len(r['junctions']),
   'exposed_cave_columns':sum(w['exposed_cave_biome_columns_at_base_height'] for w in r['junctions'])}
 # These are newly selected windows. Never use them as a paired material-improvement metric.
report={'version':version,'source_commit':sha,
 'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
 'unit_tests_passed':count,'actual_server_runs':24,'one_public_world':'freshgenerator:world',
 'changed_existing_generation_resources':changed,'unchanged_existing_generation_resources':unchanged,
 'geometry_and_aquifer_graph':'unchanged; paired native base heights and water levels required identical',
 'paired_fixed_coordinate_audit':paired,'newly_selected_biome_junction_audit':broad,
 'structure_and_topology_summary':audit['summary'],'save_reload':'passed with FULL neighbours',
 'legacy_full_blocks_compared':legacy['full_blocks_compared'],'legacy_full_block_differences':legacy['full_block_differences'],
 'implementation':'independent shared multiscale domain-warped surface mask, near-surface biome depth guard, scoped native snowfall gate',
 'third_party_code_copied':False,'external_generator_dependency':False,
 'limitations':['Not a universal biome-edge fix: targeted montane grass/snow/stone palettes only','Client render, weather visuals, grass/foliage colour blending and user modpack/DH not tested','Global biome and structure frequency per area not measured; native placement definitions preserved','Village door/pathfinding warnings retained, not all fixed','Existing chunks and stored biomes not rewritten; old/new borders can differ'],
 'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
dest=Path('dist');(dest/'audits').mkdir(parents=True,exist_ok=True)
for source in [jar,sources]:
 shutil.copyfile(source,dest/source.name);(dest/(source.name+'.sha256')).write_text(hashlib.sha256(source.read_bytes()).hexdigest()+'  '+source.name+'\n')
(dest/f'validation-{version}.json').write_text(json.dumps(report,indent=2)+'\n')
(dest/'audits'/f'ecotone-comparison-{version}.json').write_text(json.dumps(paired,indent=2)+'\n')
(dest/'audits'/f'ecotone-structures-{version}.json').write_text(json.dumps(audit,indent=2)+'\n')
raw={f.name:json.loads(f.read_text()) for f in fixed.glob('*.json')}
raw.update({f'junctions-{f.name}':json.loads(f.read_text()) for f in Path('build/reports/biome-transitions').glob('run.*/seed-*.json')})
(dest/'audits'/f'ecotone-raw-{version}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
print('FRESHGENERATOR_ECOTONE_RELEASE_PASS '+json.dumps(report))
