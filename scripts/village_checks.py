"""Strict paired village evidence. Missing native farms/road materials are not defects.
Do not normalize blocks, drop failed villages, or infer a pass from configuration.
"""
import json,re,sys
from pathlib import Path
BASE='7c661cecbc42328c4ef099b034393cc36afb8b50'
COUNTS=('terrain_columns','predecorated_columns_not_used','protected_roof_columns','roof_failure_columns',
        'open_hole_columns','rigid_columns','rigid_grade_error_columns')
FULL_COUNTS=('path_blocks','path_columns_with_void_within_8','farmland_blocks',
             'farmland_columns_with_void_within_8','doors','door_exit_warnings',
             'protected_path_air_or_lava_columns','protected_farm_air_or_lava_columns','protected_floor_lava_columns')
def require(ok,message):
 if not ok:raise ValueError(message)
def identity(r):return {k:r[k] for k in ('id','chunkX','chunkZ','sites','pieces')}
def counts(row,keys):
 for k in keys:require(type(row[k]) is int and row[k]>=0,'Invalid measured counter: '+k)
def fingerprint(value):require(isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value),'Missing/invalid block fingerprint')
def validate(a,b,c,sha,seed,minimum_villages=3):
 for r,phase,source in ((a,'baseline',BASE),(b,'candidate',sha),(c,'reload',sha)):
  require(r['completed'] is True and r['seed']==seed and r['phase']==phase and r['source_commit']==source,'Wrong/incomplete village run')
  require(r['settings']=='freshgenerator:highlands_v2','Wrong world settings')
  require(len(r['villages'])>=minimum_villages,'Missing villages')
  require(len(r['terrain'])==len(r['villages']),'Terrain/village count differs')
  require(len({(v['id'],v['chunkX'],v['chunkZ']) for v in r['villages']})==len(r['villages']),'Duplicate village evidence')
  require(type(r['explicit_full_chunks']) is int and r['explicit_full_chunks']>0,'Missing FULL chunks')
 require(len(a['villages'])==len(b['villages'])==len(c['villages']),'Paired village count differs')
 require(b['villages']==c['villages'],'Saved village changed on reload')
 require(any(r['id']=='minecraft:village_savanna' for r in b['villages']),'No savanna village')
 for old,new in zip(a['terrain'],b['terrain']):
  require(identity(old)==identity(new),'Native structure layout changed')
  counts(old,COUNTS);counts(new,COUNTS)
  require(old['predecorated_columns_not_used']==new['predecorated_columns_not_used']==0,'Terrain samples were already decorated')
  require(old['terrain_columns']==new['terrain_columns']>0,'Missing terrain coverage')
  for k in ('deep_sha256','outside_sha256'):fingerprint(old[k]);fingerprint(new[k]);require(old[k]==new[k],k+' changed outside village protection')
  require(new['protected_roof_columns']>=50 and new['rigid_columns']>0,'Missing protected plots')
  require(new['roof_failure_columns']==0,'Protected roof still has voids')
  require(new['open_hole_columns']==0,'Protected surface still contains holes')
  require(new['rigid_grade_error_columns']<=old['rigid_grade_error_columns'],'Rigid plot grades regressed')
 for old,new,terrain in zip(a['villages'],b['villages'],b['terrain']):
  require(identity(old)==identity(new)==identity(terrain),'Full/terrain structure layout changed')
  counts(old,FULL_COUNTS);counts(new,FULL_COUNTS);fingerprint(old['full_sha256']);fingerprint(new['full_sha256'])
  require(new['doors']==old['doors']>0,'Lost village doors')
  require(new['path_blocks']>=old['path_blocks'],'Lost baseline dirt-path blocks')
  require(new['farmland_blocks']>=old['farmland_blocks'],'Lost baseline farmland blocks')
  require(new['path_columns_with_void_within_8']<=old['path_columns_with_void_within_8'],'Path support regressed')
  require(new['farmland_columns_with_void_within_8']<=old['farmland_columns_with_void_within_8'],'Farm support regressed')
  require(new['door_exit_warnings']<=old['door_exit_warnings'],'Door access regressed')
  for field in ('protected_path_air_or_lava_columns','protected_farm_air_or_lava_columns','protected_floor_lava_columns'):
   require(new[field]==0,'Late feature breached protected ground: '+field)
 require(sum(r['path_blocks'] for r in a['villages'])>0,'No actual dirt paths in baseline coverage')
 require(sum(r['farmland_blocks'] for r in a['villages'])>0,'No actual farms in baseline coverage')
 return {'source_commit':sha,'reference_commit':BASE,'seed':seed,'actual_server_runs':3,'villages':len(b['villages']),
  'terrain_before':a['terrain'],'terrain_after':b['terrain'],'full_before':a['villages'],'full_after':b['villages'],
  'saved_world_equal':True,'explicit_full_chunks_per_pass':b['explicit_full_chunks'],
  'limits':'Fixed actual villages; two-block TERRAIN stride and four-block FULL hash stride. Dirt-path counts do not measure desert roads. Door screening is not villager AI; residual support warnings are retained.'}
def compare(folder,sha,seed):
 p=Path(folder);reports=[json.loads((p/(n+'.json')).read_text()) for n in ('baseline','candidate','reload')]
 for phase in ('baseline','candidate','reload'):
  require('FRESHGENERATOR_VILLAGE_AUDIT_PASS' in (p/(phase+'.log')).read_text(),'Missing native completion')
 result=validate(*reports,sha,seed)
 (p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_VILLAGE_PAIR_PASS '+json.dumps(result));return result
if __name__=='__main__':compare(sys.argv[1],sys.argv[2],int(sys.argv[3]))
