"""Exact pre-feature terrain, bounded and fully reported post-feature drift, independent A/A control."""
import json,os,pathlib,sys
from cave_audit_metrics import compare_deep,compare_terrain_stage
from cave_full_stage_checks import compare_full_stage
p=pathlib.Path(sys.argv[1]);read=lambda label:json.loads((p/(label+'.json')).read_text())
a,b,c=[read(s) for s in ['reference','candidate','candidate-reload']]
assert all(x['completed'] for x in [a,b,c])
assert a['source_commit']=='319c9060109ff29e894a35e45d92f604f845d2c5'
assert b['source_commit']==c['source_commit']==os.environ['GITHUB_SHA']
assert a['seed']==b['seed']==c['seed'] and a['settings']==b['settings']==c['settings']
assert a['pass']=='reference' and b['pass']=='candidate' and c['pass']=='candidate-reload'
normal=b['settings']=='minecraft:overworld';rows=[];before=after=0
stage=aa_stage=None;repeat=None;aa_rows=[]
if not normal:
 repeat=read('reference-repeat')
 assert repeat['completed'] and repeat['source_commit']==a['source_commit'] and repeat['pass']=='reference-repeat'
 assert repeat['seed']==a['seed'] and repeat['settings']==a['settings']
 stage=compare_terrain_stage(a['pre_feature_terrain'],b['pre_feature_terrain'])
 aa_stage=compare_terrain_stage(a['pre_feature_terrain'],repeat['pre_feature_terrain'])
 assert stage['deep_block_samples']>10000 and stage['chunks_compared']>=32,stage
 assert len(a['pre_feature_terrain'])==len(a['regions'])==len(repeat['regions'])
 assert any(w['label'].startswith('opening-rich') and w['deep_block_samples']>0 for w in a['pre_feature_terrain'])
 assert c['pre_feature_terrain']==[]
 print('CAVE_TERRAIN_AAB_PASS '+json.dumps({'seed':b['seed'],'AB':stage,'AA':aa_stage}),flush=True)
else:assert a['pre_feature_terrain']==b['pre_feature_terrain']==c['pre_feature_terrain']==[]
assert len(a['regions'])==len(b['regions'])==len(c['regions'])
for i,(old,new,reload) in enumerate(zip(a['regions'],b['regions'],c['regions'])):
 for key in ['label','x','z','side','columns','envelope_sha256','deep_density_sha256']:
  assert old[key]==new[key]==reload[key],(key,old['label'])
  if repeat is not None:assert old[key]==repeat['regions'][i][key],('Reference repeat',key,old['label'])
 assert new==reload,('Saved FULL area changed on reopen',new['label'])
 deep=compare_deep(old,new)
 # Historical metric names are retained in raw tests only; these are FULL-stage observations.
 deep['full_occupancy_changes']=deep.pop('deep_terrain_occupancy_changes')
 deep['full_changes_not_classified_by_sculk_names']=deep.pop('deep_unexplained_occupancy_changes')
 phase=None
 if not normal:
  phase=compare_full_stage(old,new,a['pre_feature_terrain'][i],b['pre_feature_terrain'][i])
  control=compare_full_stage(old,repeat['regions'][i],a['pre_feature_terrain'][i],repeat['pre_feature_terrain'][i])
  aa=compare_deep(old,repeat['regions'][i])
  aa_rows.append({'label':old['label'],'stage_comparison':control,'full_block_state_changes':aa['deep_block_state_changes'],
                 'raw_occupancy_changes':aa['raw_deep_occupancy_changes'],'first_state_changes':aa['first_deep_state_changes']})
 else:assert old==new,('Vanilla world modified',old['label'])
 if old['submerged_envelope_columns']==old['columns']:
  assert old['ground_y']==new['ground_y'] and old['water_y']==new['water_y'],('Submerged terrain changed',old['label'])
 before+=old['dry_opening_columns'];after+=new['dry_opening_columns']
 rows.append({'label':old['label'],'x':old['x'],'z':old['z'],'columns':old['columns'],
  'opening_columns_before':old['dry_opening_columns'],'opening_columns_after':new['dry_opening_columns'],
  'noise_opening_columns_before':old['noise_only_opening_columns'],'noise_opening_columns_after':new['noise_only_opening_columns'],
  'opening_regions_before':len(old['opening_regions']),'opening_regions_after':len(new['opening_regions']),
  'largest_opening_before':max((q['columns'] for q in old['opening_regions']),default=0),'largest_opening_after':max((q['columns'] for q in new['opening_regions']),default=0),
  'envelope_identical':True,'deep_density_identical':True,'full_stage_observations':deep,'full_stage_budget':phase,'save_reload_identical':True})
 if deep['raw_deep_occupancy_changes']:
  print('CAVE_POST_FEATURE_OBSERVATIONS '+json.dumps({'seed':b['seed'],'label':old['label'],'budget':phase,'observations':deep}),flush=True)
t=b['telemetry']
if normal:assert t['guard_instances']==0,t
else:
 assert b['native_policy_checks']>1000 and b['min_sampled_gate']<.05 and b['max_sampled_gate']>.95
 assert t['guard_instances']>0 and t['cave_blocked']+t['canyon_blocked']>0,t
 assert t['cave_deep_blocked']==t['canyon_deep_blocked']==0,t
 assert t['cave_deep_forwarded']>0 and t['canyon_forwarded']>0,t
 assert before>after>0,('No reduction or all sampled openings removed',before,after)
result={'source_commit':os.environ['GITHUB_SHA'],'reference_source':a['source_commit'],'seed':b['seed'],'settings':b['settings'],
 'actual_server_runs':3 if normal else 4,'explicit_full_chunks_per_pass':b['full_chunks_requested'],'regions':rows,'telemetry':t,
 'opening_columns_before':before,'opening_columns_after':after,'pre_feature_terrain_comparison':stage,
 'reference_repeat_control':None if normal else {'pre_feature_terrain_comparison':aa_stage,'regions':aa_rows},
 'post_feature_occupancy_changes':sum(r['full_stage_observations']['full_occupancy_changes'] for r in rows),
 'post_feature_fluid_occupancy_changes':sum(r['full_stage_budget']['post_feature_fluid_occupancy_changes'] for r in rows if r['full_stage_budget']),
 'raw_deep_occupancy_changes':sum(r['full_stage_observations']['raw_deep_occupancy_changes'] for r in rows),
 'deep_block_state_changes':sum(r['full_stage_observations']['deep_block_state_changes'] for r in rows),
 'native_policy_checks':b['native_policy_checks'],'min_sampled_gate':b['min_sampled_gate'],'max_sampled_gate':b['max_sampled_gate'],
 'validation_policy':'Exact actual TERRAIN states, no exclusions. All FULL changes retained; occupancy capped at min(128,max(8,floor(n*.005))) and fluid changes at min(32,max(4,floor(n*.002))) per sampled window. Budgets are engineering bounds, not a claim of identical decorated caves.',
 'scope':'same finite 64x64 windows; vertical dry opening area, not exact cave counts or all side entrances; structures checked separately'}
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_CAVE_COMPARISON_PASS '+json.dumps(result))
