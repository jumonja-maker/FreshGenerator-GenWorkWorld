"""Paired fixed coordinates with independent exact pre-feature terrain snapshots."""
import json,os,pathlib,sys
from cave_audit_metrics import compare_deep,compare_terrain_stage
p=pathlib.Path(sys.argv[1]);a,b,c=[json.loads((p/(s+'.json')).read_text()) for s in ['reference','candidate','candidate-reload']]
assert all(x['completed'] for x in [a,b,c])
assert a['source_commit']=='319c9060109ff29e894a35e45d92f604f845d2c5'
assert b['source_commit']==c['source_commit']==os.environ['GITHUB_SHA']
assert a['seed']==b['seed']==c['seed'] and a['settings']==b['settings']==c['settings']
assert a['pass']=='reference' and b['pass']=='candidate' and c['pass']=='candidate-reload'
normal=b['settings']=='minecraft:overworld';rows=[];before=after=deep_changed=raw_changed=state_changed=feature_changed=unexplained=0
stage=None
if not normal:
 stage=compare_terrain_stage(a['pre_feature_terrain'],b['pre_feature_terrain'])
 assert stage['deep_block_samples']>10000 and stage['chunks_compared']>=32,('Insufficient actual TERRAIN evidence',stage)
 assert len(a['pre_feature_terrain'])==len(a['regions'])
 assert any(w['label'].startswith('opening-rich') and w['deep_block_samples']>0 for w in a['pre_feature_terrain'])
 assert c['pre_feature_terrain']==[], 'Reload must not masquerade as a pre-feature observation'
 print('CAVE_TERRAIN_COMPARISON_PASS '+json.dumps({'seed':b['seed'],**stage}),flush=True)
else:
 assert a['pre_feature_terrain']==b['pre_feature_terrain']==c['pre_feature_terrain']==[]
assert len(a['regions'])==len(b['regions'])==len(c['regions'])
for old,new,reload in zip(a['regions'],b['regions'],c['regions']):
 for key in ['label','x','z','side','columns','envelope_sha256','deep_density_sha256']:
  assert old[key]==new[key]==reload[key],(key,old['label'])
 assert new==reload,('Saved FULL area changed on reopen',new['label'])
 deep=compare_deep(old,new)
 deep_changed+=deep['deep_terrain_occupancy_changes'];raw_changed+=deep['raw_deep_occupancy_changes'];state_changed+=deep['deep_block_state_changes']
 feature_changed+=deep['deep_feature_only_occupancy_changes'];unexplained+=deep['deep_unexplained_occupancy_changes']
 if deep['raw_deep_occupancy_changes']:
  print('CAVE_DEEP_OBSERVATIONS '+json.dumps({'seed':b['seed'],'label':old['label'],**deep}),flush=True)
 # Base geometry passed an independent, exact TERRAIN-stage comparison above.
 # FULL sculk sensors/shriekers are explicitly counted as changed decorations, not erased.
 assert deep['deep_unexplained_occupancy_changes']==0,('Unexplained deep occupancy changed',new['label'],deep)
 if normal:assert old==new,('Vanilla world modified',old['label'])
 if old['submerged_envelope_columns']==old['columns']:
  assert old['ground_y']==new['ground_y'] and old['water_y']==new['water_y'],('Submerged terrain changed',old['label'])
 before+=old['dry_opening_columns'];after+=new['dry_opening_columns']
 rows.append({'label':old['label'],'x':old['x'],'z':old['z'],'columns':old['columns'],
  'opening_columns_before':old['dry_opening_columns'],'opening_columns_after':new['dry_opening_columns'],
  'noise_opening_columns_before':old['noise_only_opening_columns'],'noise_opening_columns_after':new['noise_only_opening_columns'],
  'opening_regions_before':len(old['opening_regions']),'opening_regions_after':len(new['opening_regions']),
  'largest_opening_before':max((q['columns'] for q in old['opening_regions']),default=0),'largest_opening_after':max((q['columns'] for q in new['opening_regions']),default=0),
  'envelope_identical':True,'deep_density_identical':True,**deep,'save_reload_identical':True})
t=b['telemetry']
if normal:assert t['guard_instances']==0,t
else:
 assert b['native_policy_checks']>1000 and b['min_sampled_gate']<.05 and b['max_sampled_gate']>.95
 assert t['guard_instances']>0 and t['cave_blocked']+t['canyon_blocked']>0,t
 assert t['cave_deep_blocked']==t['canyon_deep_blocked']==0,t
 assert t['cave_deep_forwarded']>0 and t['canyon_forwarded']>0,t
 assert before>0 and after<before,('No measured reduction',before,after)
 assert after>0,('All sampled surface entrances disappeared',before,after)
result={'source_commit':os.environ['GITHUB_SHA'],'reference_source':a['source_commit'],'seed':b['seed'],'settings':b['settings'],
 'actual_server_runs':3,'explicit_full_chunks_per_pass':b['full_chunks_requested'],'regions':rows,'telemetry':t,
 'opening_columns_before':before,'opening_columns_after':after,'pre_feature_terrain_comparison':stage,
 'deep_terrain_occupancy_changes':deep_changed,'deep_feature_only_occupancy_changes':feature_changed,'deep_unexplained_occupancy_changes':unexplained,
 'raw_deep_occupancy_changes':raw_changed,'deep_block_state_changes':state_changed,
 'native_policy_checks':b['native_policy_checks'],'min_sampled_gate':b['min_sampled_gate'],'max_sampled_gate':b['max_sampled_gate'],
 'deep_classification':'Exact pre-feature TERRAIN states checked separately. FULL raw states retained; dry sculk veins are coatings; changed sensors/shriekers are counted as features, never asserted identical.',
 'scope':'same finite 64x64 windows; vertical dry opening-area proxy, not exact count of cave networks or all side entrances; structures checked separately'}
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_CAVE_COMPARISON_PASS '+json.dumps(result))
