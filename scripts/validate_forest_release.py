"""Require attributable empirical data, unchanged geography, active native forest hook and real paired worlds."""
import gzip,hashlib,json,os,pathlib,shutil,zipfile
import xml.etree.ElementTree as E
P=pathlib.Path;version='0.3.0-alpha.1';sha=os.environ['GITHUB_SHA']
jar=P(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar');src=jar.with_name(jar.stem+'-sources.jar')
old=P('dist/FreshGenerator-Fabric-26.3-0.2.4-alpha.1.jar');data=0;classes=0
with zipfile.ZipFile(jar) as z,zipfile.ZipFile(old) as a:
 names=z.namelist();assert len(names)==len(set(names))
 assert json.loads(z.read('fabric.mod.json'))['version']==version
 assert json.loads(z.read('freshgenerator.mixins.json'))['mixins']==['BiomeSnowMixin','ForestPlacementMixin']
 assert not any(n.startswith(('dev/freshgenerator/audit/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/','net/minecraft/','research/')) for n in names)
 for n in a.namelist():
  if n.startswith('data/') and n.endswith('.json'):
   assert a.read(n)==z.read(n),('Worldgen bytes changed',n);data+=1
  if n.endswith('.class'):
   assert a.read(n)==z.read(n),('Existing runtime class changed',n);classes+=1
 assert data==334,data
 assert 'dev/freshgenerator/forest/ForestEnvironment.class' in names
 assert 'dev/freshgenerator/core/ForestEcology.class' in names
 assert json.loads(z.read('data/minecraft/tags/worldgen/world_preset/normal.json'))['values']==['freshgenerator:world']
 manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'))
 for n,h in manifest['generated_sha256'].items():assert hashlib.sha256(z.read(n)).hexdigest()==h,n
suites=[E.parse(p).getroot() for p in P('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['failures','errors','skipped'])
count=sum(int(s.get('tests',0)) for s in suites);assert count>=119,count
reference=json.loads(P('research/forest-reference-statistics.json').read_text())
assert sum(d['statistics']['records'] for d in reference['datasets'].values())==6023
reports={}
for name in ['fresh-42','fresh-1337','fresh--20261111','normal-42']:
 p=P('build/reports/forest')/name
 r=json.loads((p/'comparison.json').read_text());assert r['source_commit']==sha and r['actual_server_runs']==3
 reports[name]=r
 for label in ['reference','candidate','candidate-reload']:assert 'FRESHGENERATOR_FOREST_WORLD_PASS' in (p/f'{label}.log').read_text()
audit=json.loads(P('build/reports/technical/technical-audit.json').read_text());assert audit['source_commit']==sha
assert audit['server_runs']==6 and audit['placement_rules_equal_to_vanilla_controls']
for seed in [42,1337,-20261111]:
 r=audit['summary'][f'fg-{seed}'];assert r['villages_examined']>=1 and r['structures_examined']>=3
 assert r['village_suspended_piece_candidates']==0 and r['detached_sky_terrain_components']==0
result={'version':version,'source_commit':sha,
 'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
 'unit_tests_passed':count,'actual_server_runs':18,'run_breakdown':{'paired_forest_and_reloads':9,'vanilla_scope_and_reload':3,'structure_terrain_with_controls':6},
 'worldgen_resources_byte_identical_to_0_2_4':data,'existing_runtime_classes_byte_identical':classes,
 'one_public_world':'freshgenerator:world','forest_worlds':reports,'structure_and_topology_summary':audit['summary'],
 'research_report_sha256':hashlib.sha256(P('research/forest-reference-statistics.json').read_bytes()).hexdigest(),'empirical_tree_records_measured':6023,
 'implementation':'Independent deterministic ecological thinning of native worldgen candidates; no extra attempts, no stored templates, no external generator dependency',
 'limitations':['Coefficients are game-scale design values, not a quantitatively fitted universal forest model','Measured log columns are not exact tree counts; client visual realism not assessed','Native trees/crowns retained; no new tree growth or succession simulation','Low-water-envelope proximity is a proxy, not actual flooding or every decorative mountain lake','Dark forest, bamboo jungle, wetlands and unknown mod biomes left unchanged','Native structure/biome rarity definitions preserved, realised tree density intentionally changes','No user modpack/DH/C2ME compatibility or performance claims','Existing chunks are not replanted; vegetation edges may differ'],
 'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
d=P('dist');(d/'audits').mkdir(parents=True,exist_ok=True)
for f in [jar,src]:shutil.copyfile(f,d/f.name);(d/(f.name+'.sha256')).write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n')
(d/f'validation-{version}.json').write_text(json.dumps(result,indent=2)+'\n')
raw={str(p):json.loads(p.read_text()) for p in P('build/reports/forest').rglob('*.json')}
raw['structures']=audit
(d/'audits'/f'forests-{version}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
print('FRESHGENERATOR_FOREST_RELEASE_PASS '+json.dumps(result))
