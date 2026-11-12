"""Fixed-coordinate native A/B; isolated indexes and exact climate reconstruction.
No baseline mismatch is ignored. Equal-distance shared-index effects are recorded separately.
"""
import json,pathlib,os,sys
from biome_region_metrics import decode,stats
p=pathlib.Path(sys.argv[1]);read=lambda label:json.loads((p/(label+'.json')).read_text())
a,b,c=[read(label) for label in ['reference','candidate','candidate-reload']]
assert a['source_commit']=='217d55ceeceb6f9c690fa281698af07ff069a1ed'
assert b['source_commit']==c['source_commit']==os.environ['GITHUB_SHA']
assert all(r['completed'] for r in [a,b,c]);assert a['seed']==b['seed']==c['seed'] and a['settings']==b['settings']==c['settings']
assert b['maps']==c['maps'] and b['full_windows']==c['full_windows'], 'Candidate changed on save/reload'
assert len(a['maps'])==len(b['maps']);maps=[]
for old,new in zip(a['maps'],b['maps']):
 for key in ['name','side','step','x','z','geography_sha256','deep_sha256']:assert old[key]==new[key],key
 before=decode(old);after=decode(new);reconstructed=decode(new,'reconstructed_reference_cells')
 mismatches=[i for i,(x,y) in enumerate(zip(before,reconstructed)) if x!=y]
 row={'name':old['name'],'before':stats(before,old['side'],old['step']),'after':stats(after,new['side'],new['step']),
      'changed_biome_samples':sum(x!=y for x,y in zip(before,after)),
      'reference_reconstruction_mismatches':len(mismatches),
      'surface_reference_climate_identical':old['surface_climate_sha256']==new['reconstructed_surface_climate_sha256'],
      'shared_index_equal_distance_disagreements':new['shared_index_equal_distance_disagreements'],
      'shared_index_tie_examples':new['shared_index_tie_examples'],
      'geography_and_deep_climate_identical':True}
 # Emit useful diagnostics BEFORE assertions, never publish this as a pass on failure.
 print('BIOME_REGION_MAP_EVIDENCE',b['seed'],old['name'],json.dumps({
   'reference_climate_equal':row['surface_reference_climate_identical'],'reconstruction_mismatches':len(mismatches),
   'first_reconstruction_differences':[{'x':old['x']+(i//old['side'])*old['step'],'z':old['z']+(i%old['side'])*old['step'],
                                    'real_reference':before[i],'reconstructed':reconstructed[i]} for i in mismatches[:12]],
   'shared_index_ties':row['shared_index_equal_distance_disagreements'],
   'metrics':{label:{k:row[label][k] for k in ['exact_id_crossings','common_id_boundary_fraction','common_family_boundary_fraction','common_small_components_le4_cells','common_component_area_median_blocks2']} for label in ['before','after']}}),flush=True)
 assert row['surface_reference_climate_identical'], 'Reconstructed climate differs from real reference'
 assert not mismatches, 'Isolated reconstructed reference differs from real 0.3.1 output'
 maps.append(row)
assert len(a['full_windows'])==len(b['full_windows'])
for old,new in zip(a['full_windows'],b['full_windows']):
 for key in ['label','x','z','sample_columns','base_geometry_water_sha256']:assert old[key]==new[key],(key,old['label'])
if b['settings']=='minecraft:overworld':assert a['full_windows']==b['full_windows'] and not maps
else:
 assert len(maps)==2 and b['deep_climate_exact_checks']>1000 and b['stored_biome_checks']>=1024
 assert any(m['changed_biome_samples']>0 for m in maps)
result={'source_commit':os.environ['GITHUB_SHA'],'seed':b['seed'],'settings':b['settings'],'actual_server_runs':3,'maps':maps,
 'full_windows':b['full_windows'],'explicit_full_chunks_per_pass':b['explicit_full_chunks'],
 'deep_climate_checks':b['deep_climate_exact_checks'],'stored_biome_checks':b['stored_biome_checks'],'repeat_checks':b['repeated_source_checks'],
 'saved_world_identical':True,'scope':'Maps are native source samples with separate last-result indexes; FULL coverage is separately counted. Boundaries and areas are finite, resolution-dependent observations.'}
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n')
print('FRESHGENERATOR_BIOME_REGION_COMPARISON_PASS seed='+str(b['seed']))
