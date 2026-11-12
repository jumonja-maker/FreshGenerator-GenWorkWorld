"""Release gate for native regional biome fields; no pre-claimed measured scale factor."""
import gzip,hashlib,json,os,pathlib,shutil,zipfile
import xml.etree.ElementTree as E
from biome_region_metrics import family
P=pathlib.Path;version='0.3.2-alpha.1';sha=os.environ['GITHUB_SHA'];root='data/freshgenerator/worldgen/'
jar=P(f'build/libs/FreshGenerator-Fabric-26.3-{version}.jar');sources=jar.with_name(jar.stem+'-sources.jar')
changed=[];unchanged=classes=0;allowed={root+'noise_settings/highlands_v2.json',root+'density_function/highlands_v2/temperature.json',root+'density_function/highlands_v2/vegetation.json'}
with zipfile.ZipFile(jar) as z,zipfile.ZipFile('dist/FreshGenerator-Fabric-26.3-0.3.1-alpha.1.jar') as a:
 names=z.namelist();assert len(names)==len(set(names))
 assert json.loads(z.read('fabric.mod.json'))['version']==version
 assert z.read('freshgenerator.mixins.json')==a.read('freshgenerator.mixins.json')
 assert not any(n.startswith(('net/minecraft/','dev/freshgenerator/audit/','dev/freshgenerator/smoke/','dev/freshgenerator/pack/','research/')) for n in names)
 for n in a.namelist():
  if n.startswith('data/') and n.endswith('.json'):
   if z.read(n)==a.read(n):unchanged+=1
   else:changed.append(n)
  if n.endswith('.class'):assert z.read(n)==a.read(n),('Existing runtime changed',n);classes+=1
 assert set(changed)==allowed and unchanged==337,(changed,unchanged)
 for name in ['temperature','vegetation']:
  original=json.loads(a.read(root+f'density_function/highlands_v2/{name}.json'))
  assert original==json.loads(z.read(root+f'density_function/highlands_v2/regions/original_{name}.json'))
 assert json.loads(z.read('data/minecraft/tags/worldgen/world_preset/normal.json'))['values']==['freshgenerator:world']
 manifest=json.loads(z.read('freshgenerator-worldgen-manifest.json'));assert len(manifest['generated_sha256'])==344
 for n,h in manifest['generated_sha256'].items():assert hashlib.sha256(z.read(n)).hexdigest()==h,n
suites=[E.parse(p).getroot() for p in P('build/test-results/test').glob('TEST-*.xml')]
assert suites and all(int(s.get(k,0))==0 for s in suites for k in ['failures','errors','skipped'])
count=sum(int(s.get('tests',0)) for s in suites);assert count>=146,count
metrics=[json.loads(P('build/reports/'+n).read_text()) for n in ['biome-region-metric-tests.json','cave-metrics-tests.json','cave-full-stage-tests.json']]
assert all(m[k]==0 for m in metrics for k in ['failures','errors','skipped'])
reports={}
for name in ['fresh-42','fresh-1337','fresh--20261111','normal-42']:
 folder=P('build/reports/biome-regions')/name;r=json.loads((folder/'comparison.json').read_text())
 assert r['source_commit']==sha and r['actual_server_runs']==3 and r['saved_world_identical'];reports[name]=r
 for label in ['reference','candidate','candidate-reload']:assert 'FRESHGENERATOR_BIOME_REGION_WORLD_PASS' in (folder/(label+'.log')).read_text()
fresh=[v for k,v in reports.items() if k.startswith('fresh-')]
aggregates={}
for name in ['regional','local']:
 rows=[next(m for m in r['maps'] if m['name']==name) for r in fresh];summary={}
 for phase in ['before','after']:
  source=[r[phase] for r in rows];eligible=sum(r['common_neighbor_edges'] for r in source)
  common=sum(n for r in source for biome,n in r['biome_cells'].items() if family(biome) is not None)
  summary[phase]={'samples':sum(r['sample_points'] for r in source),'common_cells':common,
   'common_id_boundary_fraction':sum(r['common_id_crossings'] for r in source)/eligible,
   'common_family_boundary_fraction':sum(r['common_family_crossings'] for r in source)/eligible,
   'small_common_components':sum(r['common_small_components_le4_cells'] for r in source),
   'small_common_components_per_10000_common_samples':10000*sum(r['common_small_components_le4_cells'] for r in source)/common,
   'exact_id_crossings':sum(r['exact_id_crossings'] for r in source)}
 for metric in ['common_id_boundary_fraction','common_family_boundary_fraction','small_common_components_per_10000_common_samples']:
  summary[metric+'_ratio']=summary['after'][metric]/summary['before'][metric]
 aggregates[name]=summary
