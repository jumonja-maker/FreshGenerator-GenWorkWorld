"""Strict bounded village evidence; do not normalize blocks or hide warnings."""
import json,sys
from pathlib import Path
BASE='7c661cecbc42328c4ef099b034393cc36afb8b50'
def require(ok,message):
 if not ok:raise ValueError(message)
def identity(r):return {k:r[k] for k in ('id','chunkX','chunkZ','sites','pieces')}
def compare(folder,sha,seed):
 p=Path(folder);a,b,c=[json.loads((p/(n+'.json')).read_text()) for n in ('baseline','candidate','reload')]
 for r,phase,source in ((a,'baseline',BASE),(b,'candidate',sha),(c,'reload',sha)):
  require(r['completed'] is True and r['seed']==seed and r['phase']==phase and r['source_commit']==source,'Wrong/incomplete village run')
  require('FRESHGENERATOR_VILLAGE_AUDIT_PASS' in (p/(phase+'.log')).read_text(),'Missing native completion')
 require(len(a['villages'])==len(b['villages'])==len(c['villages'])>=2,'Missing villages')
 require(b['villages']==c['villages'],'Saved village changed on reload')
 require(any(r['id']=='minecraft:village_savanna' for r in b['villages']),'No savanna village')
 for old,new in zip(a['terrain'],b['terrain']):
  require(identity(old)==identity(new),'Native structure layout changed')
  require(old['predecorated_columns_not_used']==new['predecorated_columns_not_used']==0,'Terrain samples were already decorated')
  require(old['terrain_columns']==new['terrain_columns']>0,'Missing terrain coverage')
  require(old['deep_sha256']==new['deep_sha256'],'Deep terrain changed outside protection depth')
  require(old['outside_sha256']==new['outside_sha256'],'Terrain outside village influence changed')
  require(new['protected_roof_columns']>=50 and new['rigid_columns']>0,'Missing protected plots')
  require(new['roof_failure_columns']==0,'Protected roof still has voids')
  require(new['open_hole_columns']==0,'Protected surface still contains holes')
  require(new['rigid_grade_error_columns']<=old['rigid_grade_error_columns'],'Rigid plot grades regressed')
 for old,new in zip(a['villages'],b['villages']):
  require(identity(old)==identity(new),'Full structure layout changed')
  require(new['doors']==old['doors']>0,'Lost village doors')
  require(new['path_blocks']>0 and new['farmland_blocks']>0,'Missing actual paths/farms')
  require(new['path_columns_with_void_within_8']<=old['path_columns_with_void_within_8'],'Path support regressed')
  require(new['farmland_columns_with_void_within_8']<=old['farmland_columns_with_void_within_8'],'Farm support regressed')
  require(new['door_exit_warnings']<=old['door_exit_warnings'],'Door access regressed')
 result={'source_commit':sha,'reference_commit':BASE,'seed':seed,'actual_server_runs':3,'villages':len(b['villages']),
  'terrain_before':a['terrain'],'terrain_after':b['terrain'],'full_before':a['villages'],'full_after':b['villages'],
  'saved_world_equal':True,'explicit_full_chunks_per_pass':b['explicit_full_chunks'],
  'limits':'Fixed real villages; coverage at two-block terrain stride and four-block FULL hash stride. Door screening is not villager pathfinding.'}
 (p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_VILLAGE_PAIR_PASS '+json.dumps(result));return result
if __name__=='__main__':compare(sys.argv[1],sys.argv[2],int(sys.argv[3]))
