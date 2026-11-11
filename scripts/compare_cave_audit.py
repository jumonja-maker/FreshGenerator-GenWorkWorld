"""Report the same windows, including surviving openings. Do not call these exact cave counts."""
import base64,json,os,pathlib,sys
p=pathlib.Path(sys.argv[1]);a,b,c=[json.loads((p/(s+'.json')).read_text()) for s in ['reference','candidate','candidate-reload']]
assert all(x['completed'] for x in [a,b,c])
assert a['source_commit']=='319c9060109ff29e894a35e45d92f604f845d2c5'
assert b['source_commit']==c['source_commit']==os.environ['GITHUB_SHA']
assert a['seed']==b['seed']==c['seed'] and a['settings']==b['settings']==c['settings']
normal=b['settings']=='minecraft:overworld';rows=[];before=after=deep_changed=0
assert len(a['regions'])==len(b['regions'])==len(c['regions'])
for old,new,reload in zip(a['regions'],b['regions'],c['regions']):
 for key in ['label','x','z','side','columns','envelope_sha256','deep_density_sha256']:
  assert old[key]==new[key]==reload[key],(key,old['label'])
 assert new==reload,('Saved FULL area changed on reopen',new['label'])
 left=base64.b64decode(old['deep_occupancy_base64']);right=base64.b64decode(new['deep_occupancy_base64']);assert len(left)==len(right)
 delta=sum(x!=y for x,y in zip(left,right));deep_changed+=delta
 # Deeper geometry is required equal, not merely statistically similar.
 assert delta==0,('Deep occupancy changed',new['label'],delta)
 if normal:assert old==new,('Vanilla world modified',old['label'])
 if old['submerged_envelope_columns']==old['columns']:
  assert old['ground_y']==new['ground_y'] and old['water_y']==new['water_y'],('Submerged terrain changed',old['label'])
 before+=old['dry_opening_columns'];after+=new['dry_opening_columns']
 rows.append({'label':old['label'],'x':old['x'],'z':old['z'],'columns':old['columns'],
  'opening_columns_before':old['dry_opening_columns'],'opening_columns_after':new['dry_opening_columns'],
  'noise_opening_columns_before':old['noise_only_opening_columns'],'noise_opening_columns_after':new['noise_only_opening_columns'],
  'opening_regions_before':len(old['opening_regions']),'opening_regions_after':len(new['opening_regions']),
  'largest_opening_before':max((q['columns'] for q in old['opening_regions']),default=0),'largest_opening_after':max((q['columns'] for q in new['opening_regions']),default=0),
  'envelope_identical':True,'deep_density_identical':True,'deep_occupancy_samples':len(left),'deep_occupancy_changes':delta,'deep_air_before':old['deep_air_samples'],'deep_air_after':new['deep_air_samples'],
  'save_reload_identical':True})
t=b['telemetry']
if normal:assert t['guard_instances']==0,t
else:
 assert b['native_policy_checks']>1000 and b['min_sampled_gate']<.05 and b['max_sampled_gate']>.95
 assert t['guard_instances']>0 and t['cave_blocked']+t['canyon_blocked']>0,t
 assert t['cave_deep_blocked']==t['canyon_deep_blocked']==0,t
 assert t['cave_deep_forwarded']>0 and t['canyon_forwarded']>0,t
 assert before>0 and after<before,('No measured reduction',before,after)
 # Some openings MUST survive; a global cave-off patch is not acceptable.
 assert after>0,('All sampled surface entrances disappeared',before,after)
result={'source_commit':os.environ['GITHUB_SHA'],'reference_source':a['source_commit'],'seed':b['seed'],'settings':b['settings'],
 'actual_server_runs':3,'explicit_full_chunks_per_pass':b['full_chunks_requested'],'regions':rows,'telemetry':t,
 'opening_columns_before':before,'opening_columns_after':after,'deep_occupancy_changes':deep_changed,
 'native_policy_checks':b['native_policy_checks'],'min_sampled_gate':b['min_sampled_gate'],'max_sampled_gate':b['max_sampled_gate'],
 'scope':'same finite 64x64 windows; vertical dry opening-area proxy, not exact count of cave networks or all side entrances; structures checked separately'}
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_CAVE_COMPARISON_PASS '+json.dumps(result))
