"""Paired coordinates, not independently selected nice-looking regions."""
import json,os
from pathlib import Path
p=Path('build/reports/ecotone-fixed')
allowed={'plains','sunflower_plains','forest','flower_forest','birch_forest','old_growth_birch_forest','dark_forest','meadow','cherry_grove','windswept_forest','windswept_hills','windswept_gravelly_hills','grove','snowy_slopes','jagged_peaks','frozen_peaks','stony_peaks','taiga','snowy_taiga','snowy_plains'}
allowed={'minecraft:'+s for s in allowed}
snow={'minecraft:snow_block','minecraft:powder_snow','minecraft:ice','minecraft:packed_ice'}
result={'source_commit':os.environ.get('GITHUB_SHA','local'),'reference_source':'4448d9dcf95feea951890d8ef65d8e8b8163af13','server_runs':6,'seeds':{}}
total_before=total_after=0
for seed in [42,1337,-20261111]:
 a=json.loads((p/f'reference-{seed}.json').read_text());b=json.loads((p/f'candidate-{seed}.json').read_text())
 assert a['completed'] and b['completed'] and a['seed']==b['seed']==seed
 assert a['production_source']==result['reference_source']
 assert b['production_source']==os.environ['GITHUB_SHA']
 assert b['snow_native_safety_checks']>1000 and b['deep_climate_mismatches']==0
 assert len(a['windows'])==len(b['windows'])==6
 diffs=0;columns=0;edges=oldcuts=newcuts=0;oldcaves=newcaves=0;changed_materials=0
 windows=[]
 for wa,wb in zip(a['windows'],b['windows']):
  assert (wa['min_x'],wa['min_z'])==(wb['min_x'],wb['min_z'])
  ca={(c[0],c[1]):c for c in wa['columns']};cb={(c[0],c[1]):c for c in wb['columns']}
  assert ca.keys()==cb.keys()
  oldcaves+=wa['exposed_cave_columns'];newcaves+=wb['exposed_cave_columns']
  for at,c in ca.items():
   d=cb[at];columns+=1
   diffs+=int((c[2],c[3],c[5])!=(d[2],d[3],d[5]))
   changed_materials+=int(c[7]!=d[7])
   for nb in [(at[0]+1,at[1]),(at[0],at[1]+1)]:
    if nb not in ca:continue
    n=ca[nb];m=cb[nb]
    if c[6]==n[6] or any(k[6] not in allowed or k[5]<144 or k[9] or not k[10] or abs(k[4]-k[2])>2 for k in [c,n,d,m]):continue
    edges+=1
    oldcuts+=int((c[7] in snow or c[8])!=(n[7] in snow or n[8]))
    newcuts+=int((d[7] in snow or d[8])!=(m[7] in snow or m[8]))
  windows.append({'x':wa['min_x'],'z':wa['min_z'],'exposed_cave_before':wa['exposed_cave_columns'],'exposed_cave_after':wb['exposed_cave_columns']})
 assert diffs==0,('Base terrain changed',seed,diffs)
 assert newcaves==0,('Exposed cave biome remains in fixed windows',seed,newcaves)
 total_before+=oldcuts;total_after+=newcuts
 result['seeds'][str(seed)]={'columns_compared':columns,'base_terrain_differences':diffs,'changed_top_material_columns':changed_materials,'baseline_boundary_edges_checked':edges,'snow_cut_edges_before':oldcuts,'snow_cut_edges_after':newcuts,'exposed_cave_columns_before':oldcaves,'exposed_cave_columns_after':newcaves,'snow_native_safety_checks':b['snow_native_safety_checks'],'deep_climate_checks':b['deep_climate_checks'],'windows':windows}
assert sum(s['exposed_cave_columns_before'] for s in result['seeds'].values())>0,'Reference did not reproduce the reported problem'
assert total_before>0 and total_after<total_before,('No measured snow-boundary improvement',total_before,total_after)
result.update({'snow_boundary_edges_before':total_before,'snow_boundary_edges_after':total_after,'scope':'fixed sampled windows, structures off here and checked separately; not universal visual quality or biome rarity statistics'})
(p/'comparison.json').write_text(json.dumps(result,indent=2)+'\n')
print('FRESHGENERATOR_ECOTONE_COMPARISON_PASS '+json.dumps(result))