# Fixed goals written before measurements: reduce broad transitions without erasing local topology.
local=aggregates['local'];regional=aggregates['regional']
assert regional['common_family_boundary_fraction_ratio']<=.8,('Insufficient regional improvement',aggregates)
assert local['common_id_boundary_fraction_ratio']<=.9,('Insufficient local improvement',aggregates)
assert local['small_common_components_per_10000_common_samples_ratio']<=.9,('Too many small remnants',aggregates)
audit=json.loads(P('build/reports/technical/technical-audit.json').read_text());assert audit['source_commit']==sha
assert audit['server_runs']==6 and audit['placement_rules_equal_to_vanilla_controls']
for seed in [42,1337,-20261111]:
 r=audit['summary'][f'fg-{seed}'];assert r['villages_examined']>=1 and r['structures_examined']>=3
 assert r['village_suspended_piece_candidates']==0 and r['detached_sky_terrain_components']==0
result={'version':version,'source_commit':sha,'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
 'unit_tests_passed':count,'metric_tests_passed':sum(m['tests'] for m in metrics),'actual_server_runs':18,
 'server_breakdown':{'paired_native_maps_full_regions_and_reload':9,'normal_world_and_reload':3,'structures_and_vanilla_controls':6},
 'one_public_world':'freshgenerator:world','changed_existing_data_resources':changed,'unchanged_existing_data_resources':unchanged,
 'existing_runtime_classes_byte_identical':classes,'climate_horizontal_scale_relative_to_0_3_1':3.0,
 'native_map_samples_per_version':sum(m['before']['sample_points'] for r in fresh for m in r['maps']),
 'deep_climate_checks':sum(r['deep_climate_checks'] for r in fresh),'stored_biome_checks':sum(r['stored_biome_checks'] for r in fresh),
 'map_aggregates':aggregates,'paired_worlds':reports,'structure_and_topology_summary':audit['summary'],
 'implementation':'Regional temperature and moisture with reduced native coordinate-warp amplitude; native continentalness/erosion/ridges/depth retained; original deep climate restored beyond 64 blocks below envelope',
 'limitations':['Native biome roles/thresholds and rarity tables are retained, not exact realised area fractions in finite maps','Scale is climate wavelength, NOT a guaranteed area/diameter for each biome ID','Local coast/river/mountain roles stay terrain-driven; every rare biome is not independently resized','Map components are resolution dependent, boundary-touching components are excluded from area statistics','Updated surface biomes change trees/surface materials/spawn suitability; deep decorated blocks not guaranteed identical','Existing chunks keep their saved biomes; old/new edges can be visible','No client visual/modpack/DH/C2ME validation or performance claims','Village door/pathfinding warnings remain in raw structural audit'],
 'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
d=P('dist');(d/'audits').mkdir(parents=True,exist_ok=True)
for file in [jar,sources]:shutil.copyfile(file,d/file.name);(d/(file.name+'.sha256')).write_text(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+file.name+'\n')
(d/f'validation-{version}.json').write_text(json.dumps(result,indent=2)+'\n')
raw={str(p):json.loads(p.read_text()) for p in P('build/reports/biome-regions').rglob('*.json')};raw['structures']=audit
(d/'audits'/f'biome-regions-{version}.json.gz').write_bytes(gzip.compress(json.dumps(raw,separators=(',',':')).encode(),mtime=0))
print('FRESHGENERATOR_BIOME_REGION_RELEASE_PASS '+json.dumps(result))
