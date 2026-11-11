"""Release requires exact pre-feature caves, measured remaining entrances, bounded FULL drift and structures."""
import gzip,hashlib,json,os,pathlib,shutil,zipfile
import xml.etree.ElementTree as E
P=pathlib.Path;version='0.3.1-alpha.1';sha=os.environ['GITHUB_SHA']
jar=P(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar');sources=jar.with_name(jar.stem+'-sources.jar')
root='data/freshgenerator/worldgen/';weight=root+'density_function/highlands_v2/coherent/cave_weight.json';settings=root+'noise_settings/highlands_v2.json'
allowed={weight,settings};changed=[];unchanged=classes=0
with zipfile.ZipFile(jar) as z,zipfile.ZipFile('dist/FreshGenerator-Fabric-26.3-0.3.0-alpha.1.jar') as old:
 names=z.namelist();assert len(names)==len(set(names))
 assert json.loads(z.read('fabric.mod.json'))['version']==version
 assert json.loads(z.read('freshgenerator.mixins.json'))['mixins']==['BiomeSnowMixin','ForestPlacementMixin','CaveCarverMixin']
 assert not any(n.startswith(('net/minecraft/','dev/freshgenerator/audit/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/','research/')) for n in names)
 for n in old.namelist():
  if n.startswith('data/') and n.endswith('.json'):
   assert n in names
   if old.read(n)!=z.read(n):changed.append(n)
   else:unchanged+=1
  if n.endswith('.class'):assert old.read(n)==z.read(n),('Existing runtime changed',n);classes+=1
 assert set(changed)==allowed and unchanged==332,(changed,unchanged)
 assert json.loads(old.read(weight))==json.loads(z.read(root+'density_function/highlands_v2/cave_access/original_weight.json'))
 newsettings=json.loads(z.read(settings));entries=newsettings['debug_functions'];assert entries[-2]['label']=='FG cave access: surface' and entries[-1]['label']=='FG cave access: roof'
 del entries[-2:];assert newsettings==json.loads(old.read(settings))
 manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'));assert len(manifest['generated_sha256'])==340
 for n,h in manifest['generated_sha256'].items():assert hashlib.sha256(z.read(n)).hexdigest()==h,n
 assert json.loads(z.read('data/minecraft/tags/worldgen/world_preset/normal.json'))['values']==['freshgenerator:world']
suites=[E.parse(p).getroot() for p in P('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['failures','errors','skipped'])
count=sum(int(s.get('tests',0)) for s in suites);assert count>=134,count
metric_suites=[json.loads(P('build/reports/'+name).read_text()) for name in ['cave-metrics-tests.json','cave-full-stage-tests.json']]
assert metric_suites[0]['tests']>=18 and metric_suites[1]['tests']>=8
assert all(m[k]==0 for m in metric_suites for k in ['failures','errors','skipped'])
reports={}
for name in ['fresh-42','fresh-1337','fresh--20261111','normal-42']:
 p=P('build/reports/caves')/name;r=json.loads((p/'comparison.json').read_text());assert r['source_commit']==sha
 fresh=name.startswith('fresh-');assert r['actual_server_runs']==(4 if fresh else 3)
 if fresh:
  stage=r['pre_feature_terrain_comparison'];control=r['reference_repeat_control']
  assert stage['exact_block_states_identical'] and stage['deep_block_samples']>10000 and stage['chunks_compared']>=32
  assert control['pre_feature_terrain_comparison']==stage
  assert len(control['regions'])==len(r['regions'])
  for row in r['regions']:
   check=row['full_stage_budget'];assert check['budget_passed'] and check['all_changed_samples_have_exact_terrain_evidence']
   assert check['verified_deep_terrain']['exact_block_states_identical']
  assert all(row['stage_comparison']['budget_passed'] for row in control['regions'])
 labels=['reference','candidate','candidate-reload']+(['reference-repeat'] if fresh else [])
 for label in labels:assert 'FRESHGENERATOR_CAVE_WORLD_PASS' in (p/f'{label}.log').read_text()
 reports[name]=r
fresh=[v for k,v in reports.items() if k.startswith('fresh-')]
before=sum(r['opening_columns_before'] for r in fresh);after=sum(r['opening_columns_after'] for r in fresh)
assert 0<after<=before*.7,('Insufficient area reduction or all openings lost',before,after)
assert sum(r['telemetry']['canyon_near_surface_blocked'] for r in fresh)>0
assert sum(r['telemetry']['canyon_near_surface_forwarded'] for r in fresh)>0
audit=json.loads(P('build/reports/technical/technical-audit.json').read_text());assert audit['source_commit']==sha
assert audit['server_runs']==6 and audit['placement_rules_equal_to_vanilla_controls']
for seed in [42,1337,-20261111]:
 r=audit['summary'][f'fg-{seed}'];assert r['villages_examined']>=1 and r['structures_examined']>=3
 assert r['village_suspended_piece_candidates']==0 and r['detached_sky_terrain_components']==0
result={'version':version,'source_commit':sha,
 'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
 'unit_tests_passed':count,'audit_metric_tests_passed':sum(m['tests'] for m in metric_suites),'actual_server_runs':21,
 'server_run_breakdown':{'reference_AA_candidate_and_reload':12,'normal_scope_and_reload':3,'structures_with_vanilla_controls':6},
 'one_public_world':'freshgenerator:world','changed_existing_data_resources':changed,'unchanged_existing_data_resources':unchanged,
 'existing_runtime_classes_byte_identical':classes,'surface_opening_columns_before':before,'surface_opening_columns_after':after,
 'surface_opening_area_reduction_fraction':1-after/before,'paired_cave_worlds':reports,'structure_and_topology_summary':audit['summary'],
 'deep_terrain_stage_samples':sum(r['pre_feature_terrain_comparison']['deep_block_samples'] for r in fresh),
 'post_feature_deep_occupancy_changes':sum(r['post_feature_occupancy_changes'] for r in fresh),
 'post_feature_deep_fluid_changes':sum(r['post_feature_fluid_occupancy_changes'] for r in fresh),
 'deep_geometry_classification':'Exact sampled TERRAIN states match, without exclusions. FULL changes, including small fluid/floor changes, are observed separately and bounded; decorated underground blocks are NOT claimed identical. A/A controls measure native repeat variability without automatically subtracting it.',
 'implementation':'Shared seeded 2-D entrance gate, shallow noise blend and per-invocation native carver-output guard; no loaded-world edits, no changed native start probabilities or random draws',
 'depth_scope':'Noise cave influence returns exactly to old weight from 42 blocks below envelope; carver proposals at least 14 blocks below envelope are passed through',
 'limitations':['Finite fixed-coordinate sample, not proof for every seed','Opening area is a vertical dry surface-cut proxy, not exact cave count or all side entrances','Upper cave branches can close or change connectivity; an accessible entrance for every deep chamber is not guaranteed','FULL decoration can change ores, sculk, plants and small cave-floor water patches; all sampled raw changes are retained','Structures keep native placement definitions; local suitability and decorations near repaired holes can change','Existing chunks are not repaired automatically; old/new boundaries may differ','Client visuals, full user modpack, Distant Horizons and C2ME not tested','Village door/pathfinding findings remain in raw audit'],
 'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
d=P('dist');(d/'audits').mkdir(parents=True,exist_ok=True)
for f in [jar,sources]:shutil.copyfile(f,d/f.name);(d/(f.name+'.sha256')).write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n')
(d/f'validation-{version}.json').write_text(json.dumps(result,indent=2)+'\n')
raw={str(p):json.loads(p.read_text()) for p in P('build/reports/caves').rglob('*.json')};raw['structures']=audit
(d/'audits'/f'cave-access-{version}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
print('FRESHGENERATOR_CAVE_RELEASE_PASS '+json.dumps(result))
