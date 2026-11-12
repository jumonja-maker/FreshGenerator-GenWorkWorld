"""Read preserved unsuccessful evidence without modifying its observations."""
import base64,gzip,hashlib,json,pathlib
for path in sorted(pathlib.Path('research/rejected-candidates').glob('village-*-9d114f3.json.gz')):
 bundle=json.loads(gzip.decompress(path.read_bytes()))
 print('EVIDENCE_IDENTITY',path.name,json.dumps(bundle['identity'],sort_keys=True))
 for name,entry in sorted(bundle['files'].items()):
  raw=base64.b64decode(entry['content'],validate=True)
  assert hashlib.sha256(raw).hexdigest()==entry['sha256']
  if name not in ('baseline.json','candidate.json','reload.json','locations.json'):continue
  obj=json.loads(raw)
  if isinstance(obj,list):print(name,json.dumps(obj));continue
  print('PHASE',name,'completed',obj.get('completed'),'failure',obj.get('failure'))
  for stage in ('terrain','villages'):
   for row in obj.get(stage,[]):
    out={k:v for k,v in row.items() if k!='sites'}
    out['site_count']=len(row.get('sites',[]));out['rigid_count']=sum(s['rigid'] for s in row.get('sites',[]))
    print(stage,json.dumps(out,sort_keys=True))
