"""Publish village terrain only from complete, same-commit native evidence.
Unrelated generated resources and pre-existing gameplay classes stay byte-identical.
"""
import gzip,hashlib,json,os,pathlib,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
from village_checks import BASE,compare,require
P=pathlib.Path
VERSION='0.3.4-alpha.1'
BASE_VERSION='0.3.3-alpha.1'
JAR=f'FreshGenerator-Fabric-26.3-{VERSION}.jar'
SOURCES=JAR.replace('.jar','-sources.jar')
ADDED={f'dev/freshgenerator/{p}.class' for p in (
 'village/VillageTerrainModel','village/VillageTerrainModel$Site','village/VillageTerrainModel$Column',
 'village/VillageTerrainPlan','village/VillageNoiseAccess','mixin/NoiseChunkVillageMixin','mixin/VillageTerrainMixin',
 'village/VillageLakeGuard','mixin/VillageLakeMixin')}
METRICS=('biome-region-metric-tests.json','cave-metrics-tests.json','cave-full-stage-tests.json',
 'technical-update-check-tests.json','ci-evidence-tests.json','technical-reference-tests.json','village-check-tests.json')
def checksum(p):return hashlib.sha256(P(p).read_bytes()).hexdigest()
def read(p):return json.loads(P(p).read_text())
def save(p,value):
 p=P(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value,indent=2)+'\n')
def package_check(jar,baseline=None):
 baseline=baseline or f'dist/FreshGenerator-Fabric-26.3-{BASE_VERSION}.jar'
 with zipfile.ZipFile(jar) as new,zipfile.ZipFile(baseline) as old:
  names=new.namelist();require(len(names)==len(set(names)),'Duplicate JAR entry');require(new.testzip() is None,'Corrupt JAR')
  require(not any(n.startswith(('net/minecraft/','dev/freshgenerator/audit/','dev/freshgenerator/pack/','dev/freshgenerator/smoke/')) for n in names),'Non-runtime code packaged')
  resources=lambda z:{n for n in z.namelist() if n.startswith(('data/','assets/')) and not n.endswith('/')}
  require(resources(old)==resources(new),'Changed resource inventory')
  for n in resources(old):require(old.read(n)==new.read(n),'Unrelated world data changed: '+n)
  require(old.read('freshgenerator-worldgen-manifest.json')==new.read('freshgenerator-worldgen-manifest.json'),'Changed data manifest')
  before=json.loads(old.read('fabric.mod.json'));after=json.loads(new.read('fabric.mod.json'))
  require(after['version']==VERSION,'Wrong version');before['version']=VERSION;require(before==after,'Changed loader/dependency contract')
  before=json.loads(old.read('freshgenerator.mixins.json'));after=json.loads(new.read('freshgenerator.mixins.json'))
  before['mixins']+=['NoiseChunkVillageMixin','VillageTerrainMixin','VillageLakeMixin'];require(before==after,'Unexpected mixin scope')
  oldclasses={n for n in old.namelist() if n.endswith('.class')};newclasses={n for n in names if n.endswith('.class')}
  require(len(oldclasses)==28 and newclasses==oldclasses|ADDED,'Unexpected runtime class inventory')
  for n in sorted(oldclasses):require(old.read(n)==new.read(n),'Unrelated runtime changed: '+n)
  manifest=json.loads(new.read('freshgenerator-worldgen-manifest.json'));require(len(manifest['generated_sha256'])==344,'Wrong generated pack')
  for n,h in manifest['generated_sha256'].items():require(hashlib.sha256(new.read(n)).hexdigest()==h,'Generated fingerprint mismatch: '+n)
 return {'worldgen_resources_byte_identical':344,'previous_runtime_classes_byte_identical':28,
  'added_runtime_classes':sorted(ADDED),'one_public_world':'freshgenerator:world','native_structure_tables_unchanged':True}
def build():
 sha=os.environ['GITHUB_SHA'];jar=P('build/libs')/JAR;sources=P('build/libs')/SOURCES
 evidence=package_check(jar)
 suites=[ET.parse(p).getroot() for p in P('build/test-results/test').glob('TEST-*.xml')]
 require(bool(suites),'No native-class unit tests');require(all(int(s.get(k,0))==0 for s in suites for k in ('failures','errors','skipped')),'Java test failure/skip')
 count=sum(int(s.get('tests',0)) for s in suites);require(count>=221,'Missing Java test coverage')
 metrics={n:read(P('build/reports')/n) for n in METRICS}
 require(all(m[k]==0 for m in metrics.values() for k in ('failures','errors','skipped')),'Python test failure/skip')
 require(sum(m['tests'] for m in metrics.values())>=122,'Missing metric/validation tests')
 fingerprints=P('build/reports/village-release/reproducible.sha256');require(fingerprints.exists(),'No initial-build hashes')
 expected={line.split()[1]:line.split()[0] for line in fingerprints.read_text().splitlines()}
 for p in (jar,sources):require(expected[str(p)]==checksum(p),'Non-reproducible package: '+str(p))
 result={'version':VERSION,'source_commit':sha,'reference_commit':BASE,'unit_tests_passed':count,
  'metric_tests_passed':sum(m['tests'] for m in metrics.values()),'metrics':metrics,
  'jar_sha256':checksum(jar),'sources_sha256':checksum(sources),'repeat_build_byte_identical':True,**evidence}
 save('build/reports/village-release/build.json',result)
 print('FRESHGENERATOR_VILLAGE_BUILD_PASS '+json.dumps(result))
