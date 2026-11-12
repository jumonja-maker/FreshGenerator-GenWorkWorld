"""Package and independent-artifact release gate for the worldgen-preserving tech update."""
import gzip,hashlib,json,os,pathlib,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as E
from technical_update_checks import BASE_COMMIT,SCENARIOS,compare_folder,require

P=pathlib.Path
VERSION='0.3.3-alpha.1'
BASE_VERSION='0.3.2-alpha.1'
SHA=os.environ['GITHUB_SHA']
JAR=f'FreshGenerator-Fabric-26.3-{VERSION}.jar'
SOURCES=JAR.replace('.jar','-sources.jar')

def checksum(p):return hashlib.sha256(P(p).read_bytes()).hexdigest()
def read(p):return json.loads(P(p).read_text())
def save(p,obj):
 p=P(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n')

def package_check(jar):
 with zipfile.ZipFile(jar) as new,zipfile.ZipFile(f'dist/FreshGenerator-Fabric-26.3-{BASE_VERSION}.jar') as old:
  names=new.namelist();require(len(names)==len(set(names)),'Duplicate JAR entry');require(new.testzip() is None,'Corrupt JAR')
  require(not any(n.startswith(('net/minecraft/','dev/freshgenerator/audit/','dev/freshgenerator/pack/','dev/freshgenerator/smoke/')) for n in names),'Non-runtime code in mod')
  resources=lambda z:{n for n in z.namelist() if n.startswith(('data/','assets/')) and not n.endswith('/')}
  require(resources(old)==resources(new),'Changed resource inventory')
  for n in resources(old):require(old.read(n)==new.read(n),'World data changed: '+n)
  for n in ['freshgenerator.mixins.json','freshgenerator-worldgen-manifest.json']:
   require(old.read(n)==new.read(n),'Changed world manifest/mixin scope: '+n)
  before=json.loads(old.read('fabric.mod.json'));after=json.loads(new.read('fabric.mod.json'))
  require(after['version']==VERSION,'Wrong version');before['version']=VERSION;require(before==after,'Changed loader/dependency contract')
  oldclasses={n for n in old.namelist() if n.endswith('.class')};newclasses={n for n in names if n.endswith('.class')}
  require(oldclasses==newclasses,'Added/removed runtime class; experimental caches must not ship')
  # Both runtime experiments were rejected. No whitelist of changed game classes remains.
  for n in sorted(oldclasses):require(old.read(n)==new.read(n),'Published runtime changed: '+n)
  require(len(oldclasses)==28,'Unexpected published runtime inventory')
  manifest=json.loads(new.read('freshgenerator-worldgen-manifest.json'));require(len(manifest['generated_sha256'])==344,'Wrong pack')
  for n,h in manifest['generated_sha256'].items():require(hashlib.sha256(new.read(n)).hexdigest()==h,'Bad generated hash: '+n)
 return {'worldgen_data_resources_identical':344,'all_data_and_assets_identical':True,'changed_runtime_classes':[],
         'existing_runtime_classes_byte_identical':28,'new_runtime_classes':[],
         'forest_runtime_byte_identical':True,'cave_runtime_byte_identical':True,
         'experimental_height_cache_in_game_jar':False,'experimental_cave_clamp_in_game_jar':False,
         'mixins_and_dependency_contract_unchanged':True}

def build():
 props=dict(line.split('=',1) for line in P('gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
 require(props['version']==VERSION,'Wrong source version')
 jar=P('build/libs')/JAR;sources=P('build/libs')/SOURCES
 evidence=package_check(jar)
 suites=[E.parse(p).getroot() for p in P('build/test-results/test').glob('TEST-*.xml')]
 require(bool(suites),'No Java test reports')
 require(all(int(s.get(k,0))==0 for s in suites for k in ('failures','errors','skipped')),'Java test failure/skip')
 count=sum(int(s.get('tests',0)) for s in suites);require(count>=190,'Missing Java tests')
 metrics={name:read(P('build/reports')/name) for name in ['biome-region-metric-tests.json','cave-metrics-tests.json','cave-full-stage-tests.json','technical-update-check-tests.json','ci-evidence-tests.json','technical-reference-tests.json']}
 require(all(m[k]==0 for m in metrics.values() for k in ('failures','errors','skipped')),'Metric test failure/skip')
 require(sum(m['tests'] for m in metrics.values())>=98,'Missing metric tests')
 replay=P('build/reports/technical-update/reproducible.sha256');require(replay.exists(),'Missing first-build fingerprints')
 expected={line.split()[1]:line.split()[0] for line in replay.read_text().splitlines()}
 for p in (jar,sources):require(expected[str(p)]==checksum(p),'Non-reproducible JAR: '+str(p))
 result={'version':VERSION,'source_commit':SHA,'reference_commit':BASE_COMMIT,'unit_tests_passed':count,
  'metric_tests_passed':sum(m['tests'] for m in metrics.values()),'metrics':metrics,
  'jar_sha256':checksum(jar),'sources_sha256':checksum(sources),'repeat_build_byte_identical':True,**evidence}
 save('build/reports/technical-update/build.json',result);print('FRESHGENERATOR_TECH_BUILD_PASS '+json.dumps(result))

def publish():
 root=P('ci-evidence');unit=root/'technical-build';b=read(unit/'reports/technical-update/build.json')
 require(b['source_commit']==SHA and b['version']==VERSION,'Stale build evidence')
 jar=unit/'libs'/JAR;sources=unit/'libs'/SOURCES
 require(b['jar_sha256']==checksum(jar) and b['sources_sha256']==checksum(sources),'Artifact digest mismatch')
 package_check(jar)
 pairs={}
 for scope,seed in SCENARIOS:
  folder=root/f'technical-world-{scope}-{seed}'
  r=compare_folder(folder,SHA,scope,seed);pairs[f'{scope}-{seed}']=r
 require(sum(r['native_map_samples_per_version'] for r in pairs.values())==638976,'Missing real map coverage')
 technical=P('build/reports/technical');technical.mkdir(parents=True,exist_ok=True)
 for seed in [42,1337,-20261111]:
  folder=root/f'technical-structures-{seed}';meta=read(folder/f'meta-{seed}.json')
  require(meta['source_commit']==SHA and meta['seed']==seed and meta['actual_server_runs']==2,'Stale structure report')
  for n,h in meta['files_sha256'].items():
   require(P(n).name==n and checksum(folder/n)==h,'Bad structure artifact')
   shutil.copyfile(folder/n,technical/n)
 subprocess.run([sys.executable,'scripts/summarize_technical_audit.py'],check=True)
 audit=read(technical/'technical-audit.json');require(audit['source_commit']==SHA and audit['server_runs']==6,'Missing structure runs')
 for seed in [42,1337,-20261111]:
  r=audit['summary'][f'fg-{seed}'];require(r['detached_sky_terrain_components']==0 and r['village_suspended_piece_candidates']==0,'Terrain/support regression')
 result={**b,'actual_server_runs':26,'server_breakdown':{'paired_worlds_three_references_and_reload':20,'structures_and_vanilla_controls':6},
  'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
  'ci_evidence':read(root/'index.json'),'one_public_world':'freshgenerator:world','paired_worlds':pairs,'structure_and_topology_summary':audit['summary'],
  'rejected_forest_optimization':{'source_commit':'578b6456f2f5ce9cc665642406f0f05a2bfc5d56','run_id':43162819040,'seed':42,'changed_sampled_blocks':9,
   'reason':'Unobserved tree/leaf-litter states; no weakening of the block gate. Restore the exact published forest runtime.',
   'raw_evidence':'research/rejected-candidates/technical-world-fresh-42-578b645.json.gz'},
  'rejected_cave_optimization':{'source_commit':'845af54c8c7d943036e741b9d931311759fe9dc8','run_id':43166487075,'seed':1337,'changed_sampled_blocks':391,
   'reason':'Unexplained sculk-related FULL differences against three stable controls. Root cause is not proven; do not ship the unvalidated runtime optimization.',
   'raw_evidence':'research/rejected-candidates/technical-world-fresh-1337-845af54.json.gz'},
  'limits':['Fixed seeds and sampled FULL columns; not a proof for all worlds','Native structure warnings retained; no villager navigation simulation',
            'No client/shader/modpack/DH/C2ME test or measured FPS/speedup claim','Maintenance of compiler/build/testing, not new runtime terrain or biome behavior',
            'Both runtime optimization experiments rejected; no claim to have fixed native sculk randomness']}
 dist=P('dist');(dist/'audits').mkdir(parents=True,exist_ok=True)
 for src in (jar,sources):
  shutil.copyfile(src,dist/src.name);(dist/(src.name+'.sha256')).write_text(checksum(src)+'  '+src.name+'\n')
 save(dist/f'validation-{VERSION}.json',result)
 raw={str(p.relative_to(root)):read(p) for p in root.rglob('*.json')};raw['structure_audit']=audit
 (dist/'audits'/f'technical-update-{VERSION}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
 print('FRESHGENERATOR_TECH_RELEASE_PASS '+json.dumps(result))

if __name__=='__main__':
 require(len(sys.argv)==2 and sys.argv[1] in ('build','publish'),'Expected build or publish')
 (build if sys.argv[1]=='build' else publish)()
