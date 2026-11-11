"""Download pinned research-only tree surveys; emit aggregates, never copy points into the mod."""
import csv, hashlib, json, os, pathlib, subprocess, urllib.request
PIN='3316eca0ef691ee8717fb4421918dd95978b5560'
BASE=f'https://raw.githubusercontent.com/spatstat/spatstat.data/{PIN}'
p=pathlib.Path('build/research-cache');p.mkdir(parents=True,exist_ok=True)
sources={}
for name in ['lansing','hyytiala','bei']:
    provenance={}
    for file in [f'data/{name}.rda',f'man/{name}.Rd','DESCRIPTION']:
        body=urllib.request.urlopen(f'{BASE}/{file}',timeout=60).read()
        target=p/pathlib.Path(file).name;target.write_bytes(body)
        provenance[file]={'url':f'{BASE}/{file}','sha256':hashlib.sha256(body).hexdigest(),'bytes':len(body)}
    sources[name]=provenance
subprocess.run(['Rscript','scripts/forest_reference_data.R',str(p)],check=True)
result={'source_commit':os.environ.get('GITHUB_SHA','local'),'upstream_commit':PIN,
        'method':'Independent base-R aggregates; 64 uniform fixed-count controls, same window. No spatstat algorithms copied.',
        'upstream_package_license':'GPL >=2; raw datasets and package code are NOT distributed in the mod or repository.',
        'data_usage':'Research observations only; no direct conversion of empirical stems/ha to Minecraft trees/chunk.',
        'datasets':{}}
notes={
'lansing':'Mixed woodland, 924ft square rescaled to metres (281.6352m side). Duplicate coordinates are retained and counted. Stem map is not canopy cover.',
'hyytiala':'Mixed pine-dominated 20x20m sample. Very small plot: not a universal boreal density estimate, no landscape-sized gaps measurable.',
'bei':'One species, Beilschmiedia pendula, in 1000x500m tropical plot. Empty quadrats are NOT canopy gaps; manual reports 3605, verify actual rows below.'}
for name in sources:
    rows=list(csv.DictReader((p/(name+'-summary.csv')).open()))
    result['datasets'][name]={'provenance':sources[name],'limitations':notes[name],
       'statistics':{r['metric']:float(r['value']) for r in rows},
       'species_counts':dict((r['species'],int(r['count'])) for r in csv.DictReader((p/(name+'-species.csv')).open()))}
result['not_collected']=['Satellite tiles/GEDI footprints: catalogue reviewed only; not analysed here','Tree ages, crown shapes, actual flood return periods and soil moisture','Finpines excluded from calibration: manual cautions about coordinate perturbation and overlap']
out=pathlib.Path('research/forest-reference-statistics.json');out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(result,indent=2)+'\n')
print('FOREST_REFERENCE_DATA_PASS '+json.dumps(result))