def totals(pairs,field):
 keys=('terrain_columns','protected_roof_columns','roof_failure_columns','open_hole_columns','rigid_columns','rigid_grade_error_columns') if field.startswith('terrain') else (
  'doors','door_exit_warnings','path_blocks','path_columns_with_void_within_8','farmland_blocks','farmland_columns_with_void_within_8',
  'protected_path_air_or_lava_columns','protected_farm_air_or_lava_columns','protected_floor_lava_columns')
 return {k:sum(row[k] for r in pairs.values() for row in r[field]) for k in keys}
def publish():
 sha=os.environ['GITHUB_SHA'];root=P('ci-evidence');unit=root/'technical-build'
 b=read(unit/'reports/village-release/build.json');require(b['source_commit']==sha and b['version']==VERSION,'Stale package evidence')
 jar=unit/'libs'/JAR;sources=unit/'libs'/SOURCES
 require(checksum(jar)==b['jar_sha256'] and checksum(sources)==b['sources_sha256'],'Package digest mismatch')
 package_check(jar)
 pairs={}
 for seed in (42,1337,-20261111):pairs[str(seed)]=compare(root/f'technical-world-fresh-{seed}',sha,seed)
 require(sum(r['villages'] for r in pairs.values())>=9,'Missing actual village coverage')
 before=totals(pairs,'terrain_before');after=totals(pairs,'terrain_after')
 require(before['roof_failure_columns']>0 and after['roof_failure_columns']==0,'No measured roof defect fixed')
 from technical_update_checks import compare_folder
 normal=compare_folder(root/'technical-world-normal-42',sha,'normal',42)
 technical=P('build/reports/technical');technical.mkdir(parents=True,exist_ok=True)
 for seed in (42,1337,-20261111):
  folder=root/f'technical-structures-{seed}';meta=read(folder/f'meta-{seed}.json')
  require(meta['source_commit']==sha and meta['seed']==seed and meta['actual_server_runs']==2,'Stale structure evidence')
  for n,h in meta['files_sha256'].items():
   require(P(n).name==n and checksum(folder/n)==h,'Bad structure observation')
   shutil.copyfile(folder/n,technical/n)
 subprocess.run([sys.executable,'scripts/summarize_technical_audit.py'],check=True)
 audit=read(technical/'technical-audit.json');require(audit['source_commit']==sha and audit['server_runs']==6,'Missing actual structure runs')
 for seed in (42,1337,-20261111):
  r=audit['summary'][f'fg-{seed}']
  require(r['detached_sky_terrain_components']==0 and r['village_suspended_piece_candidates']==0,'Terrain/support regression')
  require(r['native_structures_equal']==52 and r['native_structure_sets_equal']==21,'Native structure placement changed')
 result={**b,'actual_server_runs':20,'server_breakdown':{'paired_villages_and_reload':9,'normal_control':5,'structures_and_vanilla_controls':6},
  'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
  'ci_evidence':read(root/'index.json'),'paired_villages':pairs,
  'terrain_before':before,'terrain_after':after,'village_objects_before':totals(pairs,'full_before'),'village_objects_after':totals(pairs,'full_after'),
  'ordinary_minecraft_control':normal,'structure_and_topology_summary':audit['summary'],
  'limits':['Fixed seeds/locations and finite sampled columns; not all villages worldwide',
   'Only five vanilla village IDs in the active FreshGenerator world; enlarged/custom village layouts can fall back to native behavior',
   'Local terrain and shallow roof change; deep terrain and outside-plot samples are checked exactly before features, not all decorated underground blocks',
   'No post-load repair or editing of old chunks; transitions to existing village chunks may remain visible',
   'Door screening is not villager pathfinding; support warnings and missing bounded-search structures retained',
   'No client/shader/modpack/DH/C2ME test or measured performance claim']}
 dist=P('dist');(dist/'audits').mkdir(parents=True,exist_ok=True)
 for src in (jar,sources):
  shutil.copyfile(src,dist/src.name);(dist/(src.name+'.sha256')).write_text(checksum(src)+'  '+src.name+'\n')
 save(dist/f'validation-{VERSION}.json',result)
 raw={str(p.relative_to(root)):read(p) for p in root.rglob('*.json')};raw['structure_audit']=audit
 (dist/'audits'/f'village-terrain-{VERSION}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
 print('FRESHGENERATOR_VILLAGE_RELEASE_PASS '+json.dumps(result))
if __name__=='__main__':
 require(len(sys.argv)==2 and sys.argv[1] in ('build','publish'),'Expected build or publish')
 (build if sys.argv[1]=='build' else publish)()
