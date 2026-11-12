"""Evidence-gated alpine detail release; preserve native villages, deep caves and prior JAR code."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from village_checks import compare, require
from validate_village_release import totals
from technical_update_checks import compare_folder

P=Path
VERSION='0.3.6-alpha.1'
PREVIOUS='0.3.5-alpha.1'
ROOT='data/freshgenerator/worldgen/density_function/highlands_v2/'
SURFACE=ROOT+'coherent/surface_density.json'
ADDED={ROOT+'coherent/detail/'+name+'.json' for name in
       ('original_surface_density','mountain_factor','vertical_band','rock_relief')}
JAR='FreshGenerator-Fabric-26.3-'+VERSION+'.jar'
SOURCE_JAR=JAR.replace('.jar','-sources.jar')
METRICS=('biome-region-metric-tests.json','cave-metrics-tests.json','cave-full-stage-tests.json',
         'technical-update-check-tests.json','ci-evidence-tests.json','technical-reference-tests.json',
         'village-check-tests.json')

def read(path):return json.loads(P(path).read_text())
def save(path,value):
    path=P(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2)+'\n')
def checksum(path):return hashlib.sha256(P(path).read_bytes()).hexdigest()

def package_check(path):
    published=f'dist/FreshGenerator-Fabric-26.3-{PREVIOUS}.jar'
    with zipfile.ZipFile(path) as new,zipfile.ZipFile(published) as old:
        names=new.namelist()
        require(len(names)==len(set(names)),'Duplicate ZIP entry')
        require(new.testzip() is None,'Broken game JAR')
        old_names=set(old.namelist());new_names=set(names)
        require(new_names==old_names|ADDED,'Unapproved JAR addition/removal')
        for name in new_names:
            require(not name.startswith(('net/minecraft/','dev/freshgenerator/audit/',
                    'dev/freshgenerator/pack/','dev/freshgenerator/smoke/')),
                    'Tool/game class leaked into mod: '+name)
        runtime={name for name in names if name.endswith('.class')}
        require(len(runtime)==37,'Changed runtime class count')
        require(runtime=={name for name in old_names if name.endswith('.class')},
                'New or removed runtime class')
        for name in runtime:
            require(old.read(name)==new.read(name),'Changed gameplay bytecode: '+name)
        before=json.loads(old.read('fabric.mod.json'))
        after=json.loads(new.read('fabric.mod.json'))
        require(after['version']==VERSION,'Wrong mod version')
        before['version']=VERSION
        require(before==after,'Changed loader contract')
        require(old.read('freshgenerator.mixins.json')==new.read('freshgenerator.mixins.json'),
                'Changed mixin coverage')
        require(old.read(SURFACE)!=new.read(SURFACE),'No active alpine detail output')
        for name in old_names:
            if name in (SURFACE,'fabric.mod.json','freshgenerator-worldgen-manifest.json'):
                continue
            require(old.read(name)==new.read(name),'Unapproved packaged change: '+name)
        previous_manifest=json.loads(old.read('freshgenerator-worldgen-manifest.json'))
        manifest=json.loads(new.read('freshgenerator-worldgen-manifest.json'))
        old_data=previous_manifest['generated_sha256']
        new_data=manifest['generated_sha256']
        require(len(old_data)==344 and set(new_data)==set(old_data)|ADDED and len(new_data)==348,
                'Unexpected worldgen resource inventory')
        for name,digest in new_data.items():
            require(hashlib.sha256(new.read(name)).hexdigest()==digest,
                    'Generated fingerprint mismatch: '+name)
        require(all(new_data[name]==old_data[name] for name in old_data if name!=SURFACE),
                'Changed non-surface worldgen resource')
        original=json.loads(new.read(ROOT+'coherent/detail/original_surface_density.json'))
        require(original==json.loads(old.read(SURFACE)),
                'Original coherent terrain envelope was not preserved')
        detail=json.loads(new.read(ROOT+'coherent/detail/rock_relief.json'))
        require('native_cave_body' in json.dumps(detail) and
                detail.get('type')=='minecraft:range_choice','Original 3-D rock signal not present')
        return {'source_and_gameplay_classes_byte_identical':37,
                'generated_worldgen_resources':348,
                'unchanged_existing_worldgen_resources':343,
                'modified_existing_worldgen_resources':[SURFACE],
                'added_worldgen_resources':sorted(ADDED),
                'native_structure_tables_unchanged':True,
                'only_public_world':'freshgenerator:world'}

def build():
    sha=os.environ['GITHUB_SHA']
    jar=P('build/libs')/JAR
    source=P('build/libs')/SOURCE_JAR
    evidence=package_check(jar)
    tests=[ET.parse(path).getroot() for path in P('build/test-results/test').glob('TEST-*.xml')]
    require(bool(tests),'No real Java test reports')
    require(all(int(t.get(key,0))==0 for t in tests for key in ('failures','errors','skipped')),
            'Java test failure or skip')
    count=sum(int(t.get('tests',0)) for t in tests)
    require(count>=236,'Insufficient Java tests')
    metrics={name:read(P('build/reports')/name) for name in METRICS}
    require(all(m[key]==0 for m in metrics.values() for key in ('failures','errors','skipped')),
            'Metrics test failure or skip')
    require(sum(m['tests'] for m in metrics.values())>=128,'Insufficient Python tests')
    expected={line.split()[1]:line.split()[0] for line in
             P('build/reports/village-release/reproducible.sha256').read_text().splitlines()}
    for file in (jar,source):
        require(expected[str(file)]==checksum(file),'Non-reproducible JAR: '+str(file))
    result={'version':VERSION,'source_commit':sha,'reference_version':PREVIOUS,
            'java_tests_passed':count,'python_tests_passed':sum(m['tests'] for m in metrics.values()),
            'metrics':metrics,'jar_sha256':checksum(jar),
            'sources_sha256':checksum(source),'reproducible_build':True,**evidence}
    save('build/reports/village-release/build.json',result)
    print('FRESHGENERATOR_MOUNTAIN_BUILD_PASS '+json.dumps(result))

def publish():
    sha=os.environ['GITHUB_SHA']
    root=P('ci-evidence')
    b=read(root/'technical-build/reports/village-release/build.json')
    require(b['version']==VERSION and b['source_commit']==sha,'Stale build evidence')
    jar=root/'technical-build/libs'/JAR
    src=root/'technical-build/libs'/SOURCE_JAR
    require(checksum(jar)==b['jar_sha256'] and checksum(src)==b['sources_sha256'],
            'Mismatched published build hashes')
    require(package_check(jar)['generated_worldgen_resources']==348,
            'Published resource scope changed')
    pairs={}
    for seed in (42,1337,-20261111):
        pairs[str(seed)]=compare(root/f'technical-world-fresh-{seed}',sha,seed)
        require(pairs[str(seed)]['published_equivalence']['published_observations_identical'],
                'Changed native village compared to 0.3.4')
    require(sum(x['villages'] for x in pairs.values())>=9,'Insufficient real village coverage')
    normal=compare_folder(root/'technical-world-normal-42',sha,'normal',42)
    technical=P('build/reports/technical');technical.mkdir(parents=True,exist_ok=True)
    for seed in (42,1337,-20261111):
        folder=root/f'technical-structures-{seed}'
        meta=read(folder/f'meta-{seed}.json')
        require(meta['source_commit']==sha and meta['seed']==seed
                and meta['actual_server_runs']==2,'Stale structure data')
        for name,digest in meta['files_sha256'].items():
            require(P(name).name==name and checksum(folder/name)==digest,'Bad structure file')
            shutil.copyfile(folder/name,technical/name)
    subprocess.run([sys.executable,'scripts/summarize_technical_audit.py'],check=True)
    topology=read(technical/'technical-audit.json')
    require(topology['source_commit']==sha and topology['server_runs']==6,
            'Incomplete structure run')
    detail={}
    for seed in (42,1337,-20261111):
        row=topology['summary'][f'fg-{seed}']
        require(row['detached_sky_terrain_components']==0 and
                row['village_suspended_piece_candidates']==0,'Floating terrain or village')
        require(row['native_structures_equal']==52 and row['native_structure_sets_equal']==21,
                'Native structures changed')
        envelope=read(technical/f'fg-{seed}-envelope.json')
        result=envelope['alpine_detail']
        require(result['peak_regions']>0 and result['sampled_columns']>=30
                and result['changed_voxels']>=50,'No observed 3-D rock detail')
        require(result['sign_reversals']==0,'New detached alpine rock column')
        require(result['max_absolute_density_correction']<=0.1201,'Excessive rock detail')
        require(result['deep_zero_checks']>0 and result['high_zero_checks']>0,
                'Non-local mountain correction')
        detail[str(seed)]=result
    result={**b,'actual_server_runs':23,
        'server_breakdown':{'paired_original_villages_and_reload':12,
                            'ordinary_minecraft_control':5,'structures_and_vanilla_controls':6},
        'run_url':f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
        'evidence':read(root/'index.json'),
        'paired_villages':pairs,'ordinary_minecraft_control':normal,
        'village_roof_after':totals(pairs,'terrain_after'),
        'village_objects_after':totals(pairs,'full_after'),
        'alpine_detail':detail,'structure_and_topology_summary':topology['summary'],
        'limitations':['Only 3 seeds and bounded real-world samples; not all mountains',
            'Controlled native 3-D detail only in high mountains; not restored full old 3-D geometry',
            'Native cave systems and height envelope remain unchanged, but new surface blocks can appear in new chunks',
            'Old saved chunks are not regenerated, so chunk seam transitions may be visible',
            'No client/shader/DH/C2ME testing; no measured FPS or heap improvement',
            'Village door warnings retained; villagers not pathfinding-tested']}
    out=P('dist');(out/'audits').mkdir(parents=True,exist_ok=True)
    for f in (jar,src):
        shutil.copyfile(f,out/f.name)
        (out/(f.name+'.sha256')).write_text(checksum(f)+'  '+f.name+'\n')
    save(out/f'validation-{VERSION}.json',result)
    evidence={str(f.relative_to(root)):read(f) for f in root.rglob('*.json')}
    evidence['structures']=topology
    (out/'audits'/f'mountain-detail-{VERSION}.json.gz').write_bytes(
        gzip.compress(json.dumps(evidence,separators=(',',':')).encode(),mtime=0))
    print('FRESHGENERATOR_MOUNTAIN_RELEASE_PASS '+json.dumps(result))

if __name__=='__main__':
    require(len(sys.argv)==2 and sys.argv[1] in ('build','publish'),'Usage: validate_mountain_release.py build|publish')
    {'build':build,'publish':publish}[sys.argv[1]]()
