"""Transfer bounded CI evidence through Git blobs in this same private repository.
No branches/tags/releases are created, no existing files/artifacts are deleted. Only
40-character object IDs cross job outputs; the publisher revalidates every byte and
source/run identity before using the files. Successful evidence is retained in dist.
"""
import argparse,base64,gzip,hashlib,io,json,os,re,time,urllib.error,urllib.request
from pathlib import Path,PurePosixPath

MAX_PACKED=16*1024*1024
MAX_RAW=64*1024*1024
BUNDLES={
 'build_blob':'technical-build',
 'world_fresh_42':'technical-world-fresh-42',
 'world_fresh_1337':'technical-world-fresh-1337',
 'world_fresh_negative':'technical-world-fresh--20261111',
 'world_normal_42':'technical-world-normal-42',
 'structures_42':'technical-structures-42',
 'structures_1337':'technical-structures-1337',
 'structures_negative':'technical-structures--20261111',
}

def require(ok,message):
 if not ok:raise ValueError(message)

def provenance(env=os.environ):
 result={k:env[k] for k in ('GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_REPOSITORY')}
 require(re.fullmatch(r'[0-9a-f]{40}',result['GITHUB_SHA']),'Invalid source SHA')
 require(all(result[k].isdigit() for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')),'Invalid run identity')
 require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',result['GITHUB_REPOSITORY']),'Invalid repository')
 return result

def valid_name(name):
 require(isinstance(name,str) and bool(name) and '\\' not in name and '\x00' not in name,'Invalid filename')
 p=PurePosixPath(name)
 require(not p.is_absolute() and all(x not in ('','.','..') for x in name.split('/')),'Unsafe filename')
 require(len(name)<512,'Filename too long')
 return p

def blob_sha(data):return hashlib.sha1(('blob '+str(len(data))+'\0').encode()+data).hexdigest()

def pack(root,patterns,name,identity):
 root=Path(root).resolve();files={};total=0
 for pattern in patterns:
  for p in sorted(root.glob(pattern)):
   if not p.is_file():continue
   require(not p.is_symlink() and p.resolve().is_relative_to(root),'Symlink/outside evidence root')
   key=p.relative_to(root).as_posix();valid_name(key)
   if key in files:continue
   raw=p.read_bytes();total+=len(raw);require(total<=MAX_RAW,'Evidence exceeds size budget')
   files[key]={'sha256':hashlib.sha256(raw).hexdigest(),'content':base64.b64encode(raw).decode('ascii')}
 require(0<len(files)<=5000,'No evidence or too many files')
 raw=json.dumps({'schema':1,'name':name,'identity':identity,'files':files},sort_keys=True,separators=(',',':')).encode()
 require(len(raw)<=MAX_RAW,'Envelope exceeds size budget')
 data=gzip.compress(raw,mtime=0);require(len(data)<=MAX_PACKED,'Compressed evidence too large')
 return data

def unpack(data,name,identity,destination):
 require(len(data)<=MAX_PACKED,'Oversized compressed evidence')
 with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:raw=stream.read(MAX_RAW+1)
 require(len(raw)<=MAX_RAW,'Oversized decoded evidence')
 bundle=json.loads(raw)
 require(bundle['schema']==1 and bundle['name']==name,'Wrong bundle kind')
 require(bundle['identity']==identity,'Evidence is from a different commit/run/repository')
 require(isinstance(bundle['files'],dict) and 0<len(bundle['files'])<=5000,'Missing files')
 validated={}
 for key,entry in bundle['files'].items():
  valid_name(key);content=base64.b64decode(entry['content'],validate=True)
  require(hashlib.sha256(content).hexdigest()==entry['sha256'],'File fingerprint mismatch: '+key)
  validated[key]=content
 destination=Path(destination);require(not destination.exists(),'Refusing to overwrite prior evidence')
 destination.mkdir(parents=True)
 for key,content in validated.items():
  path=destination/key;path.parent.mkdir(parents=True,exist_ok=True)
  with path.open('xb') as out:out.write(content)
 return {'name':name,'files':len(validated),'sha256':hashlib.sha256(data).hexdigest(),'git_blob':blob_sha(data)}

def request(endpoint,payload=None):
 repo=provenance()['GITHUB_REPOSITORY']
 # Deliberately fixed to GitHub, not an arbitrary URL supplied in an artifact.
 url='https://api.github.com/repos/'+repo+'/git/blobs'+endpoint
 headers={'Accept':'application/vnd.github+json','Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],
          'X-GitHub-Api-Version':'2022-11-28','User-Agent':'FreshGenerator-CI','Content-Type':'application/json'}
 body=None if payload is None else json.dumps(payload).encode()
 for attempt in range(3):
  try:
   with urllib.request.urlopen(urllib.request.Request(url,data=body,headers=headers),timeout=90) as response:
    result=response.read(2*MAX_RAW+1);require(len(result)<=2*MAX_RAW,'Oversized API response');return json.loads(result)
  except urllib.error.HTTPError as error:
   if error.code not in (429,500,502,503,504) or attempt==2:
    raise RuntimeError('Git evidence API failed, HTTP '+str(error.code)) from None
   time.sleep(2**attempt)
 raise RuntimeError('Unreachable retry state')

def put(key,root,patterns):
 require(key in BUNDLES,'Unknown evidence key')
 data=pack(root,patterns,BUNDLES[key],provenance())
 result=request('',{'content':base64.b64encode(data).decode('ascii'),'encoding':'base64'})
 require(result['sha']==blob_sha(data),'Upload object fingerprint mismatch')
 with open(os.environ['GITHUB_OUTPUT'],'a') as out:out.write(key+'='+result['sha']+'\n')
 print('CI_EVIDENCE_STORED '+json.dumps({'name':BUNDLES[key],'blob':result['sha'],'bytes':len(data)}))

def get_all(needs,destination):
 outputs={}
 for name in ('build','worlds','structures'):
  job=needs[name];require(job['result']=='success','Failed upstream job: '+name)
  for key,value in job['outputs'].items():
   if not value:continue
   require(key in BUNDLES and key not in outputs,'Unexpected/duplicate evidence output')
   require(re.fullmatch(r'[0-9a-f]{40}',value),'Invalid blob output');outputs[key]=value
 require(set(outputs)==set(BUNDLES),'Missing evidence outputs')
 identity=provenance();index=[]
 for key,sha in sorted(outputs.items()):
  result=request('/'+sha);require(result['sha']==sha and result['encoding']=='base64','Wrong blob response')
  data=base64.b64decode(result['content']);require(len(data)==result['size'] and blob_sha(data)==sha,'Downloaded object fingerprint mismatch')
  index.append(unpack(data,BUNDLES[key],identity,Path(destination)/BUNDLES[key]))
 Path(destination,'index.json').write_text(json.dumps({'identity':identity,'bundles':index},indent=2)+'\n')
 print('CI_EVIDENCE_RESTORED '+json.dumps(index))

if __name__=='__main__':
 parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='mode',required=True)
 p=sub.add_parser('put');p.add_argument('key',choices=BUNDLES);p.add_argument('root');p.add_argument('patterns',nargs='+')
 p=sub.add_parser('get');p.add_argument('destination')
 args=parser.parse_args()
 if args.mode=='put':put(args.key,args.root,args.patterns)
 else:get_all(json.loads(os.environ['EVIDENCE_JOBS']),args.destination)
