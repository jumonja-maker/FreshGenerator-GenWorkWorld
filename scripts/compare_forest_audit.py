"""Require real paired measurements; a count of log columns is not an exact tree count."""
import json,pathlib,sys,os
p=pathlib.Path(sys.argv[1]);a,b,c=[json.loads((p/(s+'.json')).read_text()) for s in ['reference','candidate','candidate-reload']]
assert a['completed'] and b['completed'] and c['completed']
assert a['source_commit']=='75f7c44d515257edfd821b0f607f02af39e73ade'
assert b['source_commit']==c['source_commit']==os.environ['GITHUB_SHA']
assert a['seed']==b['seed']==c['seed'] and a['settings']==b['settings']==c['settings']
assert len(a['regions'])==len(b['regions'])==len(c['regions'])
normal=b['settings']=='minecraft:overworld';comparison=[];total_old=total_new=0
for old,new,reload in zip(a['regions'],b['regions'],c['regions']):
    for key in ['label','x','z','side','columns','base_geometry_biome_sha256']:
        assert old[key]==new[key]==reload[key],(key,old['label'])
    assert new==reload,('Saved FULL area changed on reload',new['label'])
    if normal or old['label']=='plains-control':
        assert old==new,('Out of scope blocks changed',old['label'])
    elif old['label']!='montane':
        assert old['supported_log_columns']>10 and new['supported_log_columns']>10,old['label']
        ratio=new['supported_log_columns']/old['supported_log_columns']
        assert .4<=ratio<=1.35,('Unacceptable density regression',old['label'],ratio)
        total_old+=old['supported_log_columns'];total_new+=new['supported_log_columns']
    comparison.append({'label':old['label'],'x':old['x'],'z':old['z'],'columns':old['columns'],
      'geometry_and_biomes_identical':True,'save_reload_identical':True,
      'supported_log_columns_before':old['supported_log_columns'],'supported_log_columns_after':new['supported_log_columns'],
      'canopy_cover_before':old['canopy_projection_fraction'],'canopy_cover_after':new['canopy_projection_fraction'],
      'empty_stem_cells_before':old['empty_16x16_stem_cells'],'empty_stem_cells_after':new['empty_16x16_stem_cells'],
      'quadrat_variance_mean_before':old['stem_quadrat_variance_mean'],'quadrat_variance_mean_after':new['stem_quadrat_variance_mean']})
t=b['telemetry'];attempts=sum(v for k,v in t.items() if k.endswith('_attempts') and 'opening' not in k)
kept=sum(v for k,v in t.items() if k.endswith('_kept'))
if normal:assert attempts==0
else:
    assert attempts>100 and 0<kept<attempts,('Mixin not exercised',t)
    assert total_new>=.6*total_old and total_new<=1.25*total_old,('Overall forest density changed excessively',total_old,total_new)
    for family in ['TEMPERATE','CONIFER','TROPICAL']:assert t[family+'_attempts']>0,(family,t)
result={'source_commit':os.environ['GITHUB_SHA'],'seed':b['seed'],'settings':b['settings'],'actual_server_runs':3,
 'explicit_full_chunks_per_pass':b['full_chunks_requested'],'regions':comparison,'telemetry':t,
 'paired_geometry_and_biomes':'identical','save_reload':'identical','scope':'finite server sample; ecological realism and client appearance NOT established by these checks'}
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_FOREST_COMPARISON_PASS '+json.dumps(result))
